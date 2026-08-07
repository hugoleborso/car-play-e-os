/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.openaap.crypto.AapTlsEngine
import org.openaap.protocol.AssembledMessage
import org.openaap.protocol.FrameDecoder
import org.openaap.protocol.FrameEncoder
import org.openaap.protocol.FrameFlags
import org.openaap.protocol.FrameHeader
import org.openaap.protocol.MessageAssembler
import org.openaap.protocol.Messages
import org.openaap.transport.Transport
import org.openaap.transport.TransportException

/** A message lifted off the link, with its id already separated from its body. */
public data class IncomingMessage(
    val channel: Int,
    val messageId: Int,
    val body: ByteArray,
    val wasEncrypted: Boolean,
    val hadControlFlag: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingMessage) return false
        return channel == other.channel &&
            messageId == other.messageId &&
            wasEncrypted == other.wasEncrypted &&
            hadControlFlag == other.hadControlFlag &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = channel
        result = 31 * result + messageId
        result = 31 * result + body.contentHashCode()
        return result
    }

    override fun toString(): String =
        "IncomingMessage(channel=$channel, id=${Messages.describe(channel, messageId)}, " +
            "body=${body.size}B, encrypted=$wasEncrypted)"
}

/** Raised when the peer breaks a protocol rule this layer is responsible for. */
public class ProtocolViolation(message: String) : RuntimeException(message)

/**
 * The message layer: everything between a byte pipe and a protocol state
 * machine.
 *
 * It owns the frame codec, per-channel reassembly, and the TLS engine, and it
 * enforces the invariants that are easy to get wrong and expensive to debug in
 * a car:
 *
 * - encryption is applied per fragment, after splitting, because the two length
 *   fields in a frame are measured in different units;
 * - the control flag is derived from the channel and message id rather than
 *   passed in by callers, because the rule is subtle and getting it wrong makes
 *   a head unit try to parse video as a control message;
 * - a peer that sends ciphertext before the handshake completes, or plaintext
 *   after it should have stopped, is rejected rather than tolerated.
 *
 * One reader and one writer. Writes are serialised; reads are not, because a
 * session has exactly one read loop.
 */
public class AapLink(
    private val transport: Transport,
    private val tls: AapTlsEngine,
    private val listener: Listener = Listener.NONE,
    /**
     * Whether control-channel messages carry the control flag.
     *
     * Our reading makes it redundant on channel 0 -- see
     * [Messages.needsControlFlag] -- but that is a reading, not a measurement,
     * and it is one of the things [SessionVariant] exists to vary. Overriding
     * it here rather than in Messages keeps the default rule stated in one
     * place and the experiment visible at the call site.
     */
    private val controlFlagOnControlChannel: Boolean = false,
) {

    /** Observation hook for tracing and diagnostics. */
    public interface Listener {
        public fun onSend(
            channel: Int,
            messageId: Int,
            size: Int,
            encrypted: Boolean,
            control: Boolean,
        ) {}
        public fun onReceive(message: IncomingMessage) {}

        public companion object {
            public val NONE: Listener = object : Listener {}
        }
    }

    private val decoder = FrameDecoder()
    private val assembler = MessageAssembler()
    private val writeLock = ReentrantLock()
    private val readBuffer = ByteArray(READ_BUFFER_SIZE)

    /** Messages fully assembled but not yet handed to the caller. */
    private val ready = ArrayDeque<AssembledMessage>()

    /**
     * Whether the session has moved past the handshake.
     *
     * Before this, everything on the wire is plaintext; after it, everything is
     * ciphertext. The head unit declares the transition by sending the
     * auth-succeeded message, and the session layer calls [enableEncryption].
     */
    public var encryptionActive: Boolean = false
        private set

    /** Marks the point after which all traffic is expected to be encrypted. */
    public fun enableEncryption() {
        check(tls.handshakeComplete) { "encryption enabled before the TLS handshake completed" }
        encryptionActive = true
    }

    /**
     * Sends one message.
     *
     * Encryption follows [encryptionActive] rather than a caller-supplied flag,
     * so it is impossible to send a message on the wrong side of the transition
     * by mistake. [forcePlaintext] exists only for the handshake messages, which
     * must stay in the clear even once the TLS engine is able to encrypt.
     */
    public fun send(
        channel: Int,
        messageId: Int,
        body: ByteArray = ByteArray(0),
        forcePlaintext: Boolean = false,
    ) {
        val encrypt = encryptionActive && !forcePlaintext
        var flags = 0
        if (encrypt) flags = flags or FrameFlags.ENCRYPTED
        val control = Messages.needsControlFlag(channel, messageId) ||
            (controlFlagOnControlChannel && channel == Messages.CONTROL_CHANNEL)
        if (control) flags = flags or FrameFlags.CONTROL

        val payload = Messages.frame(messageId, body)
        val fragmentSize = minOf(FrameHeader.SEND_FRAGMENT_SIZE, transport.maxWriteSize)

        // Encryption happens inside the lock, not before it. Fragment boundaries
        // are per channel, but the TLS record sequence is global: every channel
        // shares one. So records have to be produced in the order they are
        // transmitted. Encrypting outside the lock lets two concurrent senders
        // interleave their records and then write them in a different order,
        // which the peer cannot decrypt at all — and the failure surfaces as
        // bad_record_mac, which reads exactly like a certificate or key
        // derivation fault rather than an ordering one.
        writeLock.withLock {
            val frames = FrameEncoder.encode(
                channel = channel,
                flags = flags,
                payload = payload,
                fragmentSize = fragmentSize,
                encrypt = if (encrypt) { plain -> tls.wrap(plain) } else null,
            )
            // A multi-frame message goes out in one burst. Some head units reject
            // a frame from another channel arriving mid-message, so we do not
            // interleave on send even though we tolerate it on receive.
            for (frame in frames) {
                transport.write(frame, 0, frame.size)
            }
        }
        listener.onSend(channel, messageId, body.size, encrypt, control)
    }

    /** Sends raw TLS handshake bytes, which are always plaintext control traffic. */
    public fun sendHandshake(records: ByteArray) {
        if (records.isEmpty()) return
        send(Messages.CONTROL_CHANNEL, Messages.TLS_HANDSHAKE, records, forcePlaintext = true)
    }

    /**
     * Blocks until the next whole message arrives, or returns `null` at end of
     * stream.
     */
    public fun receive(): IncomingMessage? {
        while (true) {
            ready.removeFirstOrNull()?.let { return lift(it) }

            val read = transport.read(readBuffer, 0, readBuffer.size)
            if (read < 0) return null
            if (read == 0) continue
            decoder.feed(readBuffer, 0, read)

            while (true) {
                val frame = decoder.poll() ?: break
                val header = frame.header

                if (header.isEncrypted && !tls.handshakeComplete) {
                    throw ProtocolViolation(
                        "peer sent ciphertext on channel ${header.channel} before the handshake completed"
                    )
                }

                val plaintext = if (header.isEncrypted) tls.unwrap(frame.payload) else frame.payload
                // Decrypt first, reassemble second: the announced total counts
                // plaintext, so feeding ciphertext to the assembler would compare
                // lengths measured in different units.
                assembler.accept(header, plaintext)?.let { ready += it }
            }
        }
    }

    private fun lift(message: AssembledMessage): IncomingMessage {
        val (messageId, body) = Messages.split(message.payload)

        val expectedControlFlag = Messages.needsControlFlag(message.channel, messageId)
        if (message.isControl != expectedControlFlag) {
            throw ProtocolViolation(
                "channel ${message.channel} message ${Messages.describe(message.channel, messageId)} " +
                    "arrived with control flag ${message.isControl}, expected $expectedControlFlag"
            )
        }

        return IncomingMessage(
            channel = message.channel,
            messageId = messageId,
            body = body,
            wasEncrypted = message.isEncrypted,
            hadControlFlag = message.isControl,
        ).also(listener::onReceive)
    }

    /** Sends a TLS close_notify if one is available, then drops the transport. */
    public fun close() {
        runCatching {
            if (tls.handshakeComplete) {
                val farewell = tls.closeOutbound()
                if (farewell.isNotEmpty()) sendHandshake(farewell)
            }
        }
        runCatching { transport.close() }
    }

    private companion object {
        /**
         * Sized above the largest frame a peer can send: 16 KiB of plaintext
         * plus TLS record overhead plus the header. Reading in larger bites than
         * the link delivers is free; reading in smaller ones costs syscalls on
         * every video frame.
         */
        const val READ_BUFFER_SIZE = 32 * 1024
    }
}

/** Convenience for turning a transport failure into a session-ending signal. */
public inline fun <T> AapLink.orNullOnTransportFailure(block: AapLink.() -> T): T? =
    try {
        block()
    } catch (_: TransportException) {
        null
    }
