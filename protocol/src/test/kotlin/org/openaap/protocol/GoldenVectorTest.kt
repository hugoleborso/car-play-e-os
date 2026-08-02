/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Checks this implementation against the shared golden vectors in
 * `testdata/frame-vectors.json`.
 *
 * The same file is read by the Python head-unit emulator's test suite. The two
 * implementations were written separately from the written wire-format spec, so
 * a disagreement between them means one has misread the spec -- which is
 * exactly the class of bug that would otherwise only show up as a head unit
 * silently refusing to talk to us, in a car, with no logs.
 */
class GoldenVectorTest {

    private val root: JsonObject by lazy {
        JsonParser.parseReader(vectorFile().reader()).asJsonObject
    }

    @TestFactory
    fun `encoder matches the golden vectors`(): List<DynamicTest> =
        root.getAsJsonArray("vectors").map { element ->
            val vector = element.asJsonObject
            DynamicTest.dynamicTest(vector["name"].asString) {
                val frames = FrameEncoder.encode(
                    channel = vector["channel"].asInt,
                    flags = vector["flags"].asInt,
                    payload = vector["payload"].asString.hexToBytes(),
                    fragmentSize = vector["fragmentSize"].asInt,
                )
                val expected = vector.getAsJsonArray("frames").map { it.asString }
                assertEquals(expected, frames.map { it.toHex() })
            }
        }

    @TestFactory
    fun `decoder round-trips the golden vectors`(): List<DynamicTest> =
        root.getAsJsonArray("vectors").map { element ->
            val vector = element.asJsonObject
            DynamicTest.dynamicTest(vector["name"].asString) {
                val assembled = replay(vector.getAsJsonArray("frames").map { it.asString })
                assertEquals(1, assembled.size)
                assertEquals(vector["channel"].asInt, assembled.single().channel)
                assertArrayEquals(vector["payload"].asString.hexToBytes(), assembled.single().payload)
            }
        }

    @TestFactory
    fun `interleaved frames reassemble independently`(): List<DynamicTest> =
        root.getAsJsonArray("assemblyVectors").map { element ->
            val vector = element.asJsonObject
            DynamicTest.dynamicTest(vector["name"].asString) {
                val assembled = replay(vector.getAsJsonArray("frames").map { it.asString })
                val expected = vector.getAsJsonArray("expected").map { it.asJsonObject }
                assertEquals(expected.size, assembled.size)
                expected.forEachIndexed { index, want ->
                    assertEquals(want["channel"].asInt, assembled[index].channel)
                    assertArrayEquals(want["payload"].asString.hexToBytes(), assembled[index].payload)
                }
            }
        }

    @TestFactory
    fun `malformed input is rejected`(): List<DynamicTest> =
        root.getAsJsonArray("rejectVectors").map { element ->
            val vector = element.asJsonObject
            DynamicTest.dynamicTest(vector["name"].asString) {
                assertThrows<FrameFormatException> {
                    replay(vector.getAsJsonArray("frames").map { it.asString })
                }
            }
        }

    private fun replay(wireFrames: List<String>): List<AssembledMessage> {
        val decoder = FrameDecoder()
        val assembler = MessageAssembler()
        val out = mutableListOf<AssembledMessage>()
        for (hex in wireFrames) {
            decoder.feed(hex.hexToBytes())
            while (true) {
                val frame = decoder.poll() ?: break
                assembler.accept(frame)?.let { out += it }
            }
        }
        return out
    }

    private fun vectorFile(): File {
        // Walk up from the module directory so the test works regardless of
        // which directory Gradle happens to run it from.
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "testdata/frame-vectors.json")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("testdata/frame-vectors.json not found above ${System.getProperty("user.dir")}")
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex string: $this" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
