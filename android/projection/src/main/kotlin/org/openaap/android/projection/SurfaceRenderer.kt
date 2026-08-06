/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup

/**
 * Draws a view hierarchy onto a [Surface] at a fixed rate, and routes the head
 * unit's touches back into it.
 *
 * ### Why not VirtualDisplay
 *
 * The obvious construction is a `VirtualDisplay` with a `Presentation` on it,
 * and it is what screen-mirroring code usually does. It was rejected here for
 * two reasons that only show up on a real phone.
 *
 * A `Presentation` is a `Dialog`, so it needs a window token. Shown from a
 * service — which is where projection lives, because there is no visible
 * activity while driving — it wants `SYSTEM_ALERT_WINDOW`: a permission the
 * user must grant through a separate settings screen, that some ROMs hide, and
 * that makes the app look like it wants to draw over other apps. For a diagnostic
 * tool people are already sideloading on trust, that is a bad trade.
 *
 * The second reason is the harder one. A head unit sends touches as
 * coordinates; something must turn those into events the UI reacts to.
 * Delivering them to a `VirtualDisplay` means `InputManager.injectInputEvent`,
 * which is `@hide` and gated behind `INJECT_EVENTS` — a signature permission,
 * available only to a platform-signed build. That single call is the difference
 * between an APK anyone can install and a ROM modification, and it is the wall
 * this project exists to stay on the right side of.
 *
 * Drawing our own views onto the encoder's surface sidesteps both. We own the
 * hierarchy, so touches go in through the ordinary `dispatchTouchEvent` path
 * with no permission at all, and the whole thing is an APK.
 *
 * The cost is real and worth stating: this can only project **our own** UI, not
 * an arbitrary app. Mirroring a third-party map would need the VirtualDisplay
 * path and everything above. That is also what Android Auto itself does — it
 * projects its own interface, not the phone's screen — so the limitation is
 * narrower than it sounds.
 */
public class SurfaceRenderer(
    private val context: Context,
    private val format: ResolvedVideoFormat,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var surface: Surface? = null
    private var root: View? = null

    @Volatile
    private var rendering = false

    /**
     * Starts drawing [content] onto [target].
     *
     * The view is measured and laid out at the head unit's usable geometry,
     * which is not the phone's: a car display is a different size and density,
     * and a layout that assumed the phone's would be drawn at the wrong scale
     * and clipped.
     */
    public fun start(target: Surface, content: View) {
        check(thread == null) { "renderer already started" }

        surface = target
        root = content.apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(format.usableWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(format.usableHeight, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, format.usableWidth, format.usableHeight)
        }

        val worker = HandlerThread("openaap-render").also { it.start() }
        thread = worker
        handler = Handler(worker.looper)
        rendering = true
        handler?.post(::drawFrame)
        Log.i(TAG, "rendering ${format.usableWidth}x${format.usableHeight} at ${format.framesPerSecond}fps")
    }

    /**
     * Draws one frame and schedules the next.
     *
     * Paced by absolute deadlines rather than a fixed delay between frames.
     * Posting "one interval from now" after each draw accumulates every frame's
     * drawing time into the interval, so a stream nominally at 60fps arrives at
     * 50-something and drifts further the busier the phone gets.
     */
    private fun drawFrame() {
        if (!rendering) return
        val startedAt = SystemClock.uptimeMillis()

        val target = surface
        val view = root
        if (target != null && view != null && target.isValid) {
            var canvas: Canvas? = null
            try {
                // Hardware canvas: the frame stays on the GPU all the way into
                // the encoder, which is the entire point of surface input.
                canvas = target.lockHardwareCanvas()
                view.draw(canvas)
            } catch (e: IllegalArgumentException) {
                // The surface went away underneath us -- the head unit stopped
                // the channel, or the session ended. Not worth a crash.
                Log.i(TAG, "surface no longer accepts frames; stopping")
                rendering = false
            } catch (e: IllegalStateException) {
                Log.i(TAG, "surface no longer accepts frames; stopping")
                rendering = false
            } finally {
                canvas?.let { runCatching { target.unlockCanvasAndPost(it) } }
            }
        }

        if (!rendering) return
        val nextAt = startedAt + intervalMillis
        handler?.postAtTime(::drawFrame, nextAt.coerceAtLeast(SystemClock.uptimeMillis()))
    }

    /**
     * Delivers a touch from the head unit into the view hierarchy.
     *
     * Coordinates arrive in the head unit's pixel space, which is the space the
     * hierarchy was laid out in, so no scaling is needed — as long as nothing
     * above here quietly lays out at the phone's geometry instead.
     *
     * Dispatched on the render thread so it cannot interleave with a draw. A
     * touch that mutates the hierarchy mid-draw is the classic source of a
     * one-frame tear that only ever reproduces in a moving car.
     */
    public fun dispatchTouch(action: Int, x: Float, y: Float, eventTimeMillis: Long) {
        val view = root ?: return
        handler?.post {
            val event = MotionEvent.obtain(
                eventTimeMillis,
                eventTimeMillis,
                action,
                x.coerceIn(0f, format.usableWidth.toFloat()),
                y.coerceIn(0f, format.usableHeight.toFloat()),
                0,
            )
            try {
                view.dispatchTouchEvent(event)
                // A view that changed state on touch needs re-laying out before
                // the next draw, or the change appears a frame late and taps
                // feel unresponsive on a screen already a frame behind.
                if (view.isLayoutRequested) {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(format.usableWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(format.usableHeight, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, format.usableWidth, format.usableHeight)
                }
            } finally {
                event.recycle()
            }
        }
    }

    public fun stop() {
        rendering = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        surface = null
        (root?.parent as? ViewGroup)?.removeView(root)
        root = null
        Log.i(TAG, "renderer stopped")
    }

    private val intervalMillis: Long get() = 1000L / format.framesPerSecond

    private companion object {
        const val TAG = "openaap.render"
    }
}
