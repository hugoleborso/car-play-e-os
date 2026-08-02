/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameCodecTest {

    @Test
    fun `unfragmented message round-trips through a single bulk frame`() {
        val payload = Random(1).nextBytes(200)
        val frames = FrameEncoder.encode(channel = 7, flags = FrameFlags.ENCRYPTED, payload = payload)

        assertEquals(1, frames.size)
        // 4-byte header only: a bulk frame carries no total-length field.
        assertEquals(FrameHeader.BASE_HEADER_SIZE + payload.size, frames[0].size)

        val decoder = FrameDecoder()
        decoder.feed(frames[0])
        val frame = decoder.poll()!!

        assertEquals(7, frame.header.channel)
        assertTrue(frame.header.isFirst && frame.header.isLast)
        assertTrue(frame.header.isEncrypted)
        assertNull(frame.header.totalLength)
        assertArrayEquals(payload, frame.payload)
        assertNull(decoder.poll())
    }

    @Test
    fun `fragmented message announces total length only on the opening frame`() {
        val payload = Random(2).nextBytes(2500)
        val frames = FrameEncoder.encode(channel = 3, flags = 0, payload = payload, fragmentSize = 1000)

        assertEquals(3, frames.size)
        assertEquals(FrameHeader.EXTENDED_HEADER_SIZE + 1000, frames[0].size)
        assertEquals(FrameHeader.BASE_HEADER_SIZE + 1000, frames[1].size)
        assertEquals(FrameHeader.BASE_HEADER_SIZE + 500, frames[2].size)

        val decoder = FrameDecoder()
        val assembler = MessageAssembler()
        var assembled: AssembledMessage? = null
        for (bytes in frames) {
            decoder.feed(bytes)
            while (true) {
                val frame = decoder.poll() ?: break
                assembler.accept(frame)?.let { assembled = it }
            }
        }

        assertArrayEquals(payload, assembled!!.payload)
        assertEquals(3, assembled!!.channel)
    }

    @Test
    fun `decoder tolerates reads that split frames at arbitrary offsets`() {
        // USB bulk transfers and TCP segments do not respect frame boundaries.
        val payload = Random(3).nextBytes(5000)
        val wire = FrameEncoder.encode(4, FrameFlags.CONTROL, payload, fragmentSize = 700)
            .reduce { a, b -> a + b }

        val decoder = FrameDecoder(initialCapacity = 16)
        val assembler = MessageAssembler()
        var assembled: AssembledMessage? = null

        val rng = Random(4)
        var offset = 0
        while (offset < wire.size) {
            val chunk = minOf(rng.nextInt(1, 37), wire.size - offset)
            decoder.feed(wire, offset, chunk)
            offset += chunk
            while (true) {
                val frame = decoder.poll() ?: break
                assembler.accept(frame)?.let { assembled = it }
            }
        }

        assertArrayEquals(payload, assembled!!.payload)
        assertTrue(assembled!!.isControl)
    }

    @Test
    fun `channels interleave without corrupting each other`() {
        // A large video message is fragmented while control traffic slips between
        // its fragments; reassembly state must be per-channel.
        val video = Random(5).nextBytes(3000)
        val ping = Random(6).nextBytes(12)

        val videoFrames = FrameEncoder.encode(9, 0, video, fragmentSize = 1000)
        val pingFrame = FrameEncoder.encode(0, FrameFlags.CONTROL, ping).single()

        val decoder = FrameDecoder()
        val assembler = MessageAssembler()
        val out = mutableListOf<AssembledMessage>()

        for (bytes in listOf(videoFrames[0], pingFrame, videoFrames[1], videoFrames[2])) {
            decoder.feed(bytes)
            while (true) {
                val frame = decoder.poll() ?: break
                assembler.accept(frame)?.let { out += it }
            }
        }

        assertEquals(2, out.size)
        assertEquals(0, out[0].channel)
        assertArrayEquals(ping, out[0].payload)
        assertEquals(9, out[1].channel)
        assertArrayEquals(video, out[1].payload)
    }

    @Test
    fun `encrypted fragments announce the plaintext length and carry ciphertext lengths`() {
        // The two length fields in a frame are measured in different units:
        // payloadLength counts this frame's ciphertext, totalLength counts the
        // whole message's plaintext. Encryption expands each fragment, so the
        // sum of the frame lengths deliberately exceeds the announced total.
        // Getting this backwards makes every fragmented encrypted message fail
        // reassembly with a length mismatch that looks like a framing bug.
        val overhead = 29
        val encrypt: (ByteArray) -> ByteArray = { plain -> ByteArray(overhead) + plain }
        val decrypt: (ByteArray) -> ByteArray = { cipher -> cipher.copyOfRange(overhead, cipher.size) }

        val payload = Random(9).nextBytes(2500)
        val frames = FrameEncoder.encode(
            channel = 5,
            flags = FrameFlags.ENCRYPTED,
            payload = payload,
            fragmentSize = 1000,
            encrypt = encrypt,
        )

        assertEquals(3, frames.size)
        // Opening frame: 8-byte header, 1000 bytes of plaintext plus overhead.
        assertEquals(FrameHeader.EXTENDED_HEADER_SIZE + 1000 + overhead, frames[0].size)

        val decoder = FrameDecoder()
        val assembler = MessageAssembler()
        var assembled: AssembledMessage? = null
        for (bytes in frames) {
            decoder.feed(bytes)
            while (true) {
                val frame = decoder.poll() ?: break
                assertEquals(payload.size.toLong(), frame.header.totalLength ?: payload.size.toLong())
                // Decrypt per frame, then reassemble. Reassembling first would
                // compare ciphertext byte counts against a plaintext total.
                assembler.accept(frame.header, decrypt(frame.payload))?.let { assembled = it }
            }
        }

        assertArrayEquals(payload, assembled!!.payload)
    }

    @Test
    fun `zero length final fragment is accepted`() {
        // Senders that split at ">= fragment size" emit an empty LAST frame when
        // the payload is an exact multiple. Rejecting it would drop real traffic.
        val assembler = MessageAssembler()
        assembler.accept(Frame(FrameHeader(6, FrameFlags.FIRST, 8, totalLength = 8), ByteArray(8) { 1 }))
        val completed = assembler.accept(Frame(FrameHeader(6, FrameFlags.LAST, 0), ByteArray(0)))
        assertEquals(8, completed!!.payload.size)
    }

    @Test
    fun `continuation frame without an open message is rejected`() {
        val assembler = MessageAssembler()
        val orphan = Frame(FrameHeader(2, FrameFlags.LAST, 4), ByteArray(4))
        assertThrows(FrameFormatException::class.java) { assembler.accept(orphan) }
    }

    @Test
    fun `message shorter than its announced total length is rejected`() {
        val assembler = MessageAssembler()
        assembler.accept(Frame(FrameHeader(2, FrameFlags.FIRST, 4, totalLength = 100), ByteArray(4)))
        val short = Frame(FrameHeader(2, FrameFlags.LAST, 4), ByteArray(4))
        assertThrows(FrameFormatException::class.java) { assembler.accept(short) }
    }

    @Test
    fun `flags flipping mid-message is rejected`() {
        val assembler = MessageAssembler()
        assembler.accept(
            Frame(FrameHeader(2, FrameFlags.FIRST or FrameFlags.ENCRYPTED, 4, totalLength = 8), ByteArray(4))
        )
        // Same channel, same message, but the ENCRYPTED bit dropped: desynchronised.
        val flipped = Frame(FrameHeader(2, FrameFlags.LAST, 4), ByteArray(4))
        assertThrows(FrameFormatException::class.java) { assembler.accept(flipped) }
    }

    @Test
    fun `oversized announced length is rejected before allocating`() {
        val assembler = MessageAssembler(maxMessageBytes = 1024)
        val huge = Frame(FrameHeader(2, FrameFlags.FIRST, 4, totalLength = 4_000_000_000L), ByteArray(4))
        assertThrows(FrameFormatException::class.java) { assembler.accept(huge) }
    }

    @Test
    fun `header rejects a total length on a bulk frame`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameHeader(1, FrameFlags.BULK, 4, totalLength = 4)
        }
    }
}
