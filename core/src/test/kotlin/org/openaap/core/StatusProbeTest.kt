/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.core

import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki
import org.openaap.protocol.VersionExchange
import org.openaap.transport.LoopbackTransport

/**
 * Calibrates the instrument that reads the head unit's refusal code.
 *
 * The whole output of the status matrix is a number a car sends and a claim
 * about what makes it change. Both halves can be wrong in ways that look
 * identical from a car park: a decoder that reports the same value whatever
 * arrives, or a provocation that never reached the wire. Neither is detectable
 * from the result — only from a peer whose answer is known in advance and a
 * record of what the phone actually sent.
 *
 * So every test here does one of two things: pins what the probe *reports* for
 * a verdict whose bytes are fixed, or pins what the probe *sends* for a
 * provocation whose effect is supposed to be visible. A trip to a car costs a
 * person an afternoon; a provocation that silently did nothing costs them the
 * afternoon and gives them a plausible number to believe.
 */
class StatusProbeTest {

    /** The eleven bytes a VW MIB2 actually sent, on 6 August 2026. */
    private val minusThree = byteArrayOf(
        0x08, 0xfd.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01,
    )

    private val phone = PhoneIdentity(model = "test", maker = "openaap")

    /** One probe run against one scripted head unit, with both ends torn down. */
    private fun exchange(
        provocation: HandshakeProvocation,
        verdict: ByteArray?,
        credentials: org.openaap.crypto.CredentialProvider = StaticCredentialProvider.of(
            "test phone",
            TestPki.selfSigned("openaap phone", version = TestPki.CertificateVersion.V1),
        ),
        offers: VersionExchange.Request = VersionExchange.Request(1, 6),
    ): Pair<HandshakeProbe.Result, ScriptedHeadUnit> {
        val (phoneEnd, headUnitEnd) = LoopbackTransport.pair()
        val car = ScriptedHeadUnit(headUnitEnd, verdict = verdict, offers = offers)
        val driver = thread(name = "scripted-head-unit") {
            car.run()
            car.close()
        }
        val result = try {
            HandshakeProbe(credentials, phone, provocation).run(phoneEnd, timeoutMillis = TIMEOUT_MILLIS)
        } finally {
            // Closed before the join, not after. The pair shares one closed flag,
            // so this is what releases a head unit still blocked on a read the
            // probe is never going to satisfy -- and joining first would wait
            // out the whole timeout to discover that.
            runCatching { phoneEnd.close() }
            driver.join(TIMEOUT_MILLIS)
        }
        return result to car
    }

    // ------------------------------------------------------------ the matrix

    @Test
    fun `the matrix documents every step and never repeats an id`() {
        val matrix = StatusProbe.matrix()
        assertTrue(matrix.size >= 6, "too few provocations to tell one kind of failure from another")
        assertEquals(matrix.size, matrix.map { it.id }.toSet().size, "step ids must be unique")
        matrix.forEach { step ->
            assertTrue(step.varies.isNotBlank(), "${step.id} does not say what it varies")
            assertTrue(step.tells.isNotBlank(), "${step.id} does not say what a result would prove")
        }
    }

    @Test
    fun `each step varies exactly one thing`() {
        // The reason there is one attempt per connection: a step that changed
        // two things could not attribute whichever result it produced, and there
        // is no second try to take the other one apart.
        //
        // Lingering is not a variation. It changes how long we listen, not what
        // the head unit is reacting to.
        StatusProbe.matrix().forEach { step ->
            val deviations = listOfNotNull(
                "version answer".takeIf {
                    step.provocation.versionAnswer != HandshakeProvocation.VersionAnswer.AGREE
                },
                "no certificate request".takeIf { !step.provocation.requestPeerCertificate },
            )
            assertTrue(deviations.size <= 1, "${step.id} changes ${deviations.size} things: $deviations")

            // The other half of "one thing": a step that provokes the sequence
            // must present the ordinary identity, or it is varying the sequence
            // and the certificate at once.
            if (deviations.isNotEmpty()) {
                assertEquals(
                    "CN=openaap phone",
                    step.credentials.chain.first().subjectX500Principal.name,
                    "${step.id} varies the sequence and the certificate together",
                )
            }
        }
    }

    @Test
    fun `exactly one step withholds a certificate, and it is the one that says so`() {
        val withheld = StatusProbe.matrix().filter { it.credentials.chain.isEmpty() }
        assertEquals(listOf("no-certificate"), withheld.map { it.id })
    }

    @Test
    fun `every step listens past the verdict`() {
        // Nobody has ever waited here. The verdict was always treated as the end
        // of the probe, so what a head unit says between refusing a session and
        // dropping the cable is unrecorded -- and a teardown reason would be a
        // second code space for free.
        StatusProbe.matrix().forEach { step ->
            assertTrue(
                step.provocation.lingerMillis > 0,
                "${step.id} hangs up as soon as it has its answer, and collects nothing after it",
            )
        }
    }

    // ---------------------------------------------------- reading the verdict

    @Test
    fun `the exact bytes a car sent are reported as bytes and as a number`() {
        val (result, _) = exchange(HandshakeProvocation.NONE, verdict = minusThree)

        assertTrue(result.verdictSeen, "the verdict message was not recorded as having arrived")
        assertEquals(-3L, result.verdictStatus)
        // The hex is the evidence. Every claim this matrix makes is a comparison
        // between two of these strings, so a report that carried only the
        // decoded number would be unfalsifiable by whoever reads it.
        assertEquals("08 fd ff ff ff ff ff ff ff ff 01", result.verdictBody)
        assertEquals(HandshakeProbe.Stage.HANDSHAKE_COMPLETE, result.stage)
        assertFalse(result.succeeded, "-3 is a refusal and must never read as success")
    }

    @Test
    fun `no verdict, a verdict with no status, and a status are three different reports`() {
        // The distinction that was collapsed once already, one level up. A head
        // unit that says nothing and one that says nothing *in particular* are
        // different findings, and only one of them is a protocol we understand.
        val (silent, _) = exchange(HandshakeProvocation(lingerMillis = 200), verdict = null)
        assertFalse(silent.verdictSeen)
        assertNull(silent.verdictStatus)
        assertEquals("no verdict", silent.verdictLabel())

        val (empty, _) = exchange(HandshakeProvocation.NONE, verdict = ByteArray(0))
        assertTrue(empty.verdictSeen, "an empty body is still a message")
        assertNull(empty.verdictStatus)
        assertEquals("verdict, no status", empty.verdictLabel())

        val (zero, _) = exchange(HandshakeProvocation.NONE, verdict = byteArrayOf(0x08, 0x00))
        assertEquals(0L, zero.verdictStatus)
        assertTrue(zero.succeeded, "status 0 is the only value that means the session may proceed")
    }

    @Test
    fun `a code nobody has seen is carried through rather than rounded to one we know`() {
        // The point of the matrix is codes we have never met. A decoder that
        // mapped an unfamiliar value onto a familiar one would erase exactly the
        // result worth having.
        val (result, _) = exchange(HandshakeProvocation.NONE, verdict = byteArrayOf(0x08, 0x2a))
        assertEquals(42L, result.verdictStatus)
        assertEquals("status=42", result.verdictLabel())
    }

    @Test
    fun `listening past the verdict records what follows it`() {
        val (result, car) = exchange(
            HandshakeProvocation(lingerMillis = 500),
            verdict = minusThree,
        )
        assertEquals(-3L, result.verdictStatus, "the verdict must survive whatever ends the read")
        assertTrue(
            result.transcript.any { it.contains("listening for") },
            "the probe hung up instead of listening: ${result.transcript}",
        )
        // The phone stays quiet while it listens. A probe that answered a
        // refusal with traffic would be measuring its own noise.
        assertTrue(car.afterVerdict.isEmpty(), "the phone spoke after being refused: ${car.afterVerdict}")
    }

    // ------------------------------------------- provocations reach the wire

    @Test
    fun `a correct session answers the version exchange with agreement`() {
        val (_, car) = exchange(HandshakeProvocation.NONE, verdict = minusThree)
        val answer = VersionExchange.parseResponse(car.versionResponses.single())
        assertEquals(VersionExchange.STATUS_MATCH, answer.status)
        assertEquals(1, answer.major)
    }

    @Test
    fun `the mismatch-status provocation sets the status word and nothing else`() {
        val (_, car) = exchange(
            HandshakeProvocation(
                versionAnswer = HandshakeProvocation.VersionAnswer.MISMATCH_STATUS,
                lingerMillis = 200,
            ),
            verdict = minusThree,
        )
        val answer = VersionExchange.parseResponse(car.versionResponses.single())
        assertEquals(VersionExchange.STATUS_MISMATCH, answer.status)
        // The numbers stay correct, or the variant would be testing two things
        // and could attribute neither.
        assertEquals(1, answer.major)
    }

    @Test
    fun `the mismatch-major provocation announces a version above the one offered`() {
        val (_, car) = exchange(
            HandshakeProvocation(
                versionAnswer = HandshakeProvocation.VersionAnswer.MISMATCH_MAJOR,
                lingerMillis = 200,
            ),
            verdict = minusThree,
            offers = VersionExchange.Request(1, 4),
        )
        val answer = VersionExchange.parseResponse(car.versionResponses.single())
        assertEquals(2, answer.major, "a mismatch the head unit might also speak proves nothing")
        assertEquals(VersionExchange.STATUS_MATCH, answer.status)
    }

    @Test
    fun `the truncated provocation really is short on the wire`() {
        val (_, car) = exchange(
            HandshakeProvocation(
                versionAnswer = HandshakeProvocation.VersionAnswer.TRUNCATED,
                lingerMillis = 200,
            ),
            verdict = minusThree,
        )
        val body = car.versionResponses.single()
        assertEquals(4, body.size, "the malformed message was not malformed")
        // Deliberately the *request* layout: malformed without being nonsense,
        // so a lenient parser reads it and a strict one does not.
        assertEquals(1, VersionExchange.parseRequest(body).major)
    }

    @Test
    fun `asking for a certificate is what makes the head unit send one`() {
        // The measured fact this rests on: the car presents nothing despite our
        // request. Before spending a connection asking whether that is our
        // doing, confirm the request is the thing that controls it.
        val (asked, _) = exchange(HandshakeProvocation.NONE, verdict = minusThree)
        assertTrue(asked.headUnitChain.isNotEmpty(), "a peer that was asked sent nothing")

        val (unasked, _) = exchange(
            HandshakeProvocation(requestPeerCertificate = false, lingerMillis = 200),
            verdict = minusThree,
        )
        assertTrue(
            unasked.headUnitChain.isEmpty(),
            "the certificate request was still sent when the variant said not to",
        )
        // And the rest of the session is unaffected, or the variant would be
        // measuring a broken handshake rather than a missing request.
        assertEquals(-3L, unasked.verdictStatus)
    }

    @Test
    fun `withholding our certificate stops the handshake instead of the identity`() {
        val (result, car) = exchange(
            HandshakeProvocation(lingerMillis = 500),
            verdict = minusThree,
            credentials = org.openaap.crypto.probe.WithheldCredentials(),
        )

        assertFalse(car.handshakeCompleted, "a server with no certificate completed a handshake")
        assertTrue(
            result.stage.ordinal < HandshakeProbe.Stage.HANDSHAKE_COMPLETE.ordinal,
            "stage ${result.stage} claims a handshake that did not happen",
        )
        assertFalse(result.verdictSeen, "no verdict can exist for a handshake that never settled")
        assertNotNull(result.failure, "the failure that ended the handshake was not recorded")
        assertTrue(
            result.transcript.any { it.contains("failed") },
            "the transcript does not say the handshake failed: ${result.transcript}",
        )
    }

    @Test
    fun `every identity in the matrix can actually be presented`() {
        // Calibration, not a hypothesis. A step whose certificate our own stack
        // cannot present measures our TLS library rather than the car, and it
        // measures it after somebody has driven to one. That is not
        // hypothetical: the empty-subject step was written, and the platform
        // refuses to parse the certificate it needs -- see TestPkiTest.
        StatusProbe.matrix()
            .filter { it.credentials.chain.isNotEmpty() }
            .forEach { step ->
                val (result, _) = exchange(
                    step.provocation.copy(lingerMillis = 100),
                    verdict = minusThree,
                    credentials = step.credentials,
                )
                assertEquals(
                    HandshakeProbe.Stage.HANDSHAKE_COMPLETE,
                    result.stage,
                    "${step.id} cannot complete a handshake with a peer that checks nothing: " +
                        result.failure,
                )
                assertEquals(-3L, result.verdictStatus, "${step.id} lost the verdict")
            }
    }

    // ------------------------------------------------------- what it all says

    @Test
    fun `the report says whether the codes discriminate, not just what they were`() {
        val generic = listOf(row("a", -3L), row("b", -3L), row("c", -3L))
        assertTrue(ProbeReport.discrimination(generic).contains("generic"))

        val discriminating = listOf(row("a", -3L), row("b", -7L))
        assertTrue(ProbeReport.discrimination(discriminating).contains("different codes"))

        val bounded = listOf(row("a", -3L), row("b", null, verdictSeen = false))
        assertTrue(ProbeReport.discrimination(bounded).contains("bounds"))
    }

    private fun row(
        name: String,
        status: Long?,
        verdictSeen: Boolean = true,
    ): HandshakeProbe.Result = HandshakeProbe.Result(
        credentialName = name,
        stage = HandshakeProbe.Stage.HANDSHAKE_COMPLETE,
        alert = null,
        failure = null,
        headUnitProtocol = null,
        negotiatedTls = null,
        negotiatedCipherSuite = null,
        headUnitChain = emptyList(),
        handshakeRounds = 1,
        verdictSeen = verdictSeen,
        verdictBody = null,
        verdictStatus = status,
        transcript = emptyList(),
    )

    private companion object {
        /** Long enough for a loopback handshake, short enough that a hang fails the build. */
        const val TIMEOUT_MILLIS = 15_000L
    }
}
