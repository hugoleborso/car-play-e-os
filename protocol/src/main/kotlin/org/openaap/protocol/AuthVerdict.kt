/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

/**
 * Reads the head unit's verdict on the session as the number it actually is.
 *
 * This exists because of a wrong published result. A VW MIB2 answered
 * authentication with `status = -3`; our schema declares that field as a proto2
 * enum whose members are 0 and 1; and proto2 stores an out-of-range enum value
 * as an *unknown field*, so the generated `hasResult()` reports false — exactly
 * as it would for a field the peer never sent. Code that treated "absent" as
 * "nothing to object to" turned an explicit rejection into an acceptance, nine
 * times out of nine, and the project reported that a production head unit
 * accepted certificates it had in fact refused.
 *
 * A closed enum cannot represent what a peer actually said, and this is a
 * protocol reconstructed from observation: values outside our enumeration are
 * not an edge case here, they are the normal way of discovering that the
 * enumeration is incomplete. So the verdict is read as a raw varint and the
 * number is carried around unmodified.
 *
 * The distinction between *absent* and *zero* is preserved deliberately. One is
 * a head unit that said nothing; the other is one that said OK. Collapsing them
 * is the specific mistake that caused the original error.
 */
public object AuthVerdict {

    /** Field number of the status in the auth-complete message. */
    private const val STATUS_FIELD = 1

    /** The value a head unit sends when it is content. */
    public const val OK: Long = 0

    /**
     * The status the head unit reported, or `null` if it sent none.
     *
     * Never throws: this parses a message from a peer whose schema we
     * reconstructed, so a body that is not shaped as we expect is a finding
     * rather than an error.
     */
    public fun statusOf(body: ByteArray): Long? =
        ProtoScan.scan(body)
            .firstOrNull { it.number == STATUS_FIELD && it.wireType == 0 }
            ?.readings
            ?.firstOrNull { it.startsWith("varint ") }
            ?.removePrefix("varint ")
            ?.toLongOrNull()

    /** Whether a body says the session may proceed. */
    public fun accepted(body: ByteArray): Boolean = statusOf(body) == OK

    /**
     * A short account of a verdict for a report.
     *
     * Names the values seen in the field rather than only the ones our schema
     * declares, because the observed ones are what someone reading a report in
     * a car park will actually meet.
     */
    public fun describe(status: Long?): String = when (status) {
        null -> "no status field: the head unit sent an auth message with nothing in it"
        OK -> "accepted"
        1L -> "refused (1)"
        -3L -> "refused (-3), the value a VW MIB2 returns for every identity tried so far"
        else -> "refused ($status), a value not seen before — worth reporting"
    }
}
