/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLException

/** Which end of the TLS session this engine plays. */
public enum class TlsRole {
    /**
     * The phone. In AAP the phone is the TLS server: it presents the
     * certificate the head unit inspects, and it is the phone's identity the
     * whole trust question turns on.
     */
    SERVER,

    /** The head unit, which dials the TLS session. Used by the emulator. */
    CLIENT,
}

/**
 * TLS for a link that has no TLS-shaped socket underneath it.
 *
 * AAP does not run over a TLS socket. It carries TLS records as the payload of
 * ordinary control messages, and once the handshake completes it marks
 * per-message payloads as ciphertext with a frame flag. So the TLS state
 * machine has to be driven by hand: bytes in, bytes out, with the caller
 * responsible for putting them in messages.
 *
 * That is exactly what [SSLEngine] is for, and this class is the adapter that
 * makes it usable on byte arrays instead of the pair of buffers the raw API
 * insists on.
 */
public class AapTlsEngine(
    private val role: TlsRole,
    private val credentials: CredentialProvider,
    private val protocols: List<String> = DEFAULT_PROTOCOLS,
    private val cipherSuites: List<String>? = null,
    /**
     * Whether to demand a certificate from the peer.
     *
     * A real head unit asks the phone for one. Whether it then *checks* it is
     * the open question this project exists to answer.
     */
    private val requestPeerCertificate: Boolean = true,
) {

    private val context: SSLContext = SSLContext.getInstance("TLS").apply {
        init(credentials.keyManagers(), credentials.trustManagers(), SecureRandom())
    }

    private val engine: SSLEngine = context.createSSLEngine().apply {
        useClientMode = role == TlsRole.CLIENT
        if (role == TlsRole.SERVER) {
            // "want" rather than "need": a head unit that declines to send a
            // certificate should still get a working session, and we want the
            // diagnostic, not a hard failure.
            wantClientAuth = requestPeerCertificate
            needClientAuth = false
        }
        val supported = supportedProtocols.toSet()
        val selected = protocols.filter { it in supported }
        require(selected.isNotEmpty()) {
            "none of $protocols are supported by this JVM (has $supported)"
        }
        enabledProtocols = selected.toTypedArray()
        cipherSuites?.let { requested ->
            val supportedSuites = supportedCipherSuites.toSet()
            val selectedSuites = requested.filter { it in supportedSuites }
            require(selectedSuites.isNotEmpty()) { "none of $requested are supported by this JVM" }
            enabledCipherSuites = selectedSuites.toTypedArray()
        }
    }

    /** Ciphertext received but not yet consumable as a whole TLS record. */
    private var inboundBacklog = ByteBuffer.allocate(0)

    private val packetBufferSize get() = engine.session.packetBufferSize
    private val applicationBufferSize get() = engine.session.applicationBufferSize

    /** True once the handshake has finished and application data can flow. */
    public var handshakeComplete: Boolean = false
        private set

    /** The failure that ended the handshake, if it failed. */
    public var failure: SSLException? = null
        private set

    /**
     * The peer's certificate chain, once the handshake has progressed far
     * enough to have seen one. Empty when the peer sent none.
     */
    public val peerChain: List<X509Certificate>
        get() = runCatching {
            engine.session.peerCertificates.filterIsInstance<X509Certificate>()
        }.getOrDefault(emptyList())

    /** The negotiated protocol version, once known. */
    public val negotiatedProtocol: String? get() = engine.session.protocol?.takeIf { it != "NONE" }

    /** The negotiated cipher suite, once known. */
    public val negotiatedCipherSuite: String?
        get() = engine.session.cipherSuite?.takeIf { !it.contains("NULL") }

    /**
     * Starts the handshake and returns the first flight of bytes to send, or an
     * empty array when this role waits for the peer to speak first.
     *
     * The head unit opens the TLS session, so on the phone side this produces
     * nothing and the phone simply waits.
     */
    public fun begin(): ByteArray {
        engine.beginHandshake()
        return drainHandshakeOutput()
    }

    /**
     * Feeds one message worth of handshake ciphertext and returns whatever
     * should be sent back.
     *
     * Returns an empty array when the engine has nothing to say yet, which
     * happens whenever a TLS flight spans more than one AAP message.
     *
     * Calling this after [handshakeComplete] is legal and necessary. TLS 1.3
     * keeps talking after the handshake is nominally finished -- a server emits
     * a NewSessionTicket once it has seen the client's Finished -- and those
     * records are numbered in the same sequence as application data. Dropping
     * one desynchronises the record layer and the next real message fails its
     * authentication tag, which presents as a baffling `bad_record_mac` far
     * from the actual mistake. So post-handshake records are consumed here
     * rather than rejected.
     */
    public fun handshake(inbound: ByteArray): ByteArray {
        appendInbound(inbound)
        if (!handshakeComplete) return drainHandshakeOutput()

        // Already finished: anything arriving on the handshake path must be a
        // post-handshake message, which carries no application data.
        val leftover = unwrapBacklog()
        if (leftover.isNotEmpty()) {
            throw SSLException(
                "peer sent ${leftover.size} bytes of application data on the handshake path"
            )
        }
        return drainHandshakeOutput()
    }

    /** Encrypts application data into TLS records. */
    public fun wrap(plaintext: ByteArray): ByteArray {
        check(handshakeComplete) { "wrap before handshake completed" }
        val source = ByteBuffer.wrap(plaintext)
        val out = ByteArrayOutputStream(plaintext.size + 64)
        while (source.hasRemaining()) {
            val target = ByteBuffer.allocate(packetBufferSize)
            val result = engine.wrap(source, target)
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    target.flip()
                    out.write(target.array(), target.arrayOffset(), target.remaining())
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW ->
                    throw SSLException("wrap overflowed a $packetBufferSize byte packet buffer")
                SSLEngineResult.Status.CLOSED -> throw SSLException("TLS session closed during wrap")
                SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                    throw SSLException("unexpected underflow while wrapping application data")
                null -> throw SSLException("null wrap status")
            }
        }
        return out.toByteArray()
    }

    /**
     * Decrypts TLS records into application data.
     *
     * Returns an empty array when the input held only a partial record; the
     * remainder is buffered until the rest arrives.
     */
    public fun unwrap(ciphertext: ByteArray): ByteArray {
        check(handshakeComplete) { "unwrap before handshake completed" }
        appendInbound(ciphertext)
        return unwrapBacklog()
    }

    /** Decrypts whatever whole records the backlog currently holds. */
    private fun unwrapBacklog(): ByteArray {
        val out = ByteArrayOutputStream(inboundBacklog.remaining())
        while (true) {
            if (!inboundBacklog.hasRemaining()) break
            val target = ByteBuffer.allocate(applicationBufferSize)
            val result = engine.unwrap(inboundBacklog, target)
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    target.flip()
                    out.write(target.array(), target.arrayOffset(), target.remaining())
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
                SSLEngineResult.Status.BUFFER_OVERFLOW ->
                    throw SSLException("unwrap overflowed a $applicationBufferSize byte application buffer")
                SSLEngineResult.Status.CLOSED -> throw SSLException("TLS session closed by peer")
                null -> throw SSLException("null unwrap status")
            }
            if (result.bytesProduced() == 0 && result.bytesConsumed() == 0) break
        }
        compactBacklog()
        return out.toByteArray()
    }

    /** Emits a TLS close_notify, so the peer sees a clean shutdown rather than a dropped link. */
    public fun closeOutbound(): ByteArray {
        engine.closeOutbound()
        return runCatching { drainHandshakeOutput() }.getOrDefault(ByteArray(0))
    }

    private fun appendInbound(data: ByteArray) {
        if (data.isEmpty()) return
        val combined = ByteBuffer.allocate(inboundBacklog.remaining() + data.size)
        combined.put(inboundBacklog)
        combined.put(data)
        combined.flip()
        inboundBacklog = combined
    }

    private fun compactBacklog() {
        inboundBacklog = if (inboundBacklog.hasRemaining()) {
            ByteBuffer.wrap(
                ByteArray(inboundBacklog.remaining()).also { inboundBacklog.get(it) }
            )
        } else {
            ByteBuffer.allocate(0)
        }
    }

    /**
     * Runs the handshake state machine until it needs more input from the peer,
     * collecting everything the engine wants to send.
     */
    private fun drainHandshakeOutput(): ByteArray {
        val out = ByteArrayOutputStream(packetBufferSize)
        try {
            loop@ while (true) {
                when (engine.handshakeStatus) {
                    HandshakeStatus.NEED_WRAP -> {
                        val target = ByteBuffer.allocate(packetBufferSize)
                        val result = engine.wrap(EMPTY, target)
                        target.flip()
                        out.write(target.array(), target.arrayOffset(), target.remaining())
                        if (result.status == SSLEngineResult.Status.CLOSED) break@loop
                    }

                    HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                        if (!inboundBacklog.hasRemaining()) break@loop
                        val target = ByteBuffer.allocate(applicationBufferSize)
                        val before = inboundBacklog.remaining()
                        val result = engine.unwrap(inboundBacklog, target)
                        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break@loop
                        if (result.status == SSLEngineResult.Status.CLOSED) break@loop
                        // No forward progress and nothing consumed: stop rather than spin.
                        if (inboundBacklog.remaining() == before && result.bytesProduced() == 0) break@loop
                    }

                    HandshakeStatus.NEED_TASK -> {
                        // Certificate path validation and key agreement are handed
                        // out as tasks. Running them inline keeps the engine
                        // single-threaded, which matters because a session is
                        // driven from one message-dispatch loop.
                        while (true) {
                            val task = engine.delegatedTask ?: break
                            task.run()
                        }
                    }

                    HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> {
                        handshakeComplete = true
                        break@loop
                    }

                    null -> break@loop
                }
            }
        } catch (e: SSLException) {
            failure = e
            throw e
        }
        compactBacklog()
        return out.toByteArray()
    }

    public companion object {
        /**
         * TLS 1.2 first.
         *
         * Head units in the field are old: the target of this project is a 2017
         * MIB2 unit whose stack predates TLS 1.3 by years. TLS 1.3 is offered
         * because there is no reason to refuse a modern peer, but the ordering
         * and the fallback matter more here than on the open internet.
         */
        public val DEFAULT_PROTOCOLS: List<String> = listOf("TLSv1.2", "TLSv1.3")

        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
