/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The interface the car actually shows.
 *
 * Deliberately almost empty, and that is the point at this stage. The open
 * question is whether a projected surface reaches a production head unit at
 * all, and answering it needs a picture that is unmistakable when it arrives
 * and diagnostic when it half-arrives. A rich interface would confound the two:
 * a blank screen could mean the stream failed, or that our layout drew nothing.
 *
 * So: a large high-contrast panel, a running clock, and the negotiated geometry
 * printed on screen. The clock is the load-bearing element — a still image
 * proves a keyframe arrived, but only a moving one proves the stream is live
 * rather than frozen on the first frame, which is the most likely partial
 * failure and is invisible in a screenshot.
 *
 * Sized in the car's pixels, not the phone's. Everything here uses the geometry
 * the head unit advertised, because a layout that assumed the phone's density
 * would be drawn at the wrong scale on a display of a different size — legible
 * on a desk, unreadable at arm's length.
 */
public object CarUi {

    /** Builds the projected interface for a display of [format]. */
    public fun build(context: Context, format: ResolvedVideoFormat): View {
        // Scale from the car's own geometry rather than the phone's density.
        // A head unit that reports 160dpi at 800x480 and one that reports the
        // same at 1920x1080 are physically similar screens, so type that is
        // readable on both has to follow the pixel count.
        val unit = format.usableHeight / 24f

        val clock = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 5f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        return object : LinearLayout(context) {
            override fun draw(canvas: android.graphics.Canvas) {
                // Refreshed on every frame rather than by a timer. The renderer
                // already runs at the negotiated frame rate, and a second clock
                // would drift against it and occasionally skip a second.
                clock.text = TIME.format(Date())
                super.draw(canvas)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND)
            setPadding(unit.toInt() * 2, unit.toInt() * 2, unit.toInt() * 2, unit.toInt() * 2)

            addView(
                TextView(context).apply {
                    text = "openaap"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 3f)
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(ACCENT)
                    gravity = Gravity.CENTER
                    letterSpacing = 0.2f
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )

            addView(clock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            addView(
                TextView(context).apply {
                    // The negotiated geometry, on screen. If the picture arrives
                    // stretched or cropped, this says whether we drew the wrong
                    // size or the head unit scaled what we sent -- two different
                    // bugs that look identical from the passenger seat.
                    text = "$format"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 1.2f)
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(MUTED)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )

            addView(
                TextView(context).apply {
                    text = "projection from a de-Googled phone"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 1.2f)
                    setTextColor(MUTED)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )
        }
    }

    // Dark by default: a car display at night with a white background is
    // genuinely unpleasant, and every production projection UI is dark for that
    // reason rather than for fashion.
    private val BACKGROUND = Color.rgb(12, 14, 16)
    private val ACCENT = Color.rgb(122, 184, 170)
    private val MUTED = Color.rgb(130, 140, 145)

    private val TIME = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
}
