/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

/**
 * One set of guesses about how a head unit expects a session to continue past
 * the certificate.
 *
 * The credential probe worked because it turned an argument into a measurement:
 * nine identities, one per connection, each varying exactly one thing. This is
 * the same instrument aimed at the next unknown.
 *
 * It exists because the first real projection attempt died 52 milliseconds
 * after the phone's first encrypted frame, with the head unit tearing down USB
 * accessory mode rather than answering — `EIO` on the accessory descriptor, not
 * a protocol rejection. That failure is consistent with several causes at once,
 * and testing them one at a time costs a trip to a car each. So they are
 * enumerated instead, and a dozen reconnections in one visit walk the whole set.
 *
 * Every field here is a documented **choice** rather than a measured fact. That
 * is precisely why each one is worth varying: nothing in the public record says
 * which reading is right, and this project's method is to stop guessing.
 */
public data class SessionVariant(
    /** Short stable name, used in reports. */
    val id: String,

    /** What this variant changes, in a phrase a person can act on. */
    val varies: String,

    /**
     * Whether control-channel messages carry the control flag.
     *
     * Our reading is that the flag means "read this id from the control
     * namespace rather than this channel's own", which makes it redundant on
     * channel 0 — its namespace is already the control one. Other
     * implementations set it on every control-channel message regardless. Both
     * readings are self-consistent and the plaintext handshake works either way,
     * because a head unit tracking the handshake by state need not look at
     * flags. It may well route by them once traffic is encrypted.
     */
    val controlFlagOnControlChannel: Boolean = false,

    /** Whether the phone asks what the car can do, or waits to be led. */
    val discovery: Discovery = Discovery.MINIMAL,

    /**
     * How long to wait after authentication before speaking.
     *
     * A head unit that has just finished a TLS handshake may still be wiring up
     * its own session, and a frame arriving into that window can be dropped or
     * can upset it. Costs nothing to try.
     */
    val quietMillisBeforeDiscovery: Long = 0,

    /**
     * Whether the phone encrypts immediately on authentication.
     *
     * The transition is our single most load-bearing assumption about the
     * protocol, and the failure appears on exactly the frame where it first
     * takes effect. A head unit that expected one more message in the clear
     * would behave precisely like this.
     */
    val encryptImmediately: Boolean = true,
) {
    /** Whether the phone opens the discovery exchange. */
    public enum class Discovery {
        /**
         * Ask, carrying model and maker.
         *
         * Deliberately not a "say more" variant as well: the request has
         * exactly two defined fields in every public account of the protocol,
         * so there is nothing further to volunteer and a variant that padded it
         * would be testing our imagination rather than the head unit.
         */
        MINIMAL,

        /**
         * Do not ask at all; let the head unit lead.
         *
         * Included because the direction of the discovery exchange is the one
         * piece of the sequence the public record genuinely disagrees about.
         * If a session survives longer when the phone stays quiet, the phone
         * was talking out of turn — and that would be a finding rather than a
         * fix.
         */
        NONE,
    }

    public companion object {

        /**
         * The variants still worth spending a connection on.
         *
         * ### What the first run settled, and why four variants are gone
         *
         * A field run of the original eight separated perfectly on one axis.
         * Every variant that set the control flag on channel 0 died before TLS
         * even began, four times out of four, with the head unit's next frame
         * arriving flagged in a way our decoder reads as ciphertext. Every
         * variant that did not set it reached at least the discovery request.
         * The separation was total, so the reading this implementation started
         * with was right and the flag does not belong on channel 0. Those four
         * are not kept as regression cases: a variant that is known to break the
         * session before it starts spends a connection to learn nothing.
         *
         * ### What that run opened up
         *
         * The listen-only variant reached authentication, sent **nothing at
         * all**, and the head unit still tore down USB accessory mode 2.3
         * seconds later. That rules out the content of our discovery request as
         * the cause — silence fails too. Whatever ends these sessions is either
         * something the head unit expects from us that we never send, or
         * something it sends that we never answer.
         *
         * So the matrix turns from varying what we say to observing what it
         * does. Most of what remains is a different amount of patience.
         */
        public fun matrix(): List<SessionVariant> = listOf(
            SessionVariant(
                id = "baseline",
                varies = "asks immediately: the reference failure",
            ),
            SessionVariant(
                id = "listen-only",
                varies = "says nothing at all, and records everything the car sends",
                discovery = Discovery.NONE,
            ),
            SessionVariant(
                id = "quiet-1s",
                varies = "waits 1s, then asks",
                quietMillisBeforeDiscovery = 1_000,
            ),
            SessionVariant(
                id = "quiet-3s",
                varies = "waits 3s, then asks — past the point silence alone died",
                quietMillisBeforeDiscovery = 3_000,
            ),
            SessionVariant(
                id = "plaintext-discovery",
                varies = "asks in the clear, never enabling encryption",
                encryptImmediately = false,
            ),
            SessionVariant(
                id = "plaintext-listen",
                varies = "stays in the clear and stays silent",
                discovery = Discovery.NONE,
                encryptImmediately = false,
            ),
        )
    }
}
