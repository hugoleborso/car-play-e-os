/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import org.openaap.crypto.AapTlsEngine
import org.openaap.protocol.AuthVerdict
import org.openaap.protocol.Messages
import org.openaap.protocol.VersionExchange
import org.openaap.protocol.proto.AuthSucceeded
import org.openaap.protocol.proto.ChannelAdvert
import org.openaap.protocol.proto.ChannelOpenRequest
import org.openaap.protocol.proto.ChannelOpenResponse
import org.openaap.protocol.proto.DiscoveryRequest
import org.openaap.protocol.proto.DiscoveryResponse
import org.openaap.protocol.proto.PingRequest
import org.openaap.protocol.proto.PingResponse
import org.openaap.protocol.proto.ResultCode
import org.openaap.protocol.proto.TeardownReason
import org.openaap.protocol.proto.TeardownRequest
import org.openaap.protocol.proto.TeardownResponse

/** How the phone describes itself to the head unit. */
public data class PhoneIdentity(
    val model: String,
    val maker: String,
    /**
     * The highest protocol version we implement.
     *
     * The head unit speaks first and we answer. Echoing its minor version,
     * capped here, is safer than announcing a fixed number: head units in the
     * field advertise anything from 1.1 to 1.6, and answering above what one
     * asked for has been observed to work but has no reason to keep working.
     */
    val protocolMajor: Int = 1,
    val protocolMaxMinor: Int = 6,
)

/** What a channel is for, resolved from the head unit's advert. */
public enum class ServiceKind {
    SENSORS,
    VIDEO,
    MEDIA_AUDIO,
    SPEECH_AUDIO,
    SYSTEM_AUDIO,
    MICROPHONE,
    INPUT,
    BLUETOOTH,
    NAVIGATION,
    PHONE_STATUS,
    NOTIFICATIONS,
    VENDOR,
    UNKNOWN,
}

/** A channel the head unit offered, after we have worked out what it carries. */
public data class ResolvedChannel(
    val id: Int,
    val kind: ServiceKind,
    val advert: ChannelAdvert,
)

/** Handles the messages of one service once its channel is open. */
public interface ServiceHandler {
    /** Which channel this handler was bound to. */
    public val channel: ResolvedChannel

    /** Called once the channel-open response has arrived with a success result. */
    public fun onOpened(link: AapLink) {}

    /** Called for every message on this channel that the session does not own. */
    public fun onMessage(link: AapLink, message: IncomingMessage)

    /** Called when the session ends, successfully or otherwise. */
    public fun onClosed() {}
}

/** Builds handlers for the channels a session decides to open. */
public fun interface ServiceHandlerFactory {
    /** Returns a handler, or `null` to decline the channel and leave it closed. */
    public fun create(channel: ResolvedChannel): ServiceHandler?
}

/** Observation points for the session lifecycle. */
public interface SessionListener {
    public fun onVersionAgreed(major: Int, minor: Int) {}
    public fun onHandshakeComplete(protocol: String?, cipherSuite: String?) {}
    public fun onAuthenticated() {}

    /**
     * The phone has chosen not to open the discovery exchange and is waiting to
     * be led. Only a listen-only [SessionVariant] produces this.
     */
    public fun onListening() {}
    public fun onDiscovered(response: DiscoveryResponse, channels: List<ResolvedChannel>) {}
    public fun onChannelOpened(channel: ResolvedChannel) {}
    public fun onChannelRefused(channel: ResolvedChannel, result: ResultCode) {}
    public fun onEnded(reason: String, cause: Throwable? = null) {}

    public companion object {
        public val NONE: SessionListener = object : SessionListener {}
    }
}

/**
 * The phone half of an Android Auto session.
 *
 * The head unit drives the opening: it asks for our version, opens the TLS
 * session, and declares the result. Only once it has said the session is
 * authenticated may the phone ask what the car can do — and from that point the
 * phone leads, opening channels and streaming media.
 *
 * The state machine is deliberately strict about ordering. A head unit that
 * skips a step is a head unit we do not understand, and continuing anyway
 * produces failures several steps later that are very hard to attribute. Every
 * rejection here names the rule that was broken.
 */
public class PhoneSession(
    private val link: AapLink,
    private val tls: AapTlsEngine,
    private val identity: PhoneIdentity,
    private val handlerFactory: ServiceHandlerFactory,
    private val listener: SessionListener = SessionListener.NONE,
    /**
     * Which reading of the post-authentication sequence to follow.
     *
     * Defaults to the one this implementation believes is right. It is a
     * parameter because the first real projection attempt died on the frame
     * immediately after authentication, and the causes cannot be separated by
     * argument -- see [SessionVariant].
     */
    private val variant: SessionVariant = SessionVariant(id = "default", varies = "the default reading"),
) {

    public enum class State {
        AWAITING_VERSION,
        HANDSHAKING,
        AWAITING_AUTH,
        DISCOVERING,
        OPENING_CHANNELS,
        RUNNING,
        ENDED,
    }

    public var state: State = State.AWAITING_VERSION
        private set

    /** Channels the head unit advertised, keyed by the id it assigned them. */
    public var channels: Map<Int, ResolvedChannel> = emptyMap()
        private set

    /** The head unit's self-description, once discovery has completed. */
    public var headUnit: DiscoveryResponse? = null
        private set

    private val handlers = HashMap<Int, ServiceHandler>()
    private val pendingOpens = ArrayDeque<ResolvedChannel>()

    /**
     * Runs the session until the link closes or a rule is broken.
     *
     * Blocking, and intended to own its thread.
     */
    public fun run() {
        try {
            while (state != State.ENDED) {
                val message = link.receive()
                if (message == null) {
                    end("head unit closed the link")
                    return
                }
                dispatch(message)
            }
        } catch (e: ProtocolViolation) {
            end("protocol violation: ${e.message}", e)
            throw e
        } catch (e: Throwable) {
            end("session failed: ${e.message}", e)
            throw e
        } finally {
            handlers.values.forEach { runCatching { it.onClosed() } }
        }
    }

    private fun dispatch(message: IncomingMessage) {
        if (message.channel != Messages.CONTROL_CHANNEL && !message.hadControlFlag) {
            routeToService(message)
            return
        }
        when (message.messageId) {
            Messages.VERSION_REQUEST -> handleVersionRequest(message)
            Messages.TLS_HANDSHAKE -> handleHandshake(message)
            Messages.AUTH_SUCCEEDED -> handleAuthSucceeded(message)
            Messages.DISCOVERY_RESPONSE -> handleDiscoveryResponse(message)
            Messages.CHANNEL_OPEN_RESPONSE -> handleChannelOpenResponse(message)
            Messages.PING_REQUEST -> handlePing(message)
            Messages.TEARDOWN_REQUEST -> handleTeardown(message)
            Messages.TEARDOWN_RESPONSE -> end("head unit acknowledged teardown")
            else -> routeToService(message)
        }
    }

    // --- Handshake ---------------------------------------------------------

    private fun handleVersionRequest(message: IncomingMessage) {
        require(state == State.AWAITING_VERSION) {
            "version request arrived in state $state"
        }
        if (message.wasEncrypted) {
            throw ProtocolViolation("version request arrived encrypted, before any key exists")
        }

        val request = VersionExchange.parseRequest(message.body)
        if (request.major != identity.protocolMajor) {
            link.send(
                Messages.CONTROL_CHANNEL,
                Messages.VERSION_RESPONSE,
                VersionExchange.encodeResponse(
                    VersionExchange.Response(
                        identity.protocolMajor,
                        identity.protocolMaxMinor,
                        VersionExchange.STATUS_MISMATCH,
                    )
                ),
                forcePlaintext = true,
            )
            throw ProtocolViolation(
                "head unit speaks protocol major ${request.major}, we implement ${identity.protocolMajor}"
            )
        }

        val minor = minOf(request.minor, identity.protocolMaxMinor)
        link.send(
            Messages.CONTROL_CHANNEL,
            Messages.VERSION_RESPONSE,
            VersionExchange.encodeResponse(
                VersionExchange.Response(identity.protocolMajor, minor, VersionExchange.STATUS_MATCH)
            ),
            forcePlaintext = true,
        )
        listener.onVersionAgreed(identity.protocolMajor, minor)
        state = State.HANDSHAKING

        // The head unit opens the TLS session; as the TLS server we produce
        // nothing here and simply wait for its first flight.
        tls.begin()
    }

    private fun handleHandshake(message: IncomingMessage) {
        if (state != State.HANDSHAKING && state != State.AWAITING_AUTH) {
            throw ProtocolViolation("TLS handshake records arrived in state $state")
        }
        val reply = tls.handshake(message.body)
        link.sendHandshake(reply)

        if (tls.handshakeComplete && state == State.HANDSHAKING) {
            state = State.AWAITING_AUTH
            listener.onHandshakeComplete(tls.negotiatedProtocol, tls.negotiatedCipherSuite)
        }
    }

    private fun handleAuthSucceeded(message: IncomingMessage) {
        if (state != State.AWAITING_AUTH) {
            throw ProtocolViolation("auth result arrived in state $state")
        }
        // Raw varint, not the generated enum. See AuthVerdict for why: a proto2
        // enum hides any value outside its own members, and treating that as
        // "no objection" turned a rejection into an acceptance for the whole
        // first phase of this project.
        val status = AuthVerdict.statusOf(message.body)
        if (status != AuthVerdict.OK) {
            throw ProtocolViolation(
                "head unit did not accept the session: ${AuthVerdict.describe(status)}"
            )
        }

        // This message is the transition. Everything before it was plaintext;
        // everything after it is ciphertext -- which is the reading the
        // plaintext variant exists to question.
        if (variant.encryptImmediately) link.enableEncryption()
        listener.onAuthenticated()

        state = State.DISCOVERING

        if (variant.discovery == SessionVariant.Discovery.NONE) {
            // Deliberately silent. The session stays in DISCOVERING and simply
            // reads, so the transcript records whether the head unit leads the
            // exchange itself. That is a measurement of the one step whose
            // direction the public record disagrees about, and it is worth more
            // than another guess at the request's contents.
            listener.onListening()
            return
        }

        if (variant.quietMillisBeforeDiscovery > 0) {
            // Runs on the session thread, which is the read loop. Sleeping here
            // stops us reading too, which is the point: the variant is testing
            // a phone that says nothing for a moment, not one that is merely
            // slow to speak while still draining the link.
            runCatching { Thread.sleep(variant.quietMillisBeforeDiscovery) }
        }

        link.send(
            Messages.CONTROL_CHANNEL,
            Messages.DISCOVERY_REQUEST,
            DiscoveryRequest.newBuilder()
                .setPhoneModel(identity.model)
                .setPhoneMaker(identity.maker)
                .build()
                .toByteArray(),
        )
    }

    // --- Discovery and channel setup ---------------------------------------

    private fun handleDiscoveryResponse(message: IncomingMessage) {
        if (state != State.DISCOVERING) {
            throw ProtocolViolation("discovery response arrived in state $state")
        }
        val response = DiscoveryResponse.parseFrom(message.body)
        headUnit = response

        val resolved = response.channelsList.map { advert ->
            ResolvedChannel(advert.channelId, classify(advert), advert)
        }
        channels = resolved.associateBy { it.id }
        listener.onDiscovered(response, resolved)

        for (channel in resolved) {
            val handler = handlerFactory.create(channel) ?: continue
            handlers[channel.id] = handler
            pendingOpens += channel
        }

        if (pendingOpens.isEmpty()) {
            state = State.RUNNING
            return
        }
        state = State.OPENING_CHANNELS
        requestNextChannel()
    }

    private fun requestNextChannel() {
        val next = pendingOpens.firstOrNull() ?: run {
            state = State.RUNNING
            return
        }
        link.send(
            next.id,
            Messages.CHANNEL_OPEN_REQUEST,
            ChannelOpenRequest.newBuilder()
                .setPriority(0)
                .setChannelId(next.id)
                .build()
                .toByteArray(),
        )
    }

    private fun handleChannelOpenResponse(message: IncomingMessage) {
        if (state != State.OPENING_CHANNELS) {
            throw ProtocolViolation("channel open response arrived in state $state")
        }
        val expected = pendingOpens.removeFirstOrNull()
            ?: throw ProtocolViolation("channel open response with nothing outstanding")
        if (message.channel != expected.id) {
            throw ProtocolViolation(
                "channel open response arrived on channel ${message.channel}, expected ${expected.id}"
            )
        }

        val response = ChannelOpenResponse.parseFrom(message.body)
        val result = if (response.hasResult()) response.result else ResultCode.RESULT_OK
        if (result == ResultCode.RESULT_OK) {
            listener.onChannelOpened(expected)
            handlers[expected.id]?.onOpened(link)
        } else {
            // A refused channel is not fatal. A head unit may decline the
            // microphone or Bluetooth and still project perfectly well.
            listener.onChannelRefused(expected, result)
            handlers.remove(expected.id)?.onClosed()
        }
        requestNextChannel()
    }

    // --- Steady state ------------------------------------------------------

    private fun handlePing(message: IncomingMessage) {
        // Implementations disagree on whether ping travels encrypted, and both
        // forms occur in the field, so the request is accepted either way. The
        // response follows the session's current state like everything else.
        val stamp = runCatching { PingRequest.parseFrom(message.body) }
            .getOrNull()
            ?.takeIf { it.hasStamp() }
            ?.stamp
            ?: 0L
        link.send(
            Messages.CONTROL_CHANNEL,
            Messages.PING_RESPONSE,
            PingResponse.newBuilder().setStamp(stamp).build().toByteArray(),
        )
    }

    private fun handleTeardown(message: IncomingMessage) {
        val request = runCatching { TeardownRequest.parseFrom(message.body) }.getOrNull()
        link.send(
            Messages.CONTROL_CHANNEL,
            Messages.TEARDOWN_RESPONSE,
            TeardownResponse.getDefaultInstance().toByteArray(),
        )
        end("head unit requested teardown (${request?.reason ?: TeardownReason.TEARDOWN_UNSPECIFIED})")
    }

    private fun routeToService(message: IncomingMessage) {
        val handler = handlers[message.channel]
        if (handler == null) {
            // Not fatal: head units send traffic on channels we declined, and
            // dropping it is the correct response.
            return
        }
        handler.onMessage(link, message)
    }

    /** Asks the head unit to end the session cleanly. */
    public fun requestTeardown(reason: TeardownReason = TeardownReason.TEARDOWN_USER_QUIT) {
        if (state == State.ENDED) return
        runCatching {
            link.send(
                Messages.CONTROL_CHANNEL,
                Messages.TEARDOWN_REQUEST,
                TeardownRequest.newBuilder().setReason(reason).build().toByteArray(),
            )
        }
    }

    private fun end(reason: String, cause: Throwable? = null) {
        if (state == State.ENDED) return
        state = State.ENDED
        listener.onEnded(reason, cause)
    }

    private fun classify(advert: ChannelAdvert): ServiceKind = when {
        advert.hasSensors() -> ServiceKind.SENSORS
        advert.hasInput() -> ServiceKind.INPUT
        advert.hasMediaSource() -> ServiceKind.MICROPHONE
        advert.hasBluetooth() -> ServiceKind.BLUETOOTH
        advert.hasNavigation() -> ServiceKind.NAVIGATION
        advert.hasPhoneStatus() -> ServiceKind.PHONE_STATUS
        advert.hasNotifications() -> ServiceKind.NOTIFICATIONS
        advert.hasVendor() -> ServiceKind.VENDOR
        advert.hasMediaSink() -> {
            val sink = advert.mediaSink
            when {
                sink.hasStream() && sink.stream == org.openaap.protocol.proto.StreamKind.STREAM_KIND_VIDEO ->
                    ServiceKind.VIDEO

                else -> when (sink.purpose) {
                    org.openaap.protocol.proto.AudioPurpose.AUDIO_PURPOSE_MEDIA -> ServiceKind.MEDIA_AUDIO
                    org.openaap.protocol.proto.AudioPurpose.AUDIO_PURPOSE_SPEECH -> ServiceKind.SPEECH_AUDIO
                    org.openaap.protocol.proto.AudioPurpose.AUDIO_PURPOSE_SYSTEM -> ServiceKind.SYSTEM_AUDIO
                    else -> ServiceKind.UNKNOWN
                }
            }
        }

        else -> ServiceKind.UNKNOWN
    }
}
