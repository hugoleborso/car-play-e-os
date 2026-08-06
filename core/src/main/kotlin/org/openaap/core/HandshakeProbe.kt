/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import java.security.cert.X509Certificate
import org.openaap.crypto.AapTlsEngine
import org.openaap.crypto.CredentialProvider
import org.openaap.crypto.TlsRole
import org.openaap.crypto.probe.CredentialProbe
import org.openaap.crypto.probe.TlsAlert
import org.openaap.protocol.Messages
import org.openaap.protocol.VersionExchange
import org.openaap.protocol.proto.AuthSucceeded
import org.openaap.protocol.proto.ResultCode
import org.openaap.transport.Transport

/**
 * Runs one session as far as the head unit will let it go, and records exactly
 * where it stopped.
 *
 * This is the field instrument. A full session is not the goal — the goal is
 * the failure, described precisely enough to act on. "It didn't connect" is
 * worth nothing; "the head unit advertised protocol 1.4, completed the version
 * exchange, then answered our certificate with alert 48" is the whole answer.
 *
 * It deliberately stops at the point the head unit declares the session
 * authenticated. Everything past that is ordinary protocol work whose behaviour
 * we already know; everything before it is the open question.
 */
public class HandshakeProbe(
    private val credentials: CredentialProvider,
    private val identity: PhoneIdentity = PhoneIdentity(model = "probe", maker = "openaap"),
) {

    /** How far a session got, and what stopped it. */
    public enum class Stage {
        /** The head unit never spoke. Usually a transport or accessory problem, not a protocol one. */
        NO_CONTACT,

        /** We saw a version request and answered it. */
        VERSION_EXCHANGED,

        /** TLS records were flowing in both directions. */
        HANDSHAKE_IN_PROGRESS,

        /** The TLS session established. The head unit had not yet given its verdict. */
        HANDSHAKE_COMPLETE,

        /** The head unit accepted our certificate. This is the result that matters. */
        AUTHENTICATED,
    }

    /** Everything one probe run learned. */
    public data class Result(
        val credentialName: String,
        val stage: Stage,
        val alert: TlsAlert?,
        val failure: String?,
        val headUnitProtocol: Pair<Int, Int>?,
        val negotiatedTls: String?,
        val negotiatedCipherSuite: String?,
        /**
         * The certificate chain the head unit presented to us.
         *
         * Collected whatever the outcome. No public source records what a MIB2
         * presents, and its issuer and validity window would identify who
         * actually built the projection stack in these cars.
         */
        val headUnitChain: List<X509Certificate>,
        val handshakeRounds: Int,
        val transcript: List<String>,
    ) {
        public val succeeded: Boolean get() = stage == Stage.AUTHENTICATED

        /** A single line for a report table. */
        public fun line(): String = buildString {
            append(credentialName.padEnd(24))
            append(stage.name.padEnd(22))
            append(alert?.label?.padEnd(24) ?: "".padEnd(24))
            headUnitProtocol?.let { append("proto=${it.first}.${it.second} ") }
            negotiatedTls?.let { append("$it ") }
            failure?.takeIf { alert == null }?.let { append("(${it.take(60)})") }
        }
    }

    /**
     * Drives one attempt to completion or failure.
     *
     * Never throws for a protocol outcome — a rejection is the data we came
     * for, not an error. It only propagates failures that mean the probe itself
     * could not run.
     */
    public fun run(transport: Transport, timeoutMillis: Long = 30_000): Result {
        val transcript = mutableListOf<String>()
        val tls = AapTlsEngine(TlsRole.SERVER, credentials)
        val link = AapLink(transport, tls)

        var stage = Stage.NO_CONTACT
        var headUnitProtocol: Pair<Int, Int>? = null
        var rounds = 0
        var failure: Throwable? = null

        val deadline = System.currentTimeMillis() + timeoutMillis
        try {
            while (System.currentTimeMillis() < deadline) {
                val message = link.receive() ?: run {
                    transcript += "head unit closed the link at stage $stage"
                    null
                } ?: break

                when (message.messageId) {
                    Messages.VERSION_REQUEST -> {
                        val request = VersionExchange.parseRequest(message.body)
                        headUnitProtocol = request.major to request.minor
                        transcript += "head unit offers protocol ${request.major}.${request.minor}"
                        val minor = minOf(request.minor, identity.protocolMaxMinor)
                        link.send(
                            Messages.CONTROL_CHANNEL,
                            Messages.VERSION_RESPONSE,
                            VersionExchange.encodeResponse(
                                VersionExchange.Response(identity.protocolMajor, minor, VersionExchange.STATUS_MATCH)
                            ),
                            forcePlaintext = true,
                        )
                        transcript += "answered with ${identity.protocolMajor}.$minor"
                        stage = Stage.VERSION_EXCHANGED
                        tls.begin()
                    }

                    Messages.TLS_HANDSHAKE -> {
                        rounds++
                        if (stage == Stage.VERSION_EXCHANGED) stage = Stage.HANDSHAKE_IN_PROGRESS
                        transcript += "handshake round $rounds: ${message.body.size} bytes in"
                        val reply = tls.handshake(message.body)
                        if (reply.isNotEmpty()) {
                            link.sendHandshake(reply)
                            transcript += "handshake round $rounds: ${reply.size} bytes out"
                        }
                        if (tls.handshakeComplete && stage != Stage.HANDSHAKE_COMPLETE) {
                            stage = Stage.HANDSHAKE_COMPLETE
                            transcript += "TLS established: ${tls.negotiatedProtocol} / ${tls.negotiatedCipherSuite}"
                        }
                    }

                    Messages.AUTH_SUCCEEDED -> {
                        val parsed = runCatching { AuthSucceeded.parseFrom(message.body) }
                        if (parsed.isFailure) {
                            // Never infer acceptance from a message we could not
                            // read. This previously defaulted to RESULT_OK,
                            // which meant an unparseable body -- the exact shape
                            // an unexpected implementation would produce --
                            // rendered as the headline result of the project.
                            // A false AUTHENTICATED is far more costly than a
                            // missing one: it is the claim everyone would check.
                            transcript += "head unit sent 0x0004 with a body we could not parse " +
                                "(${message.body.size} bytes): ${parsed.exceptionOrNull()?.message}"
                            return@run finish(
                                stage,
                                null,
                                "head unit sent an unparseable verdict; not treating this as acceptance",
                                headUnitProtocol,
                                tls,
                                rounds,
                                transcript,
                            )
                        }
                        val result = parsed.getOrNull()
                        // An empty body is legitimate: the message id is itself
                        // the signal, and the result field is optional.
                        val code = result?.takeIf { it.hasResult() }?.result ?: ResultCode.RESULT_OK
                        transcript += if (result?.hasResult() == true) {
                            "head unit verdict: $code"
                        } else {
                            "head unit sent an empty verdict body; reading the message itself as acceptance"
                        }
                        if (code == ResultCode.RESULT_OK) {
                            stage = Stage.AUTHENTICATED
                            // The question is answered. Going further would only
                            // risk confusing the outcome with an unrelated fault.
                            return@run finish(
                                stage, null, null, headUnitProtocol, tls, rounds, transcript
                            )
                        }
                        transcript += "head unit refused the session after a completed handshake"
                        return@run finish(
                            stage,
                            null,
                            "head unit returned $code after the TLS handshake succeeded",
                            headUnitProtocol,
                            tls,
                            rounds,
                            transcript,
                        )
                    }

                    else -> transcript += "unexpected ${Messages.describe(message.channel, message.messageId)}"
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                transcript += "timed out after ${timeoutMillis}ms at stage $stage"
            }
        } catch (e: Throwable) {
            failure = e
            transcript += "failed at stage $stage: ${e::class.simpleName}: ${e.message}"
        }

        return finish(
            stage,
            CredentialProbe.classify(failure),
            failure?.message ?: transcript.lastOrNull(),
            headUnitProtocol,
            tls,
            rounds,
            transcript,
        )
    }

    private fun finish(
        stage: Stage,
        alert: TlsAlert?,
        failure: String?,
        headUnitProtocol: Pair<Int, Int>?,
        tls: AapTlsEngine,
        rounds: Int,
        transcript: List<String>,
    ) = Result(
        credentialName = credentials.name,
        stage = stage,
        alert = alert,
        failure = failure,
        headUnitProtocol = headUnitProtocol,
        negotiatedTls = tls.negotiatedProtocol,
        negotiatedCipherSuite = tls.negotiatedCipherSuite,
        headUnitChain = tls.peerChain,
        handshakeRounds = rounds,
        transcript = transcript,
    )
}

/** Renders a set of probe results as the report to bring back from the car. */
public object ProbeReport {

    public fun render(results: List<HandshakeProbe.Result>, context: Map<String, String> = emptyMap()): String =
        buildString {
            appendLine("openaap handshake probe report")
            appendLine("=".repeat(78))
            context.forEach { (key, value) -> appendLine("$key: $value") }
            appendLine()

            val chain = results.firstOrNull { it.headUnitChain.isNotEmpty() }?.headUnitChain
            if (chain != null) {
                appendLine("Certificate the head unit presented to us")
                appendLine("-".repeat(78))
                chain.forEachIndexed { index, certificate ->
                    appendLine("  [$index] subject : ${certificate.subjectX500Principal.name}")
                    appendLine("      issuer  : ${certificate.issuerX500Principal.name}")
                    appendLine("      serial  : ${certificate.serialNumber.toString(16)}")
                    appendLine("      valid   : ${certificate.notBefore} .. ${certificate.notAfter}")
                    appendLine("      key     : ${certificate.publicKey.algorithm}, version ${certificate.version}")
                }
                appendLine()
            } else {
                appendLine("The head unit presented no certificate to us.")
                appendLine()
            }

            appendLine("Results")
            appendLine("-".repeat(78))
            results.forEach { appendLine("  ${it.line()}") }
            appendLine()

            val accepted = results.filter { it.succeeded }
            appendLine("Verdict")
            appendLine("-".repeat(78))
            when {
                accepted.isNotEmpty() ->
                    appendLine(
                        "  ACCEPTED: ${accepted.joinToString { it.credentialName }}.\n" +
                            "  This head unit does not require a Google-issued certificate. That is a\n" +
                            "  new result and worth publishing."
                    )

                results.all { it.stage == HandshakeProbe.Stage.NO_CONTACT } ->
                    appendLine(
                        "  The head unit never spoke. This is a transport or accessory-mode problem,\n" +
                            "  not a certificate one. Check the cable, the USB port and whether\n" +
                            "  projection is enabled on the unit before reading anything into it."
                    )

                results.none { it.stage.ordinal >= HandshakeProbe.Stage.HANDSHAKE_COMPLETE.ordinal } ->
                    appendLine(
                        "  Every identity was rejected during the handshake. The alert column says\n" +
                            "  which check failed for each."
                    )

                else ->
                    appendLine(
                        "  Handshakes completed but no identity was accepted. The head unit is\n" +
                            "  making its decision after TLS rather than during it, which is worth\n" +
                            "  knowing: the certificate parsed and validated as a certificate, and\n" +
                            "  was refused on identity."
                    )
            }

            appendLine()
            appendLine("Transcripts")
            appendLine("-".repeat(78))
            results.forEach { result ->
                appendLine("  ${result.credentialName}")
                result.transcript.forEach { appendLine("    $it") }
                appendLine()
            }
        }
}
