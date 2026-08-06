/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns whatever is drawn on a [Surface] into the H.264 access units a head
 * unit expects.
 *
 * Surface input rather than byte buffers, which matters for more than tidiness:
 * it keeps the frame on the GPU from the compositor to the encoder, so a
 * 1080p60 stream never copies a pixel through the CPU. Feeding buffers instead
 * would make this the hottest code in the app.
 *
 * ### What the head unit needs, and why the defaults are wrong
 *
 * The wire format carries raw Annex-B access units, not an MP4 or any container.
 * `MediaCodec` already emits Annex-B for AVC, so no repackaging is needed — but
 * two defaults do have to be overridden:
 *
 * - **Baseline profile.** Head units of this era decode Baseline reliably and
 *   nothing above it reliably. The encoder will happily negotiate High if asked,
 *   and the failure mode is a picture that decodes on a desk and tears in a car.
 * - **Codec-specific data on every keyframe.** SPS and PPS arrive once, in
 *   `BUFFER_FLAG_CODEC_CONFIG`, before any frame. A head unit that starts
 *   decoding later — and it does, because the driver selects projection when
 *   they feel like it — has already missed them. They are cached here and
 *   prepended to each keyframe, which costs a few dozen bytes a second and
 *   removes an entire class of "connected but black" failure.
 */
public class H264Encoder(
    private val format: ResolvedVideoFormat,
    private val bitrate: Int = VideoFormats.bitrateFor(format),
) {

    /** One encoded access unit, ready to hand to the media channel. */
    public data class AccessUnit(
        /** Presentation time in microseconds, the unit the protocol stamps with. */
        val presentationMicros: Long,
        val payload: ByteArray,
        val keyframe: Boolean,
    ) {
        // Data classes compare arrays by identity, which turns any equality
        // check on this type into a silent falsehood. Tests do compare them.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is AccessUnit &&
                    presentationMicros == other.presentationMicros &&
                    keyframe == other.keyframe &&
                    payload.contentEquals(other.payload))

        override fun hashCode(): Int =
            (presentationMicros.hashCode() * 31 + keyframe.hashCode()) * 31 +
                payload.contentHashCode()
    }

    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)

    /** SPS and PPS, retained so they can lead every keyframe. */
    private var codecConfig: ByteArray? = null

    /**
     * The surface to draw into. Valid between [start] and [stop].
     *
     * Created by the encoder rather than passed in, because `MediaCodec`
     * allocates it and the buffer geometry has to match the encoder's.
     */
    public var inputSurface: Surface? = null
        private set

    /**
     * Configures the encoder and returns the surface to draw into.
     *
     * Throws if no device encoder can satisfy the format. That is worth
     * propagating rather than degrading: a car showing the wrong geometry is
     * harder to diagnose than one showing nothing.
     */
    public fun start(): Surface {
        check(codec == null) { "encoder already started" }

        val mediaFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            format.usableWidth,
            format.usableHeight,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, format.framesPerSecond)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoFormats.KEYFRAME_INTERVAL_SECONDS)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
            // Constant bitrate. A car link has fixed headroom and no buffer to
            // absorb a variable-rate burst, and a burst is exactly what a map
            // animation produces.
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        codec = encoder
        inputSurface = surface
        running.set(true)
        Log.i(TAG, "encoding $format at ${bitrate / 1000} kbps, Baseline")
        return surface
    }

    /**
     * Blocks until the next access unit is available, or the encoder stops.
     *
     * Returns `null` when there is nothing to send and the caller should ask
     * again — a timeout, a format change, or the codec-config buffer, none of
     * which are frames. Returns `null` permanently once [stop] has run.
     *
     * Deliberately blocking rather than callback-based. The media channel has a
     * credit window and must not run ahead of it, so a caller that can simply
     * stop asking is easier to keep correct than one that has to buffer or drop
     * inside a callback.
     */
    public fun drain(timeoutMicros: Long = DRAIN_TIMEOUT_MICROS): AccessUnit? {
        val encoder = codec ?: return null
        if (!running.get()) return null

        val info = MediaCodec.BufferInfo()
        val index = try {
            encoder.dequeueOutputBuffer(info, timeoutMicros)
        } catch (e: IllegalStateException) {
            // Racing a stop() from another thread. Not an error worth throwing:
            // the session is ending and there is nothing left to send.
            Log.i(TAG, "encoder stopped while draining")
            return null
        }
        if (index < 0) return null

        return try {
            val buffer = encoder.getOutputBuffer(index) ?: return null
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val bytes = ByteArray(info.size)
            buffer.get(bytes)

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // Not a frame. Keep it: every keyframe from here on carries it.
                codecConfig = bytes
                Log.i(TAG, "captured ${bytes.size} bytes of codec-specific data")
                return null
            }

            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val payload = if (keyframe) prependCodecConfig(bytes) else bytes
            AccessUnit(info.presentationTimeUs, payload, keyframe)
        } finally {
            runCatching { encoder.releaseOutputBuffer(index, false) }
        }
    }

    /**
     * Asks for a keyframe at the next opportunity.
     *
     * Called when the head unit takes video focus. Without it the car shows
     * nothing until the periodic keyframe comes round, which reads as a
     * projection that took a second to wake up.
     */
    public fun requestKeyframe() {
        val encoder = codec ?: return
        runCatching {
            encoder.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        }.onFailure { Log.w(TAG, "could not request a keyframe", it) }
    }

    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        val encoder = codec
        codec = null
        inputSurface = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        // The surface belongs to the codec and is invalid once released, so it
        // is not released separately.
        Log.i(TAG, "encoder stopped")
    }

    private fun prependCodecConfig(frame: ByteArray): ByteArray {
        val config = codecConfig ?: return frame
        return ByteArray(config.size + frame.size).also {
            config.copyInto(it, 0)
            frame.copyInto(it, config.size)
        }
    }

    private companion object {
        const val TAG = "openaap.encoder"

        // Long enough that a caller polling in a loop does not spin, short
        // enough that a stop is noticed promptly.
        const val DRAIN_TIMEOUT_MICROS = 100_000L
    }
}
