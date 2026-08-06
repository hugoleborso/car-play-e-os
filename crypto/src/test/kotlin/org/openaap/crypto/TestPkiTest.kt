/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto

import java.security.cert.CertificateParsingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pins what the platform will and will not let us mint.
 *
 * This exists because of a probe that was designed, written, and could never
 * have run. A certificate with a degenerate name is an obvious way to ask
 * whether a head unit's rejection comes from its parser or from its trust
 * policy — and the platform refuses to produce one, in two separate ways, both
 * of which surface only when the certificate is parsed rather than when it is
 * built.
 *
 * A probe that fails on the phone measures our own TLS library. There is one
 * head unit, one attempt per connection, and a person driving to a car; a
 * variant that cannot leave the phone is the most expensive kind of mistake
 * available here. So the constraints are asserted rather than remembered.
 */
class TestPkiTest {

    @Test
    fun `a certificate with no issuer name cannot be built at all`() {
        // BouncyCastle encodes it happily; the platform's own CertificateFactory
        // will not read it back. So there is no way to present one, whatever a
        // head unit might have made of it.
        val failure = assertThrows(CertificateParsingException::class.java) {
            TestPki.authority(distinguishedName = "")
        }
        assertEquals("Empty issuer DN not allowed in X509Certificates", failure.message)
    }

    @Test
    fun `a v1 certificate with no subject name cannot be built either`() {
        // And v1 is the structure that matters: it is what real Android Auto
        // endpoints present, so an empty subject on a v3 certificate would vary
        // the structure and the name together and could attribute neither.
        //
        // The platform permits an empty subject only on v3 with a critical
        // subject alternative name -- a shape no endpoint in this protocol has
        // ever been seen using.
        val failure = assertThrows(CertificateParsingException::class.java) {
            TestPki.selfSigned(
                commonName = "openaap phone",
                version = TestPki.CertificateVersion.V1,
                subjectName = "",
            )
        }
        assertEquals("Empty subject DN not allowed in v1 certificate", failure.message)
    }

    @Test
    fun `a subject that differs from the issuer is still buildable, which is what the knob is for`() {
        val leaf = TestPki.selfSigned(
            commonName = "openaap phone",
            version = TestPki.CertificateVersion.V1,
            subjectName = "CN=something else",
        )
        val certificate = leaf.chain.single()
        assertEquals("CN=something else", certificate.subjectX500Principal.name)
        assertEquals("CN=openaap phone", certificate.issuerX500Principal.name)
    }
}
