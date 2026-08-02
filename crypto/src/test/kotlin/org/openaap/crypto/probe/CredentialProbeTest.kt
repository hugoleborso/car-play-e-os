/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto.probe

import javax.net.ssl.SSLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openaap.crypto.AapTlsEngine
import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki
import org.openaap.crypto.TlsRole

/**
 * Tests the instrument, not the car.
 *
 * These run the probe matrix against head units whose behaviour we control, so
 * that when it is pointed at real hardware we know a rejection means the head
 * unit rejected something rather than that the probe is broken. A diagnostic
 * tool that has never been calibrated is worse than none: it produces confident
 * readings that nobody can trust.
 */
class CredentialProbeTest {

    /** Runs one probe against a head unit with a given trust policy. */
    private fun run(
        probe: CredentialProbe.Probe,
        headUnitTrust: List<java.security.cert.X509Certificate> = emptyList(),
        headUnitProtocols: List<String> = AapTlsEngine.DEFAULT_PROTOCOLS,
    ): CredentialProbe.Outcome {
        val phone = AapTlsEngine(TlsRole.SERVER, probe.credentials)
        val headUnit = AapTlsEngine(
            TlsRole.CLIENT,
            StaticCredentialProvider.of(
                "head unit",
                TestPki.selfSigned("emulated head unit"),
                trustAnchors = headUnitTrust,
            ),
            protocols = headUnitProtocols,
        )

        var failure: Throwable? = null
        try {
            phone.begin()
            var toPhone = headUnit.begin()
            var toHeadUnit = ByteArray(0)
            var rounds = 0
            while (rounds++ < 30) {
                if (toPhone.isNotEmpty()) {
                    toHeadUnit = phone.handshake(toPhone)
                    toPhone = ByteArray(0)
                } else if (toHeadUnit.isNotEmpty()) {
                    toPhone = headUnit.handshake(toHeadUnit)
                    toHeadUnit = ByteArray(0)
                } else {
                    break
                }
            }
        } catch (e: Throwable) {
            failure = e
        }

        return CredentialProbe.Outcome(
            probe = probe,
            handshakeCompleted = headUnit.handshakeComplete,
            alert = CredentialProbe.classify(failure),
            failureMessage = failure?.message,
            peerChain = headUnit.peerChain,
        )
    }

    @Test
    fun `the matrix varies exactly one dimension per probe and documents each`() {
        val matrix = CredentialProbe.matrix()

        assertTrue(matrix.size >= 9, "matrix is too small to isolate a cause")
        assertEquals(matrix.size, matrix.map { it.id }.toSet().size, "probe ids must be unique")
        matrix.forEach { probe ->
            assertTrue(probe.dimension.isNotBlank(), "${probe.id} does not say what it varies")
            assertTrue(probe.tells.isNotBlank(), "${probe.id} does not say what a result would prove")
            assertTrue(probe.credentials.chain.isNotEmpty(), "${probe.id} presents no certificate")
        }
    }

    @Test
    fun `a head unit that checks nothing accepts every well-formed identity`() {
        // The optimistic hypothesis. Probes that fail here are failing for
        // reasons other than trust -- which is exactly what we need to know
        // before pointing the matrix at a car.
        val excluded = setOf("expired", "not-yet-valid")
        val outcomes = CredentialProbe.matrix()
            .filterNot { it.id in excluded }
            .map { run(it) }

        outcomes.forEach { outcome ->
            assertTrue(
                outcome.handshakeCompleted,
                "${outcome.probe.id} failed against a head unit that checks nothing: " +
                    "${outcome.failureMessage}",
            )
        }
    }

    @Test
    fun `a head unit that pins an authority rejects everything and says why`() {
        // The pessimistic hypothesis, and the one the public evidence points to.
        // The value is the alert: unknown_ca names the check that failed, which
        // "it didn't connect" does not.
        val pinned = TestPki.authority("pinned vendor authority")
        val outcomes = CredentialProbe.matrix().map { run(it, headUnitTrust = listOf(pinned.certificate)) }

        outcomes.forEach { outcome ->
            assertFalse(
                outcome.handshakeCompleted,
                "${outcome.probe.id} was accepted by a head unit pinning a different authority",
            )
            assertNotNull(
                outcome.alert,
                "${outcome.probe.id} produced no classifiable alert: ${outcome.failureMessage}",
            )
        }

        // Which alert a rejection produces depends on whose TLS stack is
        // rejecting. The JDK reports a failed path build as certificate_unknown;
        // OpenSSL, which is what head units run, reports unknown_ca for the same
        // condition. Both belong to the same family and both mean "your chain
        // does not terminate anywhere I trust", so the assertion is on the
        // family. Pinning it to one code would make this test pass here and
        // then mislead whoever reads the field report.
        val trustFamily = setOf(TlsAlert.UNKNOWN_CA, TlsAlert.CERTIFICATE_UNKNOWN, TlsAlert.BAD_CERTIFICATE)
        assertTrue(
            outcomes.all { it.alert in trustFamily },
            "a pinning head unit should reject on trust grounds; got ${outcomes.map { it.alert?.label }}",
        )
    }

    @Test
    fun `a certificate from the pinned authority is accepted, proving rejection was about trust`() {
        // The control. Without it, a matrix that rejects everything could just
        // be broken rather than informative.
        val pinned = TestPki.authority("pinned vendor authority")
        val probe = CredentialProbe.Probe(
            id = "control-pinned",
            dimension = "control",
            tells = "confirms the harness can complete a handshake at all",
            credentials = StaticCredentialProvider.of(
                "control",
                pinned.issue("openaap phone", version = TestPki.CertificateVersion.V1),
            ),
        )

        val outcome = run(probe, headUnitTrust = listOf(pinned.certificate))
        assertTrue(outcome.handshakeCompleted, "control failed: ${outcome.failureMessage}")
    }

    @Test
    fun `expiry probes are rejected by a validating head unit and named as date failures`() {
        val pinned = TestPki.authority("pinned vendor authority")
        // Issue the expired and not-yet-valid identities from the *trusted*
        // authority, so the only thing wrong with them is the date. Otherwise
        // the chain failure would mask the date failure and the probe would
        // measure nothing.
        val expired = CredentialProbe.Probe(
            "trusted-but-expired",
            "validity window only",
            "isolates the date check from the chain check",
            StaticCredentialProvider.of(
                "trusted-but-expired",
                pinned.issue(
                    "openaap phone",
                    validityDays = -1,
                    version = TestPki.CertificateVersion.V1,
                    validFromDaysAgo = 400,
                ),
            ),
        )

        val outcome = run(expired, headUnitTrust = listOf(pinned.certificate))
        assertFalse(outcome.handshakeCompleted)
        assertEquals(TlsAlert.CERTIFICATE_EXPIRED, outcome.alert, "got ${outcome.failureMessage}")
    }

    @Test
    fun `alert classification digs through wrapped causes`() {
        val wrapped = RuntimeException("session failed", SSLException("Received fatal alert: unknown_ca"))
        assertEquals(TlsAlert.UNKNOWN_CA, CredentialProbe.classify(wrapped))
        assertEquals(null, CredentialProbe.classify(RuntimeException("no TLS involved")))
        assertEquals(null, CredentialProbe.classify(null))
    }

    @Test
    fun `every alert we distinguish explains what it means about the head unit`() {
        // The alert codes are the deliverable of the field test. If one arrives
        // without an explanation attached, the person reading the report in a
        // car park cannot act on it.
        TlsAlert.entries.forEach { alert ->
            assertTrue(alert.meaning.isNotBlank(), "${alert.label} has no interpretation")
            assertTrue(alert.code in 0..255, "${alert.label} has an implausible code")
        }
    }
}
