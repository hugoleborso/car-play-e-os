/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto.probe

import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import org.openaap.crypto.CredentialProvider
import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki

/**
 * A set of distinct identities to present to a head unit, one at a time, to
 * find out what it actually checks.
 *
 * The state of the public record on this question is: nobody knows. Every
 * open-source head-unit implementation disables peer verification, so they tell
 * us nothing about production hardware. The one person who built a phone-side
 * stack and tested it against a VW-group head unit reported that it validated
 * both the certificate chain and the validity dates — but that is a single
 * report, about a different model year, and it has never been reproduced or
 * broken down into which specific check failed.
 *
 * A single self-signed attempt would not settle it either. "It didn't work"
 * cannot distinguish a chain check from a date check from a parser that chokes
 * on a certificate structure it has never seen. So the probe presents a series
 * of identities that differ in one dimension each, and records the TLS alert
 * the head unit returns. Alert codes are specific: `unknown_ca` and
 * `certificate_expired` and `bad_certificate` are three different findings.
 *
 * Every identity here is generated locally from keys that exist only in memory.
 * No probe uses, contains, or is derived from key material belonging to Google
 * or to any vendor — which is what makes running this legitimate interoperability
 * testing rather than credential theft.
 */
public object CredentialProbe {

    /**
     * One hypothesis, made testable.
     *
     * [dimension] names what this probe varies relative to the baseline, and
     * [tells] says what a rejection here would prove. Both exist so the report
     * is readable months later by someone who was not there.
     */
    public data class Probe(
        val id: String,
        val dimension: String,
        val tells: String,
        val credentials: CredentialProvider,
    )

    /**
     * The distinguished name of the authority every real Android Auto
     * certificate chains to.
     *
     * This is a public identifier read off a public certificate, not a secret.
     * It is here for exactly one probe: presenting a certificate that carries
     * this name but is signed by our own key tells us whether a head unit pins
     * the authority by *name* or by *key*. A name-only pin would be a genuinely
     * new finding; a key pin is the expected result. There is no way to learn
     * this without asking the question.
     */
    private const val GOOGLE_LINK_AUTHORITY_DN: String =
        "C=US, ST=California, L=Mountain View, O=Google Automotive Link"

    /** Builds the full matrix. Each call generates fresh keys. */
    public fun matrix(): List<Probe> {
        val ourCa = TestPki.authority("openaap probe CA")
        val impersonatingCa = TestPki.authority(distinguishedName = GOOGLE_LINK_AUTHORITY_DN)

        return listOf(
            Probe(
                id = "self-signed-v1",
                dimension = "baseline: the structure real endpoints use",
                tells = "If this is accepted, no certificate authority is checked at all and " +
                    "a clean-room phone side works today.",
                credentials = StaticCredentialProvider.of(
                    "self-signed-v1",
                    TestPki.selfSigned("openaap phone", version = TestPki.CertificateVersion.V1),
                ),
            ),
            Probe(
                id = "self-signed-v3",
                dimension = "certificate structure: v3 with extensions",
                tells = "If v1 is accepted and v3 is not, the head unit's parser is the " +
                    "obstacle rather than its trust policy -- a very different problem, and " +
                    "one we can simply comply with.",
                credentials = StaticCredentialProvider.of(
                    "self-signed-v3",
                    TestPki.selfSigned("openaap phone", version = TestPki.CertificateVersion.V3),
                ),
            ),
            Probe(
                id = "own-ca-v1",
                dimension = "chain depth: a real two-level chain",
                tells = "If a chain is accepted where a bare self-signed leaf is not, the " +
                    "head unit requires a chain but does not check where it terminates.",
                credentials = StaticCredentialProvider.of(
                    "own-ca-v1",
                    ourCa.issue("openaap phone", version = TestPki.CertificateVersion.V1),
                ),
            ),
            Probe(
                id = "authority-name-match",
                dimension = "how the authority is pinned: by name or by key",
                tells = "Acceptance would mean the head unit compares the issuer name and " +
                    "never verifies the signature -- which would be both a serious flaw in " +
                    "the head unit and an open door. Rejection confirms a real key pin.",
                credentials = StaticCredentialProvider.of(
                    "authority-name-match",
                    impersonatingCa.issue("openaap phone", version = TestPki.CertificateVersion.V1),
                ),
            ),
            Probe(
                id = "expired",
                dimension = "validity window: already expired",
                tells = "If an expired certificate is accepted, the head unit ignores dates. " +
                    "That matters because it is the check most likely to be skipped on a " +
                    "device whose clock is unreliable.",
                credentials = StaticCredentialProvider.of(
                    "expired",
                    TestPki.selfSigned(
                        "openaap phone",
                        validityDays = -1,
                        version = TestPki.CertificateVersion.V1,
                        validFromDaysAgo = 400,
                    ),
                ),
            ),
            Probe(
                id = "not-yet-valid",
                dimension = "validity window: starts in the future",
                tells = "Separates a genuine date check from an expiry-only check, and " +
                    "detects a head unit whose clock is behind ours.",
                credentials = StaticCredentialProvider.of(
                    "not-yet-valid",
                    TestPki.selfSigned(
                        "openaap phone",
                        validityDays = 400,
                        version = TestPki.CertificateVersion.V1,
                        validFromDaysAgo = -30,
                    ),
                ),
            ),
            Probe(
                id = "long-validity",
                dimension = "validity window: decades, as real certificates use",
                tells = "Real endpoint certificates were minted with validity measured in " +
                    "decades. A head unit that rejects short-dated certificates would be " +
                    "an odd but checkable behaviour.",
                credentials = StaticCredentialProvider.of(
                    "long-validity",
                    TestPki.selfSigned(
                        "openaap phone",
                        validityDays = 365 * 25,
                        version = TestPki.CertificateVersion.V1,
                    ),
                ),
            ),
            Probe(
                id = "rsa-4096",
                dimension = "key size above the usual 2048",
                tells = "Detects a head unit with a hard key-size ceiling, which old " +
                    "embedded stacks sometimes have.",
                credentials = StaticCredentialProvider.of(
                    "rsa-4096",
                    TestPki.selfSigned(
                        "openaap phone",
                        keyType = TestPki.KeyType.RSA_4096,
                        version = TestPki.CertificateVersion.V1,
                    ),
                ),
            ),
            Probe(
                id = "ec-p256",
                dimension = "key algorithm: elliptic curve rather than RSA",
                tells = "Every known endpoint certificate is RSA. If EC fails while RSA " +
                    "succeeds, the constraint is the cipher suite, not the identity.",
                credentials = StaticCredentialProvider.of(
                    "ec-p256",
                    TestPki.selfSigned(
                        "openaap phone",
                        keyType = TestPki.KeyType.EC_P256,
                        version = TestPki.CertificateVersion.V1,
                    ),
                ),
            ),
        )
    }

    /** What one probe produced. */
    public data class Outcome(
        val probe: Probe,
        val handshakeCompleted: Boolean,
        /** The TLS alert the peer sent, when it sent one. */
        val alert: TlsAlert?,
        val failureMessage: String?,
        /** The certificate chain the head unit presented to us, if any. */
        val peerChain: List<X509Certificate>,
    ) {
        public fun summary(): String = buildString {
            append(probe.id.padEnd(24))
            append(if (handshakeCompleted) "ACCEPTED" else "rejected")
            alert?.let { append("  alert=${it.label}") }
            failureMessage?.takeIf { !handshakeCompleted && alert == null }?.let {
                append("  (${it.take(80)})")
            }
        }
    }

    /**
     * Extracts the alert from a TLS failure.
     *
     * The alert is the whole point of running the matrix: it names the check
     * that failed. The JDK surfaces it inside an exception message rather than
     * as a structured value, so it has to be recovered by matching.
     */
    public fun classify(failure: Throwable?): TlsAlert? {
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is SSLException) {
                val text = cause.message?.lowercase().orEmpty()
                TlsAlert.entries.firstOrNull { alert -> text.contains(alert.label) }?.let { return it }
            }
            cause = cause.cause
        }
        return null
    }
}

/**
 * The TLS alerts worth distinguishing here, and what each one means about the
 * head unit rather than about TLS in general.
 */
public enum class TlsAlert(public val code: Int, public val label: String, public val meaning: String) {
    BAD_CERTIFICATE(42, "bad_certificate", "the certificate could not be parsed or was structurally rejected"),
    UNSUPPORTED_CERTIFICATE(43, "unsupported_certificate", "the certificate type is not one this head unit handles"),
    CERTIFICATE_REVOKED(44, "certificate_revoked", "revocation checking is active, which implies real PKI plumbing"),
    CERTIFICATE_EXPIRED(45, "certificate_expired", "validity dates are checked -- and the head unit's clock matters"),
    CERTIFICATE_UNKNOWN(46, "certificate_unknown", "rejected for an unstated reason; usually a failed path build"),
    UNKNOWN_CA(48, "unknown_ca", "the chain does not terminate in an authority it trusts -- the hard wall"),
    ACCESS_DENIED(49, "access_denied", "the certificate parsed and validated but the identity was refused"),
    DECODE_ERROR(50, "decode_error", "a message could not be decoded; suggests a framing problem, not a trust one"),
    DECRYPT_ERROR(51, "decrypt_error", "a signature failed to verify"),
    PROTOCOL_VERSION(70, "protocol_version", "the TLS version we offered is not acceptable"),
    INSUFFICIENT_SECURITY(71, "insufficient_security", "our cipher suites or key sizes were refused"),
    HANDSHAKE_FAILURE(40, "handshake_failure", "no acceptable set of parameters -- often cipher suites, not certificates"),
    INTERNAL_ERROR(80, "internal_error", "the head unit's stack failed on its own terms"),
}
