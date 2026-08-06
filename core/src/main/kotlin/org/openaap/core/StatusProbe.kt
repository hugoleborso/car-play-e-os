/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki
import org.openaap.crypto.probe.CredentialProbe
import org.openaap.crypto.probe.WithheldCredentials

/**
 * Provokes different kinds of failure to find out whether the head unit answers
 * them differently.
 *
 * ### The question
 *
 * A production VW MIB2 answers every session with control message `0x0004`
 * carrying `status = -3`, then drops USB accessory mode a few seconds later. It
 * did so for all nine identities in the credential matrix, which varied X.509
 * version, chain depth, validity window, key size and key algorithm. Nothing
 * public documents the value.
 *
 * `-3` therefore has two readings that the existing evidence cannot separate:
 *
 * - **specific** — "your certificate is not one I trust", in which case the
 *   number locates the trust wall exactly and the nine-certificate invariance
 *   means the wall is above all of them;
 * - **generic** — "something went wrong", in which case it locates nothing, and
 *   nine identical answers are nine readings of one uninformative constant.
 *
 * Varying the certificate again cannot tell these apart, because both readings
 * predict the same result. What can is varying something that is *not* the
 * certificate and seeing whether the number moves. A code that changes maps a
 * second point in the code space, which nobody has published; a code that never
 * changes is itself the answer, and a much less comfortable one.
 *
 * ### Why every variant here acts before the verdict
 *
 * The verdict arrives the instant the TLS handshake settles, with no message
 * from the phone in between. So the reachable inputs are the version exchange,
 * the handshake, and the certificate inside it — and nothing else. Two of the
 * obvious ideas are excluded by that fact rather than by taste:
 *
 * - **"complete TLS and send a deliberately malformed first message"** — the
 *   verdict has already been sent by then. The idea is kept, but moved to the
 *   only place in the session where it can still reach a verdict: the version
 *   response, in [truncated]. That is the malformed-message experiment, run
 *   where it can produce an answer.
 * - **"complete TLS and send nothing at all"** — already run, in the projection
 *   variant matrix, and it ended in the same teardown. It also cannot move the
 *   verdict, for the same reason. Repeating it would spend a connection to
 *   re-observe a known constant.
 *
 * A third exclusion is worth stating because it looks like an obvious axis.
 * **Forcing a different cipher suite is not a test.** The phone is the TLS
 * server, so it chooses the suite out of what the head unit offered; a suite the
 * head unit did not offer cannot be selected at all, and one it did offer it can
 * hardly object to afterwards. Either the variant degenerates into
 * [noCertificate], or it asks the head unit whether it dislikes something it
 * volunteered. Neither is worth a trip to a car.
 *
 * A fourth was written, and then measured out of existence. **A certificate with
 * an empty subject cannot be built**: the platform refuses to parse one back,
 * with `Empty subject DN not allowed in v1 certificate` — and v1 is the whole
 * point, because it is the structure real endpoints present. An empty subject on
 * a v3 certificate is permitted only alongside a critical subject alternative
 * name, which would vary the structure and the name together and could attribute
 * neither. `TestPkiTest` pins both constraints so the idea is not designed a
 * second time. It is recorded here rather than deleted because "we tried and the
 * platform said no" is a different fact from "we did not think of it".
 *
 * ### What is not obtainable
 *
 * There is no positive control. Producing a session this head unit accepts would
 * require a certificate signed by Google, which this project will not obtain,
 * so "the code for success" cannot be measured and `0` remains an assumption
 * read off our own schema. Every result here is therefore a comparison between
 * kinds of failure, and the matrix is honest about being unable to anchor one
 * end of the scale.
 *
 * ### Clean room
 *
 * Every identity below is generated at runtime from keys that exist only in
 * memory. One variant names Google's authority — as a *name*, read off a public
 * certificate, in a message that is a list of names. No key material belonging
 * to Google or to any vendor is used, embedded or derived from.
 */
public object StatusProbe {

    /**
     * How long to keep listening after the verdict, before giving up on it.
     *
     * The measured teardown was 2.3 seconds after authentication in the
     * projection matrix, so this is comfortably past it. The read ends when the
     * head unit drops the accessory regardless; this only bounds the case where
     * it does not.
     */
    private const val LINGER_MILLIS: Long = 6_000

    /**
     * The variants worth a connection, in the order to spend connections on.
     *
     * Ordering is deliberate rather than logical: a visit to a car ends when it
     * ends, so the variants whose result would most change what we believe run
     * first. The two that ask whether `-3` is about *the head unit's* identity
     * rather than ours come immediately after the reference, because if either
     * moves the number then every conclusion drawn from the credential matrix
     * was about the wrong side of the handshake.
     */
    public fun matrix(): List<ProbeStep> = listOf(
        baseline(),
        noPeerCertificateRequest(),
        googleIssuerAdvertised(),
        versionStatusMismatch(),
        noCertificate(),
        versionMajorMismatch(),
        truncated(),
    )

    /**
     * The reference point, which is not optional — and is no longer what the
     * nine credential runs did.
     *
     * Every other row is a comparison, and a comparison against a remembered
     * number measured by different code is not a comparison. Two things changed
     * under this matrix: the verdict decoder, which is why the matrix exists,
     * and the `CertificateRequest`, which the phone believed it was sending and
     * was not. `AapTlsEngine` set `wantClientAuth` and then cleared it again
     * with `needClientAuth = false`, and those are one field in JSSE. So this
     * row is the first session this project has ever run that asks the head unit
     * to identify itself.
     *
     * That makes it a reference and a variant at once, which is unusual and
     * worth naming: [noPeerCertificateRequest] reproduces what the nine actually
     * did, so the two rows bracket the bug.
     */
    private fun baseline(): ProbeStep = ProbeStep(
        id = "baseline",
        varies = "nothing, except that the certificate request is now genuinely sent",
        tells = "Re-establishes -3 with the corrected decoder. Also the first session that " +
            "really asks the car for a certificate: if this differs from the no-request row, " +
            "asking is what the car has been reacting to all along.",
        credentials = phoneIdentity("baseline"),
        provocation = HandshakeProvocation(lingerMillis = LINGER_MILLIS),
    )

    /**
     * What the nine credential runs actually did, reproduced deliberately.
     *
     * The recorded observation was that this head unit presents no certificate
     * of its own "despite our `CertificateRequest`". That request was never
     * sent — see [baseline] — so the observation was about a question nobody
     * asked, and the hypothesis it supported is untested rather than refuted.
     *
     * The hypothesis is still the strongest one here: that `-3` is the head unit
     * reporting *its own* failure to authenticate rather than judging ours. This
     * row now serves two purposes. It reproduces the historical TLS shape, so
     * `-3` from it confirms nothing has drifted; and paired with [baseline] it
     * isolates the request itself, which is the one thing that changed under the
     * fix.
     */
    private fun noPeerCertificateRequest(): ProbeStep = ProbeStep(
        id = "no-peer-cert-request",
        varies = "we do not ask the car for a certificate — what every previous run did",
        tells = "Reproduces the TLS shape of the nine credential probes, whose CertificateRequest " +
            "was never actually sent. If this is -3 and the baseline is not, the car has been " +
            "answering a question about itself, not about us.",
        credentials = phoneIdentity("no-peer-cert-request"),
        provocation = HandshakeProvocation(
            requestPeerCertificate = false,
            lingerMillis = LINGER_MILLIS,
        ),
    )

    /**
     * The same hypothesis approached from the other side, and the one variant
     * here that could return something valuable even if the code never moves.
     *
     * A TLS client picks the certificate it sends by matching the issuer names
     * the server advertises in its `CertificateRequest`. Ours advertises none,
     * because we trust everything — and a client with nothing to match against
     * may reasonably send nothing. Advertising the authority its own certificate
     * chains to gives it a name it recognises.
     *
     * This becomes a real question only now that the request is actually sent
     * (see [baseline]); before the fix there was no list to be empty.
     *
     * Two payoffs, and the second does not depend on the first. If it now
     * presents a certificate and the verdict changes, `-3` was the phone's fault
     * for never letting it identify itself. If it presents a certificate and the
     * verdict does not change, we still come home with a MIB2 head unit
     * certificate — issuer, validity window and subject naming — which no public
     * source records and which says who actually built the projection stack in
     * these cars.
     *
     * The certificate carrying that name is generated here and signed by our own
     * key. It is used as a *name to advertise*, never as a trust anchor: nothing
     * is validated against it, which is why the provider is built with peer
     * verification explicitly off rather than left to default from the list
     * being non-empty.
     */
    private fun googleIssuerAdvertised(): ProbeStep {
        val named = TestPki.authority(distinguishedName = CredentialProbe.GOOGLE_LINK_AUTHORITY_DN)
        return ProbeStep(
            id = "invite-car-certificate",
            varies = "we name the authority the car's own certificate chains to as one we accept",
            tells = "A TLS client sends nothing when it recognises none of the issuer names the " +
                "server advertises, and we advertise none. If naming that authority makes the car " +
                "present a certificate, its silence was our doing — and either the verdict moves, " +
                "or we come home with the first published MIB2 head unit certificate.",
            credentials = StaticCredentialProvider.of(
                name = "invite-car-certificate",
                leaf = phoneLeaf(),
                trustAnchors = listOf(named.certificate),
                // Advertised, never enforced. Verifying against it would reject
                // the very certificate the variant exists to collect.
                verifyPeer = false,
            ),
            provocation = HandshakeProvocation(lingerMillis = LINGER_MILLIS),
        )
    }

    /**
     * Refuse the session before TLS, by the one route the protocol defines.
     *
     * The version response carries a status word whose only documented values
     * are match and mismatch. Sending mismatch is the phone declaring the
     * session dead in the single message where it is entitled to. If the head
     * unit answers *that* with `0x0004`, then `0x0004` is a general error
     * channel reached without a certificate ever existing, and `-3` cannot mean
     * "certificate refused" — which is the strongest result this matrix can
     * produce, from the cheapest variant in it.
     *
     * If instead the handshake proceeds, the row still pays: it establishes that
     * this head unit does not read the status word, which nothing public says.
     */
    private fun versionStatusMismatch(): ProbeStep = ProbeStep(
        id = "version-status-mismatch",
        varies = "the version response says the versions do not match",
        tells = "Refuses before TLS, by the protocol's own means. A verdict message here would " +
            "mean 0x0004 is a general error channel and -3 says nothing about certificates. " +
            "A handshake here means the car ignores the status word.",
        credentials = phoneIdentity("version-status-mismatch"),
        provocation = HandshakeProvocation(
            versionAnswer = HandshakeProvocation.VersionAnswer.MISMATCH_STATUS,
            lingerMillis = LINGER_MILLIS,
        ),
    )

    /**
     * Does the verdict require a handshake that finished?
     *
     * Every measurement so far was taken after a completed handshake, so nothing
     * is known about what this unit says when TLS itself fails. Withholding the
     * certificate makes our own stack refuse at the `ServerHello` and emit a
     * fatal alert, deterministically and without assuming anything about the
     * peer — see [WithheldCredentials] for why that one arrangement stands in
     * for three separate ideas.
     *
     * Silence here bounds `-3` to sessions whose TLS completed, which is a real
     * constraint on its meaning. A verdict here removes even that bound.
     */
    private fun noCertificate(): ProbeStep = ProbeStep(
        id = "no-certificate",
        varies = "we present no certificate at all, so TLS cannot complete",
        tells = "The only failure here is of the handshake rather than of an identity. Silence " +
            "bounds -3 to completed handshakes; a verdict shows the car reports handshake " +
            "failures through the same message and the same code.",
        credentials = WithheldCredentials(),
        provocation = HandshakeProvocation(lingerMillis = LINGER_MILLIS),
    )

    /**
     * The other half of the version exchange, and a genuinely different check.
     *
     * [versionStatusMismatch] asks whether the head unit reads a field we set.
     * This asks whether it compares the numbers itself, by announcing a major
     * version it cannot speak while the status word still claims agreement. A
     * unit that only trusts the word will sail past this one and stop at the
     * other; a unit that only compares numbers does the reverse. Which of the
     * two it is has never been published for any head unit, and it costs one
     * connection to find out — with the verdict collected either way.
     */
    private fun versionMajorMismatch(): ProbeStep = ProbeStep(
        id = "version-major-mismatch",
        varies = "we announce a protocol major version the car cannot speak",
        tells = "Separates a car that reads our status word from one that compares the numbers. " +
            "Whichever it does, the row still records what it answers — and a code that differs " +
            "from the status-word row would mean the car distinguishes its own failure modes.",
        credentials = phoneIdentity("version-major-mismatch"),
        provocation = HandshakeProvocation(
            versionAnswer = HandshakeProvocation.VersionAnswer.MISMATCH_MAJOR,
            lingerMillis = LINGER_MILLIS,
        ),
    )

    /**
     * A message the head unit cannot parse, at the earliest point one exists.
     *
     * This is the "deliberately malformed message" idea, relocated. After the
     * handshake it could not reach the verdict; before it, it can. Four bytes
     * where six are defined is malformed without being absurd — it is exactly
     * the *request* format, so a lenient parser reads it and a strict one does
     * not, and which of those this unit is decides how it reports the fault.
     *
     * A distinct code here would separate "could not understand you" from
     * "understood you and refused you", which is the single most useful split
     * the code space could have.
     */
    private fun truncated(): ProbeStep = ProbeStep(
        id = "version-truncated",
        varies = "the version response is four bytes where six are defined",
        tells = "A malformed message before any certificate exists. A code here that differs " +
            "from -3 separates a parse failure from a policy failure, which is the split worth " +
            "the most. A code equal to -3 makes -3 generic beyond argument.",
        credentials = phoneIdentity("version-truncated"),
        provocation = HandshakeProvocation(
            versionAnswer = HandshakeProvocation.VersionAnswer.TRUNCATED,
            lingerMillis = LINGER_MILLIS,
        ),
    )

    /**
     * The identity a projection session would present, minted fresh per step.
     *
     * Deliberately identical to `ProjectionService.credentials()` in every
     * respect that the head unit can see: v1 structure, RSA-2048, a decade of
     * validity. Holding the certificate constant is what makes the rest of the
     * matrix a measurement of something other than the certificate — a variant
     * that also changed the identity would have nothing to attribute its result
     * to.
     */
    private fun phoneIdentity(name: String): StaticCredentialProvider =
        StaticCredentialProvider.of(name, phoneLeaf())

    private fun phoneLeaf(): TestPki.Leaf = TestPki.selfSigned(
        commonName = "openaap phone",
        version = TestPki.CertificateVersion.V1,
        validityDays = LONG_VALIDITY_DAYS,
    )

    private const val LONG_VALIDITY_DAYS: Long = 365 * 10
}
