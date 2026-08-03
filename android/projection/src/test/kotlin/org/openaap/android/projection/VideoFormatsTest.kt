/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openaap.protocol.proto.FrameGeometry
import org.openaap.protocol.proto.FrameRate
import org.openaap.protocol.proto.VideoFormat

/**
 * These run on the JVM without a device, which is the point: format selection
 * and bitrate arithmetic are ordinary logic and should not need a phone plugged
 * into a car to verify.
 */
class VideoFormatsTest {

    private fun format(
        geometry: FrameGeometry,
        rate: FrameRate = FrameRate.FRAME_RATE_30,
        density: Int = 0,
        insetWidth: Int = 0,
        insetHeight: Int = 0,
    ): VideoFormat = VideoFormat.newBuilder()
        .setGeometry(geometry)
        .setRate(rate)
        .setDensity(density)
        .setInsetWidth(insetWidth)
        .setInsetHeight(insetHeight)
        .build()

    @Test
    fun `geometry indices resolve to the pixel sizes they stand for`() {
        // The wire carries an index, not a size. Getting this table wrong makes
        // a display of the wrong dimensions, which shows up as a garbled picture
        // rather than as an error.
        assertEquals(800 to 480, resolveSize(FrameGeometry.GEOMETRY_800_480))
        assertEquals(1280 to 720, resolveSize(FrameGeometry.GEOMETRY_1280_720))
        assertEquals(1920 to 1080, resolveSize(FrameGeometry.GEOMETRY_1920_1080))
    }

    private fun resolveSize(geometry: FrameGeometry): Pair<Int, Int> {
        val resolved = VideoFormats.resolve(format(geometry))!!
        return resolved.width to resolved.height
    }

    @Test
    fun `an uninterpretable geometry resolves to null rather than a guess`() {
        assertNull(VideoFormats.resolve(format(FrameGeometry.GEOMETRY_NONE)))
    }

    @Test
    fun `an uninterpretable frame rate resolves to null`() {
        assertNull(
            VideoFormats.resolve(
                format(FrameGeometry.GEOMETRY_800_480, rate = FrameRate.FRAME_RATE_NONE)
            )
        )
    }

    @Test
    fun `the largest interpretable format wins, then the higher frame rate`() {
        val advertised = listOf(
            format(FrameGeometry.GEOMETRY_800_480),
            format(FrameGeometry.GEOMETRY_1280_720, FrameRate.FRAME_RATE_30),
            format(FrameGeometry.GEOMETRY_1280_720, FrameRate.FRAME_RATE_60),
        )
        val (index, chosen) = VideoFormats.choose(advertised)!!
        assertEquals(2, index)
        assertEquals(1280, chosen.width)
        assertEquals(60, chosen.framesPerSecond)
    }

    @Test
    fun `the chosen index refers to the advertised list, not to our own ordering`() {
        // The head unit identifies a format by its position in what it sent. An
        // index into a filtered or sorted list would name a different format,
        // and the head unit would accept it without complaint.
        val advertised = listOf(
            format(FrameGeometry.GEOMETRY_NONE),
            format(FrameGeometry.GEOMETRY_1920_1080),
            format(FrameGeometry.GEOMETRY_800_480),
        )
        val (index, chosen) = VideoFormats.choose(advertised)!!
        assertEquals(1, index)
        assertEquals(1920, chosen.width)
    }

    @Test
    fun `formats we cannot interpret are skipped rather than chosen`() {
        val advertised = listOf(
            format(FrameGeometry.GEOMETRY_NONE),
            format(FrameGeometry.GEOMETRY_800_480),
        )
        val (index, _) = VideoFormats.choose(advertised)!!
        assertEquals(1, index)
    }

    @Test
    fun `a list with nothing interpretable yields no choice`() {
        assertNull(VideoFormats.choose(listOf(format(FrameGeometry.GEOMETRY_NONE))))
        assertNull(VideoFormats.choose(emptyList()))
    }

    @Test
    fun `margins reduce the drawable area`() {
        val resolved = VideoFormats.resolve(
            format(FrameGeometry.GEOMETRY_800_480, insetWidth = 40, insetHeight = 20)
        )!!
        assertEquals(760, resolved.usableWidth)
        assertEquals(460, resolved.usableHeight)
    }

    @Test
    fun `a missing density falls back rather than producing a zero-dpi display`() {
        assertEquals(
            VideoFormats.DEFAULT_DENSITY_DPI,
            VideoFormats.resolve(format(FrameGeometry.GEOMETRY_800_480))!!.densityDpi,
        )
        assertEquals(
            220,
            VideoFormats.resolve(format(FrameGeometry.GEOMETRY_800_480, density = 220))!!.densityDpi,
        )
    }

    @Test
    fun `frame interval is the reciprocal of the frame rate in microseconds`() {
        val thirty = VideoFormats.resolve(format(FrameGeometry.GEOMETRY_800_480))!!
        assertEquals(33_333L, thirty.frameIntervalMicros)

        val sixty = VideoFormats.resolve(
            format(FrameGeometry.GEOMETRY_800_480, FrameRate.FRAME_RATE_60)
        )!!
        assertEquals(16_666L, sixty.frameIntervalMicros)
    }

    @Test
    fun `bitrate rises with pixel rate and stays within the clamps`() {
        val small = VideoFormats.bitrateFor(
            VideoFormats.resolve(format(FrameGeometry.GEOMETRY_800_480))!!
        )
        val large = VideoFormats.bitrateFor(
            VideoFormats.resolve(
                format(FrameGeometry.GEOMETRY_1920_1080, FrameRate.FRAME_RATE_60)
            )!!
        )

        assertTrue("a bigger display should not get less bitrate", large > small)
        assertTrue("floor keeps a small display from blocking up", small >= 2_000_000)
        assertTrue("ceiling keeps the link and decoder comfortable", large <= 12_000_000)
    }
}
