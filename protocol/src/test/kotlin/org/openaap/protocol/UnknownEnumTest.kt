/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.openaap.protocol.proto.AuthSucceeded

/**
 * Pins the behaviour that made this project publish a wrong result.
 *
 * A real head unit answered authentication with `status = -3`. Our schema
 * declares that field as a proto2 enum with members 0 and 1, and proto2 treats
 * an unknown enum value as an *unknown field*: the value is preserved on the
 * wire but `hasStatus()` reports false, exactly as it would for a field that was
 * never sent. Code that read "absent" as "nothing to object to" therefore turned
 * an explicit rejection into an acceptance, nine times out of nine, and the
 * report said the car had accepted certificates it had refused.
 *
 * The lesson generalises past this one field: any branch on an enum received
 * from a peer has the same hazard, because a closed enum cannot represent what
 * the peer actually said.
 */
class UnknownEnumTest {

    /** The eleven bytes a VW MIB2 actually sent, on 6 August 2026. */
    private val fromTheCar = byteArrayOf(
        0x08, 0xfd.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01,
    )

    @Test
    fun `an unknown enum value reads as an absent field`() {
        val parsed = AuthSucceeded.parseFrom(fromTheCar)
        // This is the trap, asserted so nobody removes the workaround believing
        // the generated code will tell them the truth.
        assertFalse(
            parsed.hasResult(),
            "proto2 hid an out-of-range enum; if this ever fails, the runtime changed",
        )
    }

    @Test
    fun `reading it as a raw varint recovers what the car said`() {
        assertEquals(-3L, AuthVerdict.statusOf(fromTheCar))
    }

    @Test
    fun `a genuine acceptance is still read as one`() {
        val ok = AuthSucceeded.newBuilder()
            .setResult(org.openaap.protocol.proto.ResultCode.RESULT_OK)
            .build()
        assertEquals(0L, AuthVerdict.statusOf(ok.toByteArray()))
    }

    @Test
    fun `an empty body reports no status rather than a zero`() {
        // Absent and zero are different claims and must not collapse: one is a
        // head unit that said nothing, the other is one that said OK.
        assertEquals(null, AuthVerdict.statusOf(ByteArray(0)))
    }
}
