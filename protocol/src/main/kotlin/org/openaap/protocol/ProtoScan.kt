/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

/**
 * Reads a protobuf message without knowing its schema.
 *
 * Protobuf's wire format is self-describing enough to walk blind: every field
 * carries its number and a wire type that says how long it is. What is lost
 * without a schema is only the *names* and the distinction between a few types
 * that share an encoding. That is enough to answer the question this exists for.
 *
 * It exists because of an eleven-byte message. A production head unit sends
 * message `0x0004` with an eleven-byte body, our schema declares one optional
 * field, and that field is absent — so the report said "empty verdict body"
 * about eleven bytes of content nobody has ever documented. A schema written
 * from the public record cannot show what the public record got wrong. This
 * can.
 *
 * Every field is rendered several ways at once, because an unknown varint is
 * equally likely to be an enum, a boolean or a length, and an unknown byte
 * string is as likely to be text as a nested message. Guessing one reading and
 * printing it would hide the others.
 */
public object ProtoScan {

    /** One field found in a message. */
    public data class Field(
        val number: Int,
        val wireType: Int,
        /** Every plausible reading, most likely first. */
        val readings: List<String>,
    ) {
        override fun toString(): String =
            "field $number (wire $wireType): " + readings.joinToString(" | ")
    }

    /**
     * Walks [body] and describes what is in it.
     *
     * Never throws. A body that is not protobuf at all, or is truncated,
     * produces the fields it managed to read plus a note — which is itself the
     * finding, because it says the message is not what we assumed.
     */
    public fun scan(body: ByteArray, depth: Int = 0): List<Field> {
        val fields = mutableListOf<Field>()
        var offset = 0
        while (offset < body.size) {
            val (tag, afterTag) = readVarint(body, offset) ?: run {
                fields += Field(-1, -1, listOf("stopped: truncated tag at byte $offset"))
                return fields
            }
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            if (number == 0) {
                fields += Field(-1, -1, listOf("stopped: field number 0 at byte $offset, not protobuf"))
                return fields
            }
            offset = afterTag

            when (wireType) {
                0 -> {
                    val (value, next) = readVarint(body, offset) ?: run {
                        fields += Field(number, 0, listOf("truncated varint"))
                        return fields
                    }
                    offset = next
                    fields += Field(number, 0, varintReadings(value))
                }

                1 -> {
                    if (offset + 8 > body.size) {
                        fields += Field(number, 1, listOf("truncated 64-bit"))
                        return fields
                    }
                    val raw = readLittleEndian(body, offset, 8)
                    offset += 8
                    fields += Field(
                        number,
                        1,
                        listOf("u64 $raw", "double ${Double.fromBits(raw)}"),
                    )
                }

                2 -> {
                    val (length, afterLength) = readVarint(body, offset) ?: run {
                        fields += Field(number, 2, listOf("truncated length"))
                        return fields
                    }
                    val end = afterLength + length.toInt()
                    if (length < 0 || end > body.size || end < afterLength) {
                        fields += Field(number, 2, listOf("length $length overruns the body"))
                        return fields
                    }
                    val slice = body.copyOfRange(afterLength, end)
                    offset = end
                    fields += Field(number, 2, bytesReadings(slice, depth))
                }

                5 -> {
                    if (offset + 4 > body.size) {
                        fields += Field(number, 5, listOf("truncated 32-bit"))
                        return fields
                    }
                    val raw = readLittleEndian(body, offset, 4)
                    offset += 4
                    fields += Field(
                        number,
                        5,
                        listOf("u32 $raw", "float ${Float.fromBits(raw.toInt())}"),
                    )
                }

                // 3 and 4 are the deprecated start/end group markers. Nothing in
                // this protocol should use them, and seeing one is a strong sign
                // the body is not protobuf.
                else -> {
                    fields += Field(number, wireType, listOf("unsupported wire type; stopping"))
                    return fields
                }
            }
        }
        return fields
    }

    /** A one-line-per-field rendering for a report. */
    public fun describe(body: ByteArray, indent: String = "    "): String {
        if (body.isEmpty()) return "${indent}(empty)"
        val fields = scan(body)
        if (fields.isEmpty()) return "$indent(no fields decoded from ${body.size} bytes)"
        return fields.joinToString("\n") { "$indent$it" }
    }

    /** Plain hex, for when the structure is not the interesting part. */
    public fun hex(body: ByteArray, limit: Int = 256): String {
        val shown = body.take(limit)
        return shown.joinToString(" ") { "%02x".format(it) } +
            if (body.size > limit) " … (${body.size} bytes total)" else ""
    }

    private fun varintReadings(value: Long): List<String> = buildList {
        add("varint $value")
        if (value == 0L || value == 1L) add("bool ${value == 1L}")
        // Signed varints are zigzag-encoded, so a small positive value may be a
        // small negative one. Worth showing whenever the two differ visibly.
        val zigzag = (value ushr 1) xor -(value and 1)
        if (zigzag != value) add("sint $zigzag")
    }

    private fun bytesReadings(slice: ByteArray, depth: Int): List<String> = buildList {
        val text = slice.toString(Charsets.UTF_8)
        val printable = text.isNotEmpty() && text.all { it == '\n' || it == '\t' || it.code in 0x20..0x7e }
        if (printable) add("string \"$text\"")

        // A length-delimited field is either bytes, a string, or a nested
        // message, and nothing on the wire distinguishes them. Try to walk it as
        // a message: if every byte accounts for itself, it almost certainly is
        // one. Bounded, because a pathological body could otherwise recurse.
        if (depth < MAX_DEPTH && slice.isNotEmpty()) {
            val nested = scan(slice, depth + 1)
            val clean = nested.isNotEmpty() && nested.none { it.number < 0 }
            if (clean) {
                add(
                    "message { " +
                        nested.joinToString("; ") { "${it.number}=${it.readings.firstOrNull()}" } +
                        " }"
                )
            }
        }
        if (!printable) add("bytes ${hex(slice, 32)}")
        add("${slice.size}B")
    }

    private fun readVarint(source: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < source.size) {
            val byte = source[offset].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            offset++
            if (byte and 0x80 == 0) return result to offset
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun readLittleEndian(source: ByteArray, start: Int, bytes: Int): Long {
        var result = 0L
        for (index in 0 until bytes) {
            result = result or ((source[start + index].toLong() and 0xff) shl (8 * index))
        }
        return result
    }

    private const val MAX_DEPTH = 3
}
