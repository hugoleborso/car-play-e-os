/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import org.openaap.protocol.proto.FrameGeometry
import org.openaap.protocol.proto.FrameRate
import org.openaap.protocol.proto.VideoFormat

/**
 * A concrete video format, resolved from the enum indices the protocol carries.
 *
 * The wire format does not transmit pixel counts. It transmits an index into a
 * fixed table, so `2` means 1280x720 rather than anything derivable. Everything
 * above this layer works in pixels; this is where the translation happens, once.
 */
public data class ResolvedVideoFormat(
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val densityDpi: Int,
    val marginWidth: Int = 0,
    val marginHeight: Int = 0,
) {
    /** Microseconds between frames, the unit media timestamps are expressed in. */
    public val frameIntervalMicros: Long get() = 1_000_000L / framesPerSecond

    /**
     * The area actually drawn, once the head unit's margins are removed.
     *
     * Margins exist because some head units letterbox the projected image behind
     * their own furniture. Drawing under them wastes bitrate on pixels nobody
     * sees, and worse, puts controls where they cannot be touched.
     */
    public val usableWidth: Int get() = width - marginWidth
    public val usableHeight: Int get() = height - marginHeight

    override fun toString(): String = "${width}x$height@${framesPerSecond}fps ${densityDpi}dpi"
}

/** Translates between protocol enum indices and real pixel geometry. */
public object VideoFormats {

    /**
     * The geometries the protocol can express.
     *
     * Deliberately not extended past what is documented. Modern head units use
     * index values of 4 and above, but no public source says what they mean, and
     * guessing would produce a display of the wrong size — which fails as a
     * garbled picture rather than as an error.
     */
    private val geometries: Map<FrameGeometry, Pair<Int, Int>> = mapOf(
        FrameGeometry.GEOMETRY_800_480 to (800 to 480),
        FrameGeometry.GEOMETRY_1280_720 to (1280 to 720),
        FrameGeometry.GEOMETRY_1920_1080 to (1920 to 1080),
    )

    private val rates: Map<FrameRate, Int> = mapOf(
        FrameRate.FRAME_RATE_30 to 30,
        FrameRate.FRAME_RATE_60 to 60,
    )

    /** Default density when a head unit advertises none, matching a typical car display. */
    public const val DEFAULT_DENSITY_DPI: Int = 160

    /** Resolves one advertised format, or `null` if it uses values we cannot interpret. */
    public fun resolve(format: VideoFormat): ResolvedVideoFormat? {
        val (width, height) = geometries[format.geometry] ?: return null
        val fps = rates[format.rate] ?: return null
        return ResolvedVideoFormat(
            width = width,
            height = height,
            framesPerSecond = fps,
            densityDpi = format.density.takeIf { it > 0 } ?: DEFAULT_DENSITY_DPI,
            marginWidth = format.insetWidth.coerceAtLeast(0),
            marginHeight = format.insetHeight.coerceAtLeast(0),
        )
    }

    /**
     * Chooses which advertised format to project at.
     *
     * Returns the index into the advertised list as well as the format, because
     * the protocol identifies a choice by index and the head unit will not
     * recognise it any other way.
     *
     * The preference is the *largest* resolution we can interpret, then the
     * higher frame rate. A head unit that advertises several is telling us what
     * it can accept, and picking its best is what a driver expects. Formats we
     * cannot interpret are skipped rather than guessed at.
     */
    public fun choose(advertised: List<VideoFormat>): Pair<Int, ResolvedVideoFormat>? {
        var best: Pair<Int, ResolvedVideoFormat>? = null
        advertised.forEachIndexed { index, format ->
            val resolved = resolve(format) ?: return@forEachIndexed
            val current = best?.second
            val better = current == null ||
                resolved.width * resolved.height > current.width * current.height ||
                (resolved.width * resolved.height == current.width * current.height &&
                    resolved.framesPerSecond > current.framesPerSecond)
            if (better) best = index to resolved
        }
        return best
    }

    /**
     * A defensible bitrate for a car display.
     *
     * Derived from pixel rate rather than fixed per resolution, so an unusual
     * combination lands somewhere sensible. The coefficient is chosen for a link
     * that is a USB cable rather than a network: there is bandwidth to spare, and
     * the cost of a soft picture on a dashboard at arm's length is higher than
     * the cost of a few extra megabits.
     *
     * Clamped at both ends. The floor stops a low-resolution head unit getting a
     * picture that blocks up on motion; the ceiling stops a 1080p60 unit asking
     * the encoder for more than the link and the decoder will comfortably carry.
     */
    public fun bitrateFor(format: ResolvedVideoFormat): Int {
        val pixelsPerSecond = format.width.toLong() * format.height * format.framesPerSecond
        val bits = (pixelsPerSecond * BITS_PER_PIXEL_NUMERATOR / BITS_PER_PIXEL_DENOMINATOR)
        return bits.coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }

    // 0.12 bits per pixel: comfortable for H.264 Baseline on synthetic UI
    // content, which is mostly flat colour and text and compresses far better
    // than camera footage.
    private const val BITS_PER_PIXEL_NUMERATOR = 12L
    private const val BITS_PER_PIXEL_DENOMINATOR = 100L

    private const val MIN_BITRATE = 2_000_000
    private const val MAX_BITRATE = 12_000_000

    /**
     * How often to force a keyframe, in seconds.
     *
     * A head unit may begin decoding at any moment — it starts displaying when
     * the driver selects projection, not when we start sending — so it needs a
     * recovery point reasonably often. One second costs little on flat UI content
     * and bounds how long a freshly-attached display stays blank.
     */
    public const val KEYFRAME_INTERVAL_SECONDS: Int = 1
}
