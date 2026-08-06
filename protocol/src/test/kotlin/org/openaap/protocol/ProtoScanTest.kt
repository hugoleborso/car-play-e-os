/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtoScanTest {

    @Test
    fun `reads a varint field`() {
        // field 1, wire type 0, value 3
        val fields = ProtoScan.scan(byteArrayOf(0x08, 0x03))
        assertEquals(1, fields.size)
        assertEquals(1, fields[0].number)
        assertEquals(0, fields[0].wireType)
        assertTrue(fields[0].readings.any { it == "varint 3" }, "${fields[0]}")
    }

    @Test
    fun `reads a string field and offers the text`() {
        // field 4, wire type 2, "Polo"
        val body = byteArrayOf(0x22, 0x04) + "Polo".toByteArray()
        val fields = ProtoScan.scan(body)
        assertEquals(4, fields[0].number)
        assertTrue(fields[0].readings.any { it == "string \"Polo\"" }, "${fields[0]}")
    }

    @Test
    fun `offers bool for zero and one, since an enum and a flag look identical`() {
        assertTrue(ProtoScan.scan(byteArrayOf(0x08, 0x01))[0].readings.any { it == "bool true" })
        assertTrue(ProtoScan.scan(byteArrayOf(0x08, 0x00))[0].readings.any { it == "bool false" })
        // A value that cannot be a bool must not be offered as one.
        assertTrue(ProtoScan.scan(byteArrayOf(0x08, 0x07))[0].readings.none { it.startsWith("bool") })
    }

    @Test
    fun `recognises a nested message`() {
        // field 1 containing { field 1 = 1 }
        val inner = byteArrayOf(0x08, 0x01)
        val body = byteArrayOf(0x0a, inner.size.toByte()) + inner
        val readings = ProtoScan.scan(body)[0].readings
        assertTrue(readings.any { it.startsWith("message {") }, "$readings")
    }

    @Test
    fun `says so rather than throwing when the body is not protobuf`() {
        // Field number 0 is illegal and is the cheapest signal that a body is
        // something other than what we assumed -- which is the whole point of
        // scanning an undocumented message.
        val fields = ProtoScan.scan(byteArrayOf(0x00, 0x01, 0x02))
        assertTrue(fields.any { it.readings.any { reading -> reading.contains("not protobuf") } }, "$fields")
    }

    @Test
    fun `says so rather than throwing when a length overruns`() {
        // field 1, wire type 2, declares 40 bytes, supplies 2
        val fields = ProtoScan.scan(byteArrayOf(0x0a, 0x28, 0x01, 0x02))
        assertTrue(fields.any { it.readings.any { reading -> reading.contains("overruns") } }, "$fields")
    }

    @Test
    fun `never throws on arbitrary input`() {
        // The bodies this will meet are undocumented by definition, so the one
        // behaviour it must never have is failing in the middle of writing a
        // report from a car park.
        val random = java.util.Random(20260806)
        repeat(2000) {
            val body = ByteArray(random.nextInt(48)).also(random::nextBytes)
            ProtoScan.scan(body)
            ProtoScan.describe(body)
            ProtoScan.hex(body)
        }
    }

    @Test
    fun `decodes a realistic discovery request`() {
        val request = org.openaap.protocol.proto.DiscoveryRequest.newBuilder()
            .setPhoneModel("FP6")
            .setPhoneMaker("Fairphone")
            .build()
        val described = ProtoScan.describe(request.toByteArray())
        assertTrue(described.contains("field 4"), described)
        assertTrue(described.contains("FP6"), described)
        assertTrue(described.contains("Fairphone"), described)
    }
}
