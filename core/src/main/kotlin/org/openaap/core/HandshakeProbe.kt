/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import org.openaap.crypto.AapTlsEngine
import org.openaap.crypto.CredentialProvider
import org.openaap.crypto.TlsRole
import org.openaap.crypto.probe.CredentialProbe
import org.openaap.crypto.probe.TlsAlert
import org.openaap.protocol.AuthVerdict
import org.openaap.protocol.Messages
import org.openaap.protocol.ProtoScan
import org.openaap.protocol.VersionExchange
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
 *
 * The one thing it does past that point is *listen*, when a [provocation] asks
 * it to. The verdict was always treated as the end of the probe, so nothing at
 * all is known about what a head unit says between refusing a session and
 * dropping the cable — and a teardown reason would be a second code space.
 */
public class HandshakeProbe(
    private val credentials: CredentialProvider,
    private val identity: PhoneIdentity = PhoneIdentity(model = "probe", maker = "openaap"),
    /**
     * What this run does differently, if anything.
     *
     * Defaults to nothing, which is what the credential matrix wants: it varies
     * the identity and holds the sequence fixed. The status matrix does the
     * reverse.
     */
    private val provocation: HandshakeProvocation = HandshakeProvocation.NONE,
) {

    /** Runs one step of whichever matrix it came from. */
    public constructor(step: ProbeStep, identity: PhoneIdentity) :
        this(step.credentials, identity, step.provocation)

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
        /**
         * Whether the head unit sent a verdict message at all.
         *
         * Kept apart from [verdictStatus] because "no message" and "a message
         * with no status in it" are different claims about the head unit, and
         * collapsing two such claims into one absent value is the exact mistake
         * that made this project publish a wrong result. The same distinction,
         * one level up.
         */
        val verdictSeen: Boolean = false,
        /** The verdict body exactly as it arrived, in hex. The evidence, not a reading of it. */
        val verdictBody: String? = null,
        /** The status decoded as a raw varint, or `null` when the body carried none. */
        val verdictStatus: Long? = null,
        val transcript: List<String>,
    ) {
        public val succeeded: Boolean get() = stage == Stage.AUTHENTICATED

        /** A single line for a report table. */
        public fun line(): String = buildString {
            append(credentialName.padEnd(24))
            append(stage.name.padEnd(22))
            append(verdictLabel().padEnd(14))
            append(alert?.label?.padEnd(24) ?: "".padEnd(24))
            headUnitProtocol?.let { append("proto=${it.first}.${it.second} ") }
            negotiatedTls?.let { append("$it ") }
            failure?.takeIf { alert == null }?.let { append("(${it.take(60)})") }
        }

        /**
         * The verdict as a column value.
         *
         * Three outcomes, never two: no message, a message with no status, and a
         * number. The middle one has never been observed and would be a finding
         * in itself.
         */
        public fun verdictLabel(): String = when {
            !verdictSeen -> "no verdict"
            verdictStatus == null -> "verdict, no status"
            else -> "status=$verdictStatus"
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
        val tls = AapTlsEngine(
            TlsRole.SERVER,
            credentials,
            requestPeerCertificate = provocation.requestPeerCertificate,
        )
        val link = AapLink(transport, tls)

        var stage = Stage.NO_CONTACT
        var headUnitProtocol: Pair<Int, Int>? = null
        var rounds = 0
        var failure: Throwable? = null
        var refusal: String? = null
        var verdictSeen = false
        var verdictBody: String? = null
        var verdictStatus: Long? = null

        // Set once the TLS engine has failed. Feeding it further records would
        // throw again on every one of them and bury the first failure -- which
        // is the only one that says anything -- under identical copies.
        var handshakeFaulted = false

        // Not a val. A provocation that asks us to listen past the verdict is
        // asking for a *shorter* wait than the outer timeout, not a longer one:
        // the outer timeout bounds a car that never speaks, the linger bounds a
        // car that has already said its piece.
        var deadline = System.currentTimeMillis() + timeoutMillis

        try {
            while (System.currentTimeMillis() < deadline) {
                val message = link.receive()
                if (message == null) {
                    transcript += "head unit closed the link at stage $stage"
                    break
                }

                when (message.messageId) {
                    Messages.VERSION_REQUEST -> {
                        val request = VersionExchange.parseRequest(message.body)
                        headUnitProtocol = request.major to request.minor
                        transcript += "head unit offers protocol ${request.major}.${request.minor}"
                        val answer = versionAnswer(request)
                        link.send(
                            Messages.CONTROL_CHANNEL,
                            Messages.VERSION_RESPONSE,
                            answer,
                            forcePlaintext = true,
                        )
                        transcript += "answered with ${ProtoScan.hex(answer)}" +
                            " (${describeVersionAnswer()})"
                        stage = Stage.VERSION_EXCHANGED
                        tls.begin()
                    }

                    Messages.TLS_HANDSHAKE -> {
                        if (handshakeFaulted) {
                            transcript += "ignored ${message.body.size} further handshake bytes " +
                                "after the engine had already failed"
                            continue
                        }
                        rounds++
                        if (stage == Stage.VERSION_EXCHANGED) stage = Stage.HANDSHAKE_IN_PROGRESS
                        transcript += "handshake round $rounds: ${message.body.size} bytes in"
                        try {
                            val reply = tls.handshake(message.body)
                            if (reply.isNotEmpty()) {
                                link.sendHandshake(reply)
                                transcript += "handshake round $rounds: ${reply.size} bytes out"
                            }
                            if (tls.handshakeComplete && stage != Stage.HANDSHAKE_COMPLETE) {
                                stage = Stage.HANDSHAKE_COMPLETE
                                transcript += "TLS established: ${tls.negotiatedProtocol} / " +
                                    tls.negotiatedCipherSuite
                                transcript += "head unit presented " +
                                    "${tls.peerChain.size} certificate(s) of its own"
                            }
                        } catch (e: SSLException) {
                            handshakeFaulted = true
                            failure = e
                            transcript += "handshake round $rounds failed: ${e.message}"
                            // Say why, rather than going quiet. A peer that never
                            // receives an alert cannot report a specific reason
                            // for the session ending, and a specific reason is
                            // the entire object of the exercise.
                            runCatching { tls.closeOutbound() }
                                .getOrDefault(ByteArray(0))
                                .takeIf { it.isNotEmpty() }
                                ?.let {
                                    link.sendHandshake(it)
                                    transcript += "sent ${it.size} bytes of TLS alert"
                                }
                            deadline = shorten(deadline)
                        }
                    }

                    Messages.AUTH_SUCCEEDED -> {
                        // Read as a raw varint, never through the generated
                        // enum. A closed proto2 enum reports an out-of-range
                        // value as an absent field, and reading absent as
                        // "nothing to object to" is exactly how this project
                        // published a wrong result: a head unit answering -3
                        // was recorded as having accepted us, nine times over.
                        // See AuthVerdict.
                        verdictSeen = true
                        verdictBody = ProtoScan.hex(message.body)
                        verdictStatus = AuthVerdict.statusOf(message.body)
                        transcript += "head unit verdict: ${AuthVerdict.describe(verdictStatus)}"
                        transcript += "verdict body raw: $verdictBody"
                        // Every field, decoded without reference to our schema.
                        // The eleven bytes that carried -3 were reported as an
                        // empty body for a month because our schema declared one
                        // field and the car sent a value it could not hold; a
                        // second undeclared field would hide exactly as well.
                        ProtoScan.describe(message.body, indent = "")
                            .lineSequence()
                            .forEach { transcript += "verdict body decoded: $it" }

                        if (verdictStatus == AuthVerdict.OK) {
                            stage = Stage.AUTHENTICATED
                        } else {
                            refusal = "head unit refused the session at stage $stage: " +
                                AuthVerdict.describe(verdictStatus)
                        }

                        if (provocation.lingerMillis <= 0) {
                            // The question is answered. Going further would only
                            // risk confusing the outcome with an unrelated fault.
                            return finish(
                                stage, null, refusal, headUnitProtocol, tls, rounds,
                                verdictSeen, verdictBody, verdictStatus, transcript,
                            )
                        }
                        transcript += "listening for up to ${provocation.lingerMillis} ms more, " +
                            "to see what follows a verdict"
                        deadline = shorten(deadline)
                    }

                    else -> {
                        transcript += "unexpected " +
                            Messages.describe(message.channel, message.messageId) +
                            " (${message.body.size} bytes)"
                        // Anything arriving after a verdict is undocumented by
                        // definition -- nobody has waited here before -- so its
                        // body is decoded blind rather than named and dropped.
                        if (message.channel == Messages.CONTROL_CHANNEL && message.body.isNotEmpty()) {
                            transcript += "  raw ${ProtoScan.hex(message.body, 64)}"
                            ProtoScan.describe(message.body, indent = "  ")
                                .lineSequence()
                                .forEach { transcript += it }
                        }
                    }
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                transcript += "stopped listening at stage $stage"
            }
        } catch (e: Throwable) {
            failure = e
            transcript += "failed at stage $stage: ${e::class.simpleName}: ${e.message}"
        }

        return finish(
            stage,
            CredentialProbe.classify(failure),
            // The refusal outranks whatever ended the read. A head unit that
            // states a verdict and then drops the cable has told us two things,
            // and the transport error is the less interesting of them -- but it
            // arrives second and would otherwise overwrite the finding.
            refusal ?: failure?.message ?: transcript.lastOrNull(),
            headUnitProtocol,
            tls,
            rounds,
            verdictSeen,
            verdictBody,
            verdictStatus,
            transcript,
        )
    }

    /** Brings the deadline forward to the linger window, never pushes it out. */
    private fun shorten(deadline: Long): Long =
        minOf(deadline, System.currentTimeMillis() + provocation.lingerMillis)

    /**
     * The bytes of the version response, which is the last message the phone
     * controls before TLS and therefore the last one that can provoke anything.
     */
    private fun versionAnswer(request: VersionExchange.Request): ByteArray {
        val minor = minOf(request.minor, identity.protocolMaxMinor)
        return when (provocation.versionAnswer) {
            HandshakeProvocation.VersionAnswer.AGREE ->
                VersionExchange.encodeResponse(
                    VersionExchange.Response(
                        identity.protocolMajor,
                        minor,
                        VersionExchange.STATUS_MATCH,
                    )
                )

            HandshakeProvocation.VersionAnswer.MISMATCH_STATUS ->
                VersionExchange.encodeResponse(
                    VersionExchange.Response(
                        identity.protocolMajor,
                        minor,
                        VersionExchange.STATUS_MISMATCH,
                    )
                )

            HandshakeProvocation.VersionAnswer.MISMATCH_MAJOR ->
                VersionExchange.encodeResponse(
                    VersionExchange.Response(
                        // One above what the head unit asked for, so the
                        // mismatch is unambiguous rather than a version it might
                        // also happen to speak.
                        request.major + 1,
                        minor,
                        VersionExchange.STATUS_MATCH,
                    )
                )

            // The first four bytes are exactly the *request* layout, so this is
            // malformed without being nonsense: a lenient parser reads it, a
            // strict one does not, and which this head unit is decides how it
            // reports the fault.
            HandshakeProvocation.VersionAnswer.TRUNCATED ->
                VersionExchange.encodeResponse(
                    VersionExchange.Response(
                        identity.protocolMajor,
                        minor,
                        VersionExchange.STATUS_MATCH,
                    )
                ).copyOf(TRUNCATED_VERSION_BYTES)
        }
    }

    private fun describeVersionAnswer(): String = when (provocation.versionAnswer) {
        HandshakeProvocation.VersionAnswer.AGREE -> "agreeing"
        HandshakeProvocation.VersionAnswer.MISMATCH_STATUS -> "status word says mismatch"
        HandshakeProvocation.VersionAnswer.MISMATCH_MAJOR -> "major version it cannot speak"
        HandshakeProvocation.VersionAnswer.TRUNCATED -> "truncated to $TRUNCATED_VERSION_BYTES bytes"
    }

    private fun finish(
        stage: Stage,
        alert: TlsAlert?,
        failure: String?,
        headUnitProtocol: Pair<Int, Int>?,
        tls: AapTlsEngine,
        rounds: Int,
        verdictSeen: Boolean,
        verdictBody: String?,
        verdictStatus: Long?,
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
        verdictSeen = verdictSeen,
        verdictBody = verdictBody,
        verdictStatus = verdictStatus,
        transcript = transcript,
    )

    private companion object {
        /** The version *request* length, which is what a truncated response looks like. */
        const val TRUNCATED_VERSION_BYTES = 4
    }
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
            appendLine(discrimination(results))
        }

    /**
     * What the spread of verdict codes says, which is the whole object of the
     * status matrix.
     *
     * Written as prose rather than a table because the reader is standing next
     * to a car and the conclusion is not obvious from a column of numbers: one
     * distinct code across several kinds of failure means something quite
     * different from two.
     */
    public fun discrimination(results: List<HandshakeProbe.Result>): String = buildString {
        appendLine("Codes seen")
        appendLine("-".repeat(78))
        val spoke = results.filter { it.stage != HandshakeProbe.Stage.NO_CONTACT }
        if (spoke.isEmpty()) {
            appendLine("  Nothing was measured: the head unit did not speak in any attempt.")
            return@buildString
        }

        val codes = spoke.filter { it.verdictSeen }.map { it.verdictStatus }.distinct()
        val silent = spoke.filterNot { it.verdictSeen }
        spoke.filter { it.verdictSeen }
            .groupBy { it.verdictStatus }
            .forEach { (status, rows) ->
                appendLine("  ${(status?.toString() ?: "no status field").padEnd(18)}" +
                    rows.joinToString(", ") { it.credentialName })
            }
        if (silent.isNotEmpty()) {
            appendLine("  ${"(no verdict sent)".padEnd(18)}${silent.joinToString(", ") { it.credentialName }}")
        }
        appendLine()

        when {
            codes.size > 1 ->
                appendLine(
                    "  The head unit answers different provocations with different codes, so the\n" +
                        "  code space is meaningful and part of it is now mapped. This is new: no\n" +
                        "  public source records any of these values."
                )

            codes.size == 1 && silent.isNotEmpty() ->
                appendLine(
                    "  One code, and it appears only where the session got far enough to earn a\n" +
                        "  verdict. That bounds what it can mean, without yet showing it to be\n" +
                        "  specific to certificates: the rows that never reached a verdict do not\n" +
                        "  contradict either reading."
                )

            codes.size == 1 ->
                appendLine(
                    "  One code for every kind of failure provoked, including ones that have\n" +
                        "  nothing to do with the certificate. It is a generic failure indicator and\n" +
                        "  says nothing about the trust wall. That is a negative result about the\n" +
                        "  code and a real result about the method."
                )

            else ->
                appendLine("  No verdict message arrived in any attempt that got far enough to expect one.")
        }
    }
}
