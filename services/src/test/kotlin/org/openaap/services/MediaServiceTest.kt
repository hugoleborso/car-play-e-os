/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.services

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.openaap.core.AapLink
import org.openaap.core.IncomingMessage
import org.openaap.core.ResolvedChannel
import org.openaap.core.ServiceKind
import org.openaap.crypto.AapTlsEngine
import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki
import org.openaap.crypto.TlsRole
import org.openaap.protocol.FrameDecoder
import org.openaap.protocol.MediaStamp
import org.openaap.protocol.MessageAssembler
import org.openaap.protocol.Messages
import org.openaap.protocol.proto.ChannelAdvert
import org.openaap.protocol.proto.MediaAck
import org.openaap.protocol.proto.MediaReadiness
import org.openaap.protocol.proto.MediaSetupResponse
import org.openaap.protocol.proto.VideoFocusIndication
import org.openaap.protocol.proto.VideoFocusState
import org.openaap.transport.Transport

class MediaServiceTest {

    /**
     * Captures what the service put on the wire, decoded back into messages.
     *
     * A recording transport rather than a connected loopback pair: a read on a
     * loopback blocks until the peer speaks, and in these tests nothing ever
     * speaks back — the service is driven by handing it messages directly.
     */
    private class Wire {
        private val written = java.io.ByteArrayOutputStream()

        private val transport = object : Transport {
            override val description: String = "capture"
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = -1
            override fun write(source: ByteArray, offset: Int, length: Int) {
                written.write(source, offset, length)
            }

            override fun close() = Unit
        }

        val link = AapLink(
            transport,
            AapTlsEngine(
                TlsRole.SERVER,
                StaticCredentialProvider.of("test", TestPki.selfSigned("test")),
            ),
        )

        /** Channel and message id of everything sent so far, oldest first. */
        fun sent(): List<Pair<Int, Int>> = bodies().map { it.first to it.second }

        /** Channel, message id and body of everything sent so far. */
        fun bodies(): List<Triple<Int, Int, ByteArray>> {
            // Decoded from scratch on each call. The session never enables
            // encryption in these tests, so the capture is plaintext throughout
            // and re-parsing it is both cheap and stateless.
            val decoder = FrameDecoder()
            val assembler = MessageAssembler()
            val out = mutableListOf<Triple<Int, Int, ByteArray>>()
            decoder.feed(written.toByteArray())
            while (true) {
                val frame = decoder.poll() ?: break
                assembler.accept(frame)?.let { assembled ->
                    val (id, body) = Messages.split(assembled.payload)
                    out += Triple(assembled.channel, id, body)
                }
            }
            return out
        }
    }

    private fun channel(kind: ServiceKind, id: Int = 3) =
        ResolvedChannel(id, kind, ChannelAdvert.newBuilder().setChannelId(id).build())

    private fun incoming(channelId: Int, messageId: Int, body: ByteArray) =
        IncomingMessage(channelId, messageId, body, wasEncrypted = false, hadControlFlag = false)

    private class RecordingProducer : MediaProducer {
        var started = false
        var stopped = false
        var chosenFormat = -1
        private var sink: ((Long, ByteArray) -> Unit)? = null

        override fun start(format: Int, sink: (Long, ByteArray) -> Unit) {
            started = true
            chosenFormat = format
            this.sink = sink
        }

        override fun stop() {
            stopped = true
        }

        fun emit(micros: Long, payload: ByteArray) {
            sink?.invoke(micros, payload)
        }
    }

    private fun setupResponse(maxUnacked: Int, format: Int = 0): ByteArray =
        MediaSetupResponse.newBuilder()
            .setReadiness(MediaReadiness.MEDIA_READINESS_READY)
            .setMaxUnacked(maxUnacked)
            .addAcceptedFormats(format)
            .build()
            .toByteArray()

    @Test
    fun `video waits for focus before starting, audio does not`() {
        // The step that is easy to miss: a video channel that starts early gets
        // a stream the head unit is not displaying, which looks like a working
        // connection with a black screen.
        val wire = Wire()
        val producer = RecordingProducer()
        val video = MediaService(channel(ServiceKind.VIDEO), producer)

        video.onOpened(wire.link)
        video.onMessage(wire.link, incoming(3, Messages.MEDIA_SETUP_RESPONSE, setupResponse(1)))

        assertFalse(producer.started, "video started before focus was granted")
        assertTrue(
            wire.sent().none { it.second == Messages.MEDIA_START_INDICATION },
            "start indication sent before focus",
        )

        video.onMessage(
            wire.link,
            incoming(
                3,
                Messages.VIDEO_FOCUS_INDICATION,
                VideoFocusIndication.newBuilder().setState(VideoFocusState.VIDEO_FOCUS_HELD).build().toByteArray(),
            ),
        )

        assertTrue(producer.started, "video did not start after focus was granted")
        assertTrue(wire.sent().any { it.second == Messages.MEDIA_START_INDICATION })
    }

    @Test
    fun `an audio channel starts as soon as setup succeeds`() {
        val wire = Wire()
        val producer = RecordingProducer()
        val audio = MediaService(channel(ServiceKind.MEDIA_AUDIO, id = 4), producer)

        audio.onOpened(wire.link)
        audio.onMessage(wire.link, incoming(4, Messages.MEDIA_SETUP_RESPONSE, setupResponse(1)))

        assertTrue(producer.started, "audio waited for a focus indication that never comes")
    }

    @Test
    fun `media carries an eight byte microsecond stamp ahead of the payload`() {
        val wire = Wire()
        val producer = RecordingProducer()
        val audio = MediaService(channel(ServiceKind.MEDIA_AUDIO, id = 4), producer)
        audio.onOpened(wire.link)
        audio.onMessage(wire.link, incoming(4, Messages.MEDIA_SETUP_RESPONSE, setupResponse(4)))

        val payload = byteArrayOf(1, 2, 3, 4, 5)
        producer.emit(123_456_789L, payload)

        val media = wire.bodies().last { it.second == Messages.MEDIA_WITH_STAMP }
        assertEquals(123_456_789L, MediaStamp.read(media.third))
        assertArrayEquals(payload, media.third.copyOfRange(MediaStamp.SIZE, media.third.size))
    }

    @Test
    fun `a window of one permits exactly one frame until it is acknowledged`() {
        val wire = Wire()
        val producer = RecordingProducer()
        val audio = MediaService(channel(ServiceKind.MEDIA_AUDIO, id = 4), producer)
        audio.onOpened(wire.link)
        audio.onMessage(wire.link, incoming(4, Messages.MEDIA_SETUP_RESPONSE, setupResponse(1)))

        producer.emit(1, byteArrayOf(1))
        producer.emit(2, byteArrayOf(2))
        producer.emit(3, byteArrayOf(3))

        assertEquals(
            1,
            wire.bodies().count { it.second == Messages.MEDIA_WITH_STAMP },
            "more than one frame went out against a window of one",
        )
        assertEquals(2, audio.dropped)

        audio.onMessage(
            wire.link,
            incoming(4, Messages.MEDIA_ACK, MediaAck.newBuilder().setCount(1).build().toByteArray()),
        )
        producer.emit(4, byteArrayOf(4))
        assertEquals(2, wire.bodies().count { it.second == Messages.MEDIA_WITH_STAMP })
    }

    @Test
    fun `a head unit that advertises no window is treated as asking for one at a time`() {
        // Reading "absent" as "unlimited" is the mistake that makes a channel
        // work for a few seconds and then stall.
        val wire = Wire()
        val producer = RecordingProducer()
        val audio = MediaService(channel(ServiceKind.MEDIA_AUDIO, id = 4), producer)
        audio.onOpened(wire.link)
        audio.onMessage(
            wire.link,
            incoming(
                4,
                Messages.MEDIA_SETUP_RESPONSE,
                MediaSetupResponse.newBuilder()
                    .setReadiness(MediaReadiness.MEDIA_READINESS_READY)
                    .addAcceptedFormats(0)
                    .build()
                    .toByteArray(),
            ),
        )

        producer.emit(1, byteArrayOf(1))
        producer.emit(2, byteArrayOf(2))
        assertEquals(1, wire.bodies().count { it.second == Messages.MEDIA_WITH_STAMP })
    }

    @Test
    fun `a refused setup does not start the producer`() {
        val wire = Wire()
        val producer = RecordingProducer()
        val video = MediaService(channel(ServiceKind.VIDEO), producer)
        video.onOpened(wire.link)
        video.onMessage(
            wire.link,
            incoming(
                3,
                Messages.MEDIA_SETUP_RESPONSE,
                MediaSetupResponse.newBuilder()
                    .setReadiness(MediaReadiness.MEDIA_READINESS_FAILED)
                    .build()
                    .toByteArray(),
            ),
        )
        assertFalse(producer.started)
    }

    @Test
    fun `revoked focus stops the producer and tells the head unit`() {
        val wire = Wire()
        val producer = RecordingProducer()
        val video = MediaService(channel(ServiceKind.VIDEO), producer)
        video.onOpened(wire.link)
        video.onMessage(wire.link, incoming(3, Messages.MEDIA_SETUP_RESPONSE, setupResponse(1)))
        video.onMessage(
            wire.link,
            incoming(
                3,
                Messages.VIDEO_FOCUS_INDICATION,
                VideoFocusIndication.newBuilder().setState(VideoFocusState.VIDEO_FOCUS_HELD).build().toByteArray(),
            ),
        )
        assertTrue(producer.started)

        video.onMessage(
            wire.link,
            incoming(
                3,
                Messages.VIDEO_FOCUS_INDICATION,
                VideoFocusIndication.newBuilder()
                    .setState(VideoFocusState.VIDEO_FOCUS_RELEASED)
                    .build()
                    .toByteArray(),
            ),
        )

        assertTrue(producer.stopped)
        assertTrue(wire.sent().any { it.second == Messages.MEDIA_STOP_INDICATION })
    }
}

class CreditWindowTest {

    @Test
    fun `credit is consumed and restored`() {
        val window = CreditWindow(2)
        assertTrue(window.tryConsume())
        assertTrue(window.tryConsume())
        assertFalse(window.tryConsume())
        window.restore()
        assertTrue(window.tryConsume())
    }

    @Test
    fun `over-acknowledgement cannot grow the window`() {
        // A head unit that acknowledges more than it owes, through a duplicate
        // or an off-by-one, must not be able to make us overrun a peer that has
        // told us exactly how much it can take.
        val window = CreditWindow(2)
        window.tryConsume()
        window.restore(100)
        assertEquals(2, window.credits)
    }

    @Test
    fun `a non-positive window is refused`() {
        assertThrows<IllegalArgumentException> { CreditWindow(0) }
        assertThrows<IllegalArgumentException> { CreditWindow(-1) }
    }
}
