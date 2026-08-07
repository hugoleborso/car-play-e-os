/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v1CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Generates the throwaway PKI used by the emulator and the test suite.
 *
 * Every certificate produced here is minted at runtime, is valid for days, and
 * is issued by a CA whose private key exists only in memory. Nothing in this
 * file is, or is derived from, key material belonging to Google, to a head-unit
 * vendor, or to anybody else: that is the clean-room constraint the project
 * operates under, and it is the reason the certificates are generated rather
 * than checked in.
 *
 * A consequence worth stating plainly: a certificate from this CA will be
 * accepted by our own emulator, because the emulator is configured to trust it.
 * It says nothing about whether a real head unit will accept it. Determining
 * that is the job of the probe matrix, not of this class.
 */
public object TestPki {

    private val random = SecureRandom()

    /** A generated certificate authority, kept in memory. */
    public class Authority internal constructor(
        public val certificate: X509Certificate,
        internal val keyPair: KeyPair,
        /**
         * The name string this authority was built from, kept verbatim.
         *
         * Re-deriving it from the certificate is not equivalent: the platform
         * renders a name in RFC 2253 form, which reverses component order
         * relative to the sequence actually encoded. Feeding that back in as an
         * issuer name produces a DN that no longer matches the authority's
         * subject byte-for-byte, and chain validation then rejects a chain that
         * is in fact correct -- a failure that surfaces as an opaque keystore
         * error far from its cause.
         */
        internal val subjectName: String,
    ) {
        /** Issues a leaf certificate signed by this authority. */
        public fun issue(
            commonName: String,
            keyType: KeyType = KeyType.RSA_2048,
            validityDays: Long = 30,
            serverAuth: Boolean = true,
            clientAuth: Boolean = true,
            version: CertificateVersion = CertificateVersion.V3,
            validFromDaysAgo: Long = 1,
        ): Leaf {
            val leafKeys = generateKeyPair(keyType)
            val certificate = buildCertificate(
                subjectName = "CN=$commonName",
                subjectKeys = leafKeys,
                issuerName = subjectName,
                issuerKey = keyPair.private,
                isCa = false,
                validityDays = validityDays,
                serverAuth = serverAuth,
                clientAuth = clientAuth,
                version = version,
                validFromDaysAgo = validFromDaysAgo,
            )
            return Leaf(listOf(certificate, this.certificate), leafKeys.private)
        }
    }

    /** A generated leaf certificate and its private key. */
    public class Leaf internal constructor(
        public val chain: List<X509Certificate>,
        public val privateKey: PrivateKey,
    )

    public enum class KeyType { RSA_2048, RSA_4096, EC_P256 }

    /**
     * Creates a self-signed CA.
     *
     * [distinguishedName] overrides the default `CN=<commonName>` with a
     * complete name. The probe matrix needs it: reproducing a multi-component
     * authority name exactly is the only way to ask a head unit whether it pins
     * its trust anchor by name or by key.
     */
    public fun authority(
        commonName: String = "openaap test CA",
        keyType: KeyType = KeyType.RSA_2048,
        distinguishedName: String? = null,
    ): Authority {
        val name = distinguishedName ?: "CN=$commonName"
        val keys = generateKeyPair(keyType)
        val certificate = buildCertificate(
            subjectName = name,
            subjectKeys = keys,
            issuerName = name,
            issuerKey = keys.private,
            isCa = true,
            validityDays = 365,
            serverAuth = false,
            clientAuth = false,
        )
        return Authority(certificate, keys, name)
    }

    /**
     * Creates a standalone self-signed certificate with no CA above it.
     *
     * [subjectName] replaces the default `CN=<commonName>` on the subject only,
     * leaving the issuer as the common name. The status matrix needs a
     * certificate whose subject is an *empty* name, to ask whether a head unit's
     * rejection comes from its certificate parser or from its trust policy —
     * two findings that are indistinguishable while every identity we present is
     * well formed.
     *
     * The issuer is deliberately not overridden with it. **A certificate with an
     * empty issuer cannot be constructed at all:** BouncyCastle will build one,
     * and the platform's own `CertificateFactory` then refuses to parse it with
     * `Empty issuer DN not allowed in X509Certificates`. So a probe with both
     * names empty would fail on the phone before it ever reached a car, which is
     * a connection spent measuring our own TLS library. Measured, not assumed —
     * it is what the first version of this did.
     */
    public fun selfSigned(
        commonName: String,
        keyType: KeyType = KeyType.RSA_2048,
        validityDays: Long = 30,
        version: CertificateVersion = CertificateVersion.V3,
        validFromDaysAgo: Long = 1,
        subjectName: String? = null,
    ): Leaf {
        val keys = generateKeyPair(keyType)
        val certificate = buildCertificate(
            subjectName = subjectName ?: "CN=$commonName",
            subjectKeys = keys,
            issuerName = "CN=$commonName",
            issuerKey = keys.private,
            isCa = false,
            validityDays = validityDays,
            serverAuth = true,
            clientAuth = true,
            version = version,
            validFromDaysAgo = validFromDaysAgo,
        )
        return Leaf(listOf(certificate), keys.private)
    }

    /**
     * X.509 structure version.
     *
     * Version 1 exists here for one reason: the certificates real Android Auto
     * endpoints present are `Version: 1` -- no extensions, no SAN, no key usage,
     * no extended key usage. That is unusual enough in 2026 that several modern
     * TLS stacks reject them outright, and it cuts both ways for us. A head unit
     * whose parser was written in 2014 against v1 certificates may equally
     * choke on the v3 certificate a modern library produces by default. Being
     * able to present either is the difference between "the head unit rejected
     * our identity" and "the head unit could not parse our certificate", which
     * are very different findings.
     */
    public enum class CertificateVersion { V1, V3 }

    private fun generateKeyPair(keyType: KeyType): KeyPair = when (keyType) {
        KeyType.RSA_2048 -> KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        KeyType.RSA_4096 -> KeyPairGenerator.getInstance("RSA").apply { initialize(4096, random) }.generateKeyPair()
        KeyType.EC_P256 -> KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
    }

    private fun buildCertificate(
        subjectName: String,
        subjectKeys: KeyPair,
        issuerName: String,
        issuerKey: PrivateKey,
        isCa: Boolean,
        validityDays: Long,
        serverAuth: Boolean,
        clientAuth: Boolean,
        version: CertificateVersion = CertificateVersion.V3,
        validFromDaysAgo: Long = 1,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        // Backdate: head units frequently boot with a wrong clock, and a
        // notBefore in the future is a classic cause of an unexplained handshake
        // failure in a car. validFromDaysAgo is a knob rather than a constant so
        // the probe matrix can deliberately produce not-yet-valid and expired
        // certificates and see whether a head unit notices.
        val notBefore = Date(now - validFromDaysAgo * 24 * 3600 * 1000L)
        val notAfter = Date(now + validityDays * 24 * 3600 * 1000L)
        val serial = BigInteger(64, random)
        val signatureAlgorithm = if (issuerKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"

        if (version == CertificateVersion.V1) {
            val v1 = JcaX509v1CertificateBuilder(
                X500Name(issuerName),
                serial,
                notBefore,
                notAfter,
                X500Name(subjectName),
                subjectKeys.public,
            )
            return toPlatformCertificate(
                v1.build(JcaContentSignerBuilder(signatureAlgorithm).build(issuerKey)).encoded
            )
        }

        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuerName),
            serial,
            notBefore,
            notAfter,
            X500Name(subjectName),
            subjectKeys.public,
        )

        val utils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            utils.createSubjectKeyIdentifier(subjectKeys.public),
        )
        if (isCa) {
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
            )
        } else {
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            val purposes = buildList {
                if (serverAuth) add(KeyPurposeId.id_kp_serverAuth)
                if (clientAuth) add(KeyPurposeId.id_kp_clientAuth)
            }
            if (purposes.isNotEmpty()) {
                builder.addExtension(
                    Extension.extendedKeyUsage,
                    false,
                    ExtendedKeyUsage(purposes.toTypedArray()),
                )
            }
        }

        val signer = JcaContentSignerBuilder(signatureAlgorithm).build(issuerKey)
        return toPlatformCertificate(builder.build(signer).encoded)
    }

    /**
     * Round-trips DER through the JCA factory so the returned object is a plain
     * platform certificate and callers never need BouncyCastle on their
     * classpath -- Android in particular ships its own provider.
     */
    private fun toPlatformCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
}

/** A [CredentialProvider] backed by generated test material. */
public class StaticCredentialProvider(
    override val name: String,
    override val chain: List<X509Certificate>,
    override val privateKey: PrivateKey,
    override val trustAnchors: List<X509Certificate> = emptyList(),
    /**
     * Whether [trustAnchors] are enforced, or merely advertised.
     *
     * These are two different jobs that TLS runs through one list. As the TLS
     * server we name our acceptable issuers in the `CertificateRequest`, and a
     * client picks the certificate that matches one of those names — so the list
     * decides both *what we will accept* and *what the peer is willing to offer
     * us*. Defaulting the two together is right almost everywhere and wrong for
     * one measurement: naming an authority to invite a peer's certificate out of
     * it, while still accepting whatever arrives, needs them separated.
     */
    override val verifyPeer: Boolean = trustAnchors.isNotEmpty(),
) : CredentialProvider {

    public companion object {
        public fun of(
            name: String,
            leaf: TestPki.Leaf,
            trustAnchors: List<X509Certificate> = emptyList(),
            verifyPeer: Boolean = trustAnchors.isNotEmpty(),
        ): StaticCredentialProvider =
            StaticCredentialProvider(name, leaf.chain, leaf.privateKey, trustAnchors, verifyPeer)
    }
}
