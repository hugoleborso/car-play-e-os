/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import org.openaap.crypto.CredentialProvider
import org.openaap.crypto.probe.CredentialProbe

/**
 * One deliberate deviation from a correct session, applied before the head unit
 * gives its verdict.
 *
 * "Before" is the whole design constraint and it is a measured one. The head
 * unit sends its verdict the moment the TLS handshake settles, with no message
 * from the phone in between — so the only inputs that can reach the verdict are
 * the version exchange, the handshake, and the certificate inside it. Nothing
 * the phone sends afterwards can change a number the head unit has already
 * sent, which is why this type has no field for misbehaving later.
 *
 * Each instance should differ from [NONE] in exactly one field. A variant that
 * changes two things cannot attribute whichever result it produces, and there is
 * no second attempt: one head unit, one provocation, one connection.
 */
public data class HandshakeProvocation(

    /** What the phone answers when the head unit asks which protocol it speaks. */
    val versionAnswer: VersionAnswer = VersionAnswer.AGREE,

    /**
     * Whether the phone asks the head unit for a certificate.
     *
     * A real session asks and, on the hardware measured here, gets nothing back.
     * Turning the request off is how we ask whether the refusal we receive is
     * the head unit reporting *our* certificate or *its own* inability to
     * satisfy ours.
     */
    val requestPeerCertificate: Boolean = true,

    /**
     * How long to keep reading after the verdict arrives instead of hanging up.
     *
     * The verdict has always been treated as the end of the probe, so nothing is
     * known about what follows it. A teardown request carries a reason of its
     * own, and a second code space would be worth as much as the first. Costs
     * nothing: the head unit drops the accessory a few seconds later regardless,
     * and that ends the read.
     */
    val lingerMillis: Long = 0,
) {

    /** What to put in the version response, which is the last message before TLS. */
    public enum class VersionAnswer {
        /** Echo the head unit's version, capped at ours, and report a match. */
        AGREE,

        /** The head unit's numbers, but the status word that says they do not match. */
        MISMATCH_STATUS,

        /** A major version it cannot speak, with the status word still saying match. */
        MISMATCH_MAJOR,

        /** Four bytes where the exchange defines six. */
        TRUNCATED,
    }

    public companion object {
        /** A session that misbehaves in no way at all. */
        public val NONE: HandshakeProvocation = HandshakeProvocation()
    }
}

/**
 * One thing to try on one connection, whatever the matrix it belongs to.
 *
 * The credential matrix and the status matrix vary different halves of the same
 * session — one the identity presented, the other the sequence around it — but
 * they are run identically: one per connection, position kept on disk, result
 * appended. Expressing both as this one type is what lets a single runner walk
 * either, rather than a second runner being copied from the first and then
 * drifting from it.
 */
public data class ProbeStep(
    /** Short stable name, used in reports and as the row label. */
    val id: String,

    /** What this step changes relative to the baseline, in a phrase. */
    val varies: String,

    /** What a difference in the result would prove. Written for a reader months later. */
    val tells: String,

    /** The identity presented. */
    val credentials: CredentialProvider,

    /** What else this step does differently. */
    val provocation: HandshakeProvocation = HandshakeProvocation.NONE,
)

/** Reads a credential probe as a step, so one runner can walk either matrix. */
public fun CredentialProbe.Probe.asProbeStep(): ProbeStep = ProbeStep(
    id = id,
    varies = dimension,
    tells = tells,
    credentials = credentials,
)
