/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import org.openaap.crypto.AapTlsEngine
import org.openaap.crypto.CredentialProvider
import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki
import org.openaap.crypto.TlsRole
import org.openaap.protocol.Messages
import org.openaap.protocol.VersionExchange
import org.openaap.transport.Transport

/**
 * A head unit that does exactly what the test tells it to, including things no
 * correct head unit would do.
 *
 * The status matrix's entire output is a number a car sends and a claim about
 * what makes it change. Both halves can be wrong in ways that look identical
 * from a car park: a decoder that reports the same value whatever arrives, or a
 * provocation that never reached the wire. Neither is detectable from the
 * result — only from a peer whose answer is known in advance and a record of
 * what the phone actually sent.
 *
 * It models one behaviour of the real hardware deliberately: **it hangs up
 * shortly after the verdict rather than closing politely.** The measured MIB2
 * drops USB accessory mode a couple of seconds after refusing, and a probe that
 * only ever met a peer which said goodbye would be untested against the ending
 * it will actually get.
 */
internal class ScriptedHeadUnit(
    transport: Transport,
    /**
     * The verdict body to send once TLS settles, or `null` to send none.
     *
     * Raw bytes rather than a built message, because the values worth
     * reproducing are ones our schema cannot express — which is what makes them
     * worth reproducing.
     */
    private val verdict: ByteArray?,
    private val offers: VersionExchange.Request = VersionExchange.Request(1, 6),
    /** How long after the verdict the cable goes away. */
    private val hangUpAfterMillis: Long = 300,
    credentials: CredentialProvider = StaticCredentialProvider.of(
        "scripted head unit",
        TestPki.selfSigned("scripted head unit"),
    ),
) {

    private val tls = AapTlsEngine(TlsRole.CLIENT, credentials)
    private val link = AapLink(transport, tls)

    /** Every version response body received, exactly as it arrived. */
    val versionResponses: MutableList<ByteArray> = mutableListOf()

    @Volatile
    var handshakeCompleted: Boolean = false
        private set

    /** Set when the phone's TLS engine gave up and told us so. */
    @Volatile
    var sawHandshakeFailure: Boolean = false
        private set

    /** Message ids the phone sent after being given a verdict. Nobody has collected these before. */
    val afterVerdict: MutableList<Int> = mutableListOf()

    fun run() {
        // Nothing in a loopback pair times out on its own, and every failure
        // mode here ends with one side blocked on a read the other will never
        // satisfy. A test that can hang is a test that will hang on the machine
        // where nobody is watching.
        val watchdog = thread(isDaemon = true, name = "scripted-head-unit-watchdog") {
            runCatching {
                Thread.sleep(MAX_LIFE_MILLIS)
                close()
            }
        }
        try {
            link.send(
                Messages.CONTROL_CHANNEL,
                Messages.VERSION_REQUEST,
                VersionExchange.encodeRequest(offers),
                forcePlaintext = true,
            )
            var announced = false
            while (true) {
                val message = link.receive() ?: return
                if (announced) afterVerdict += message.messageId
                when (message.messageId) {
                    Messages.VERSION_RESPONSE -> {
                        versionResponses += message.body
                        link.sendHandshake(tls.begin())
                    }

                    Messages.TLS_HANDSHAKE -> {
                        val reply = try {
                            tls.handshake(message.body)
                        } catch (_: SSLException) {
                            sawHandshakeFailure = true
                            return
                        }
                        link.sendHandshake(reply)
                        if (tls.handshakeComplete && !announced) {
                            announced = true
                            handshakeCompleted = true
                            verdict?.let {
                                link.send(
                                    Messages.CONTROL_CHANNEL,
                                    Messages.AUTH_SUCCEEDED,
                                    it,
                                    forcePlaintext = true,
                                )
                            }
                            hangUp()
                        }
                    }

                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            // A closed pipe is how every one of these sessions ends. It is the
            // shape of the experiment, not a failure of it.
        } finally {
            watchdog.interrupt()
        }
    }

    private fun hangUp() {
        thread(isDaemon = true, name = "scripted-head-unit-hangup") {
            runCatching {
                Thread.sleep(hangUpAfterMillis)
                close()
            }
        }
    }

    /** Closes the head unit's end, which ends both directions of a loopback pair. */
    fun close(): Unit = link.close()

    private companion object {
        /** Upper bound on one scripted session, well past any loopback handshake. */
        const val MAX_LIFE_MILLIS = 10_000L
    }
}
