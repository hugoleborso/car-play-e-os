/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import org.openaap.core.AapLink
import org.openaap.core.IncomingMessage
import org.openaap.core.ResolvedChannel
import org.openaap.core.ServiceHandler
import org.openaap.protocol.Messages
import org.openaap.protocol.proto.InputEvent
import org.openaap.protocol.proto.TouchPhase

/**
 * Turns the head unit's touches into events in the projected interface.
 *
 * The head unit is the only input device during a session: the phone is in a
 * pocket or a dock with its screen off, so every tap the driver makes arrives
 * here as coordinates and has to reach a view.
 *
 * This is the point at which a plain APK could have become a ROM modification.
 * The conventional route — a `VirtualDisplay` fed through
 * `InputManager.injectInputEvent` — needs `INJECT_EVENTS`, a signature
 * permission no sideloaded app can hold. Because [SurfaceRenderer] draws a
 * hierarchy we own, the same touch goes in through `dispatchTouchEvent` with no
 * permission at all. See that class for the full reasoning.
 */
public class TouchService(
    override val channel: ResolvedChannel,
    private val renderer: SurfaceRenderer,
    private val projected: ResolvedVideoFormat,
) : ServiceHandler {

    /**
     * The coordinate space the head unit sends touches in.
     *
     * Not assumed to be the video resolution. A head unit advertises the touch
     * surface separately, and it is free to differ — a 1920x1080 display with a
     * touch digitiser reported at 800x480 is a real configuration. Assuming they
     * match produces taps that land in roughly the right place near the top left
     * and drift further away toward the bottom right, which reads as a
     * calibration quirk rather than a bug and is miserable to chase in a car.
     */
    private val surface: Pair<Int, Int> = channel.advert
        .takeIf { it.hasInput() && it.input.hasTouchscreen() }
        ?.input
        ?.touchscreen
        ?.let { it.width to it.height }
        ?.takeIf { (width, height) -> width > 0 && height > 0 }
        ?: (projected.usableWidth to projected.usableHeight)

    override fun onOpened(link: AapLink) {
        Log.i(
            TAG,
            "touch surface ${surface.first}x${surface.second}, " +
                "projecting ${projected.usableWidth}x${projected.usableHeight}",
        )
    }

    override fun onMessage(link: AapLink, message: IncomingMessage) {
        // Message ids overlap between services -- 0x8001 is a media start
        // indication on a video channel and an input event here -- so this
        // handler must never be bound to anything but an input channel.
        if (message.messageId != Messages.INPUT_EVENT) return

        val event = runCatching { InputEvent.parseFrom(message.body) }
            .onFailure { Log.w(TAG, "unparseable input event", it) }
            .getOrNull() ?: return
        if (!event.hasTouch()) return

        val touch = event.touch
        val action = when (touch.phase) {
            TouchPhase.TOUCH_DOWN -> MotionEvent.ACTION_DOWN
            TouchPhase.TOUCH_UP -> MotionEvent.ACTION_UP
            TouchPhase.TOUCH_MOVE -> MotionEvent.ACTION_MOVE
            else -> return
        }

        // The pointer that actually changed, not the first in the list. With one
        // finger they are the same; with two, taking the first turns a second
        // finger's press into a jump of the first.
        val index = touch.changedIndex.takeIf { it < touch.pointsCount } ?: 0
        val point = touch.pointsList.getOrNull(index) ?: return

        renderer.dispatchTouch(
            action = action,
            x = point.x * projected.usableWidth.toFloat() / surface.first,
            y = point.y * projected.usableHeight.toFloat() / surface.second,
            // The head unit's own clock where it gives one. Gesture detection
            // measures intervals between events, so mixing two clocks turns a
            // deliberate long press into a tap whenever the link is slow.
            eventTimeMillis = if (event.hasStamp()) {
                event.stamp / NANOS_PER_MILLI
            } else {
                SystemClock.uptimeMillis()
            },
        )
    }

    private companion object {
        const val TAG = "openaap.touch"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
