/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MessagesTest {

    @Test
    fun `channel zero never carries the control flag`() {
        // Even for ids that are unambiguously control messages.
        assertFalse(Messages.needsControlFlag(0, Messages.DISCOVERY_REQUEST))
        assertFalse(Messages.needsControlFlag(0, Messages.PING_RESPONSE))
        assertFalse(Messages.needsControlFlag(0, Messages.VERSION_RESPONSE))
    }

    @Test
    fun `a control message addressed to a service channel carries the flag`() {
        // Channel open is the everyday case: it travels on the channel being
        // opened, but its id belongs to the control namespace.
        assertTrue(Messages.needsControlFlag(3, Messages.CHANNEL_OPEN_REQUEST))
        assertTrue(Messages.needsControlFlag(7, Messages.CHANNEL_OPEN_RESPONSE))
    }

    @Test
    fun `media ids stay in the service namespace despite their low numbers`() {
        // This is what the lower bound of 2 is for. Without it, every video
        // frame would be flagged as a control message and the head unit would
        // try to parse H.264 as protobuf.
        assertFalse(Messages.needsControlFlag(3, Messages.MEDIA_WITH_STAMP))
        assertFalse(Messages.needsControlFlag(3, Messages.MEDIA_PLAIN))
    }

    @Test
    fun `channel-specific ids stay in the service namespace`() {
        assertFalse(Messages.needsControlFlag(3, Messages.MEDIA_SETUP_REQUEST))
        assertFalse(Messages.needsControlFlag(1, Messages.INPUT_EVENT))
        assertFalse(Messages.needsControlFlag(2, Messages.SENSOR_READINGS))
    }

    @Test
    fun `message id round-trips through the payload prefix`() {
        val body = byteArrayOf(9, 8, 7)
        val payload = Messages.frame(Messages.DISCOVERY_RESPONSE, body)

        assertEquals(5, payload.size)
        val (id, decoded) = Messages.split(payload)
        assertEquals(Messages.DISCOVERY_RESPONSE, id)
        assertArrayEquals(body, decoded)
    }

    @Test
    fun `a message id above 0x7fff survives the round trip`() {
        // These ids set the high bit, which is where a signed-byte mistake shows.
        val payload = Messages.frame(Messages.VIDEO_FOCUS_INDICATION)
        assertEquals(Messages.VIDEO_FOCUS_INDICATION, Messages.split(payload).first)
        assertEquals(0x80, payload[0].toInt() and 0xFF)
        assertEquals(0x08, payload[1].toInt() and 0xFF)
    }

    @Test
    fun `a payload too short to hold an id is rejected`() {
        assertThrows<IllegalArgumentException> { Messages.split(byteArrayOf(1)) }
    }

    @Test
    fun `version request encodes as four big-endian bytes`() {
        val encoded = VersionExchange.encodeRequest(VersionExchange.Request(major = 1, minor = 6))
        assertArrayEquals(byteArrayOf(0, 1, 0, 6), encoded)
        assertEquals(VersionExchange.Request(1, 6), VersionExchange.parseRequest(encoded))
    }

    @Test
    fun `version response carries a status word after the version`() {
        val encoded = VersionExchange.encodeResponse(VersionExchange.Response(1, 6, VersionExchange.STATUS_MATCH))
        assertArrayEquals(byteArrayOf(0, 1, 0, 6, 0, 0), encoded)
        assertTrue(VersionExchange.parseResponse(encoded).matched)
    }

    @Test
    fun `a mismatch status is recognised`() {
        val response = VersionExchange.parseResponse(byteArrayOf(0, 1, 0, 1, 0xFF.toByte(), 0xFF.toByte()))
        assertFalse(response.matched)
        assertEquals(VersionExchange.STATUS_MISMATCH, response.status)
    }

    @Test
    fun `a truncated version exchange is rejected rather than read as zeros`() {
        assertThrows<IllegalArgumentException> { VersionExchange.parseRequest(byteArrayOf(0, 1)) }
        assertThrows<IllegalArgumentException> { VersionExchange.parseResponse(byteArrayOf(0, 1, 0, 6)) }
    }

    @Test
    fun `media stamp is eight big-endian bytes of microseconds`() {
        val stamp = 0x0001020304050607L
        val encoded = MediaStamp.write(stamp)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), encoded)
        assertEquals(stamp, MediaStamp.read(encoded))
    }

    @Test
    fun `a stamp with the high bit set is not read as negative`() {
        // Media clocks are unsigned; a naive signed read wraps here.
        val encoded = ByteArray(8) { 0xFF.toByte() }
        encoded[0] = 0x7F
        assertTrue(MediaStamp.read(encoded) > 0)
        assertEquals(0x7FFFFFFFFFFFFFFFL, MediaStamp.read(encoded))
    }
}
