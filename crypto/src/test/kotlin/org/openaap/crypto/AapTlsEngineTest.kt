/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto

import javax.net.ssl.SSLException
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AapTlsEngineTest {

    /**
     * Drives two engines against each other the way the session layer does:
     * one message of ciphertext at a time, no socket in between.
     */
    private fun handshake(phone: AapTlsEngine, headUnit: AapTlsEngine, maxRounds: Int = 30) {
        // The head unit opens the session; the phone waits for it to speak.
        phone.begin()
        var toPhone = headUnit.begin()
        var toHeadUnit = ByteArray(0)

        // Strict ping-pong that never discards a flight, including the records a
        // TLS 1.3 peer emits after it considers the handshake finished.
        var rounds = 0
        while (rounds++ < maxRounds) {
            when {
                toPhone.isNotEmpty() -> {
                    toHeadUnit = phone.handshake(toPhone)
                    toPhone = ByteArray(0)
                }
                toHeadUnit.isNotEmpty() -> {
                    toPhone = headUnit.handshake(toHeadUnit)
                    toHeadUnit = ByteArray(0)
                }
                else -> return
            }
        }
        error("handshake did not settle in $maxRounds rounds")
    }

    @Test
    fun `phone and head unit complete a mutually authenticated handshake`() {
        val ca = TestPki.authority()
        val phoneCreds = StaticCredentialProvider.of("phone", ca.issue("openaap phone"))
        val headUnitCreds = StaticCredentialProvider.of("head-unit", ca.issue("emulated head unit"))

        val phone = AapTlsEngine(TlsRole.SERVER, phoneCreds)
        val headUnit = AapTlsEngine(TlsRole.CLIENT, headUnitCreds)

        handshake(phone, headUnit)

        assertTrue(phone.handshakeComplete, "phone handshake incomplete")
        assertTrue(headUnit.handshakeComplete, "head unit handshake incomplete")
        assertNotNull(phone.negotiatedProtocol)
        assertNotNull(phone.negotiatedCipherSuite)
    }

    @Test
    fun `application data survives the round trip in both directions`() {
        val ca = TestPki.authority()
        val phone = AapTlsEngine(TlsRole.SERVER, StaticCredentialProvider.of("phone", ca.issue("phone")))
        val headUnit = AapTlsEngine(TlsRole.CLIENT, StaticCredentialProvider.of("hu", ca.issue("hu")))
        handshake(phone, headUnit)

        val request = "service discovery request".toByteArray()
        assertArrayEquals(request, phone.unwrap(headUnit.wrap(request)))

        // A video keyframe is far larger than one TLS record, so this exercises
        // the multi-record path in both wrap and unwrap.
        val keyframe = Random(7).nextBytes(200_000)
        assertArrayEquals(keyframe, headUnit.unwrap(phone.wrap(keyframe)))
    }

    @Test
    fun `a record split across two deliveries is buffered until complete`() {
        val ca = TestPki.authority()
        val phone = AapTlsEngine(TlsRole.SERVER, StaticCredentialProvider.of("phone", ca.issue("phone")))
        val headUnit = AapTlsEngine(TlsRole.CLIENT, StaticCredentialProvider.of("hu", ca.issue("hu")))
        handshake(phone, headUnit)

        val payload = Random(8).nextBytes(4096)
        val ciphertext = phone.wrap(payload)
        val split = ciphertext.size / 3

        val first = headUnit.unwrap(ciphertext.copyOfRange(0, split))
        assertEquals(0, first.size, "a partial record must not yield plaintext")
        val rest = headUnit.unwrap(ciphertext.copyOfRange(split, ciphertext.size))
        assertArrayEquals(payload, rest)
    }

    @Test
    fun `a head unit that pins a CA rejects a phone certificate from elsewhere`() {
        // This is the shape of the wall the project is up against, reproduced in
        // miniature: the head unit trusts exactly one authority, and the phone
        // holds a certificate from a different one. If real head units behave
        // like this, a clean-room phone side cannot connect without the
        // manufacturer's key material.
        val pinnedCa = TestPki.authority("pinned authority")
        val ourCa = TestPki.authority("openaap test CA")

        val phone = AapTlsEngine(TlsRole.SERVER, StaticCredentialProvider.of("phone", ourCa.issue("phone")))
        val headUnit = AapTlsEngine(
            TlsRole.CLIENT,
            StaticCredentialProvider.of(
                "strict head unit",
                pinnedCa.issue("head unit"),
                trustAnchors = listOf(pinnedCa.certificate),
            ),
        )

        assertThrows(SSLException::class.java) { handshake(phone, headUnit) }
        assertFalse(headUnit.handshakeComplete)
    }

    @Test
    fun `a lenient head unit accepts a self-signed phone certificate`() {
        // The other possibility, and the one worth testing on real hardware: a
        // head unit that requests a certificate but never validates the chain.
        val phone = AapTlsEngine(
            TlsRole.SERVER,
            StaticCredentialProvider.of("phone", TestPki.selfSigned("openaap phone")),
        )
        val headUnit = AapTlsEngine(
            TlsRole.CLIENT,
            StaticCredentialProvider.of("lenient head unit", TestPki.selfSigned("head unit")),
        )

        handshake(phone, headUnit)

        assertTrue(phone.handshakeComplete)
        assertTrue(headUnit.handshakeComplete)
        assertEquals(1, headUnit.peerChain.size)
        assertEquals("CN=openaap phone", headUnit.peerChain.first().subjectX500Principal.name)
    }

    @Test
    fun `the certificate request is what decides whether the peer identifies itself`() {
        // Calibration for the status matrix. A real MIB2 presents no certificate
        // of its own even though we ask for one, and one reading of its refusal
        // is that it is reporting *that* rather than judging ours. Before
        // spending a connection on the question, the request has to be shown to
        // be the thing that controls the answer -- against a peer that certainly
        // has a certificate to send.
        val ca = TestPki.authority()

        val asked = AapTlsEngine(TlsRole.SERVER, StaticCredentialProvider.of("phone", ca.issue("phone")))
        handshake(asked, AapTlsEngine(TlsRole.CLIENT, StaticCredentialProvider.of("hu", ca.issue("hu"))))
        assertTrue(
            asked.peerChain.isNotEmpty(),
            "a peer with a certificate sent none although it was asked",
        )

        val unasked = AapTlsEngine(
            TlsRole.SERVER,
            StaticCredentialProvider.of("phone", ca.issue("phone")),
            requestPeerCertificate = false,
        )
        handshake(unasked, AapTlsEngine(TlsRole.CLIENT, StaticCredentialProvider.of("hu", ca.issue("hu"))))
        assertTrue(unasked.handshakeComplete, "dropping the request broke the handshake")
        assertTrue(unasked.peerChain.isEmpty(), "a certificate arrived that was never requested")
    }

    @Test
    fun `elliptic curve credentials negotiate as well as RSA`() {
        val ca = TestPki.authority("ec ca", TestPki.KeyType.EC_P256)
        val phone = AapTlsEngine(
            TlsRole.SERVER,
            StaticCredentialProvider.of("phone", ca.issue("phone", TestPki.KeyType.EC_P256)),
        )
        val headUnit = AapTlsEngine(
            TlsRole.CLIENT,
            StaticCredentialProvider.of("hu", ca.issue("hu", TestPki.KeyType.EC_P256)),
        )

        handshake(phone, headUnit)
        assertTrue(phone.handshakeComplete)
    }

    @Test
    fun `TLS 1_2 only peers still negotiate`() {
        // MIB2 hardware is from 2017 and its TLS stack long predates 1.3.
        val ca = TestPki.authority()
        val phone = AapTlsEngine(TlsRole.SERVER, StaticCredentialProvider.of("phone", ca.issue("phone")))
        val headUnit = AapTlsEngine(
            TlsRole.CLIENT,
            StaticCredentialProvider.of("hu", ca.issue("hu")),
            protocols = listOf("TLSv1.2"),
        )

        handshake(phone, headUnit)
        assertEquals("TLSv1.2", headUnit.negotiatedProtocol)
    }
}
