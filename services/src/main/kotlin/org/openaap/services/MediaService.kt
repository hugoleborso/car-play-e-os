/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.services

import org.openaap.core.AapLink
import org.openaap.core.IncomingMessage
import org.openaap.core.ResolvedChannel
import org.openaap.core.ServiceHandler
import org.openaap.core.ServiceKind
import org.openaap.protocol.MediaStamp
import org.openaap.protocol.Messages
import org.openaap.protocol.proto.MediaAck
import org.openaap.protocol.proto.MediaReadiness
import org.openaap.protocol.proto.MediaSetupRequest
import org.openaap.protocol.proto.MediaSetupResponse
import org.openaap.protocol.proto.MediaStartIndication
import org.openaap.protocol.proto.MediaStopIndication
import org.openaap.protocol.proto.StreamKind
import org.openaap.protocol.proto.VideoFocusIndication
import org.openaap.protocol.proto.VideoFocusState

/** Where encoded media comes from, and where playback state goes. */
public interface MediaProducer {
    /**
     * Called once the head unit is ready to receive. The producer starts
     * generating frames and hands each to [sink].
     */
    public fun start(format: Int, sink: (frameMicros: Long, payload: ByteArray) -> Unit)

    /** Called when the head unit revokes focus or the session ends. */
    public fun stop()
}

/**
 * Drives one media channel: video, or one of the audio outputs.
 *
 * The negotiation has a shape worth stating, because the video case has an
 * extra step that is easy to miss and produces a channel that opens cleanly and
 * then stays black:
 *
 * ```
 * phone -> setup request
 * head unit -> setup response, carrying the credit window
 * head unit -> video focus indication      (video only)
 * phone -> start indication
 * phone -> media, media, media
 * head unit -> acknowledgement, returning credit
 * ```
 *
 * Audio channels have no focus step and start immediately after their setup
 * response. Video must wait: sending the start indication early leaves the head
 * unit with a stream it is not displaying.
 */
public class MediaService(
    override val channel: ResolvedChannel,
    private val producer: MediaProducer,
    private val listener: Listener = Listener.NONE,
) : ServiceHandler {

    public interface Listener {
        public fun onReady(channel: ResolvedChannel, creditWindow: Int) {}
        public fun onStarted(channel: ResolvedChannel, format: Int) {}
        public fun onStopped(channel: ResolvedChannel, reason: String) {}
        public fun onCreditExhausted(channel: ResolvedChannel, droppedFrames: Long) {}

        public companion object {
            public val NONE: Listener = object : Listener {}
        }
    }

    private val isVideo = channel.kind == ServiceKind.VIDEO

    private var session: Int = 0
    private var format: Int = 0
    private var started = false
    private var flow: CreditWindow? = null
    private var droppedFrames = 0L

    /** True once media is flowing. */
    public val streaming: Boolean get() = started

    override fun onOpened(link: AapLink) {
        // The single field of the setup request is read differently across the
        // public record -- as an index into the advertised format list, or as
        // the stream kind. The value known to work against real hardware is the
        // stream kind, and in the usual case of one advertised format the two
        // readings coincide anyway.
        val selector = if (isVideo) StreamKind.STREAM_KIND_VIDEO.number else StreamKind.STREAM_KIND_AUDIO.number
        link.send(
            channel.id,
            Messages.MEDIA_SETUP_REQUEST,
            MediaSetupRequest.newBuilder().setSelector(selector).build().toByteArray(),
        )
    }

    override fun onMessage(link: AapLink, message: IncomingMessage) {
        when (message.messageId) {
            Messages.MEDIA_SETUP_RESPONSE -> handleSetupResponse(link, message)
            Messages.VIDEO_FOCUS_INDICATION -> handleVideoFocus(link, message)
            Messages.MEDIA_ACK -> handleAck(message)
            Messages.MEDIA_STOP_INDICATION -> stop(link, "head unit stopped the channel")
            else -> Unit
        }
    }

    private fun handleSetupResponse(link: AapLink, message: IncomingMessage) {
        val response = MediaSetupResponse.parseFrom(message.body)
        if (response.hasReadiness() && response.readiness != MediaReadiness.MEDIA_READINESS_READY) {
            listener.onStopped(channel, "head unit refused setup: ${response.readiness}")
            return
        }

        // A head unit that advertises no window is asking for one message at a
        // time. Treating "absent" as "unlimited" is the mistake that makes a
        // channel stall a few seconds in.
        val credits = if (response.hasMaxUnacked() && response.maxUnacked > 0) response.maxUnacked else 1
        flow = CreditWindow(credits)
        format = response.acceptedFormatsList.firstOrNull() ?: 0
        listener.onReady(channel, credits)

        if (isVideo) {
            // Prompt the head unit rather than waiting indefinitely; some grant
            // focus spontaneously, some only when asked.
            link.send(
                channel.id,
                Messages.VIDEO_FOCUS_REQUEST,
                org.openaap.protocol.proto.VideoFocusRequest.newBuilder()
                    .setDisplayIndex(0)
                    .setRequested(VideoFocusState.VIDEO_FOCUS_HELD)
                    .build()
                    .toByteArray(),
            )
        } else {
            beginStreaming(link)
        }
    }

    private fun handleVideoFocus(link: AapLink, message: IncomingMessage) {
        val indication = VideoFocusIndication.parseFrom(message.body)
        when (indication.state) {
            VideoFocusState.VIDEO_FOCUS_HELD -> if (!started) beginStreaming(link)
            else -> stop(link, "head unit revoked video focus")
        }
    }

    private fun beginStreaming(link: AapLink) {
        if (started) return
        started = true
        link.send(
            channel.id,
            Messages.MEDIA_START_INDICATION,
            MediaStartIndication.newBuilder().setSession(session).setFormat(format).build().toByteArray(),
        )
        listener.onStarted(channel, format)
        producer.start(format) { micros, payload -> emit(link, micros, payload) }
    }

    private fun emit(link: AapLink, micros: Long, payload: ByteArray) {
        val window = flow ?: return
        if (!window.tryConsume()) {
            // Dropping is correct for live media. A frame that has waited for
            // credit is already stale, and queueing it only pushes the whole
            // stream further behind the car's clock -- latency that never
            // recovers, rather than one missing frame.
            droppedFrames++
            listener.onCreditExhausted(channel, droppedFrames)
            return
        }
        link.send(channel.id, Messages.MEDIA_WITH_STAMP, MediaStamp.write(micros) + payload)
    }

    private fun handleAck(message: IncomingMessage) {
        val ack = MediaAck.parseFrom(message.body)
        val returned = if (ack.hasCount() && ack.count > 0) ack.count else 1
        flow?.restore(returned)
    }

    private fun stop(link: AapLink, reason: String) {
        if (!started) return
        started = false
        producer.stop()
        runCatching {
            link.send(
                channel.id,
                Messages.MEDIA_STOP_INDICATION,
                MediaStopIndication.getDefaultInstance().toByteArray(),
            )
        }
        listener.onStopped(channel, reason)
    }

    override fun onClosed() {
        if (started) {
            started = false
            producer.stop()
        }
    }

    /** Frames dropped for want of credit, since the channel opened. */
    public val dropped: Long get() = droppedFrames
}

/**
 * A credit window.
 *
 * Head units commonly advertise a window of one, meaning strictly one media
 * message may be outstanding. Not honouring it makes real hardware stall the
 * channel or drop frames on its own terms, which is far harder to diagnose than
 * dropping them deliberately here.
 */
public class CreditWindow(public val capacity: Int) {

    init {
        require(capacity > 0) { "credit window must be positive, got $capacity" }
    }

    private var available = capacity

    /** Credits currently free. */
    public val credits: Int get() = synchronized(this) { available }

    /** Takes one credit, or returns false when none is available. */
    public fun tryConsume(): Boolean = synchronized(this) {
        if (available <= 0) return false
        available--
        true
    }

    /**
     * Returns credit on acknowledgement.
     *
     * Clamped to the window: a head unit that acknowledges more than it owes --
     * whether through a duplicated message or an off-by-one -- must not be able
     * to grow our window past what it asked for, or we would start overrunning
     * a peer that has told us exactly how much it can take.
     */
    public fun restore(count: Int = 1): Unit = synchronized(this) {
        require(count > 0) { "cannot restore $count credits" }
        available = minOf(capacity, available + count)
    }
}
