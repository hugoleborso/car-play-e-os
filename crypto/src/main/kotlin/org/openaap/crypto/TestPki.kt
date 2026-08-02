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
    ) {
        /** Issues a leaf certificate signed by this authority. */
        public fun issue(
            commonName: String,
            keyType: KeyType = KeyType.RSA_2048,
            validityDays: Long = 30,
            serverAuth: Boolean = true,
            clientAuth: Boolean = true,
        ): Leaf {
            val leafKeys = generateKeyPair(keyType)
            val certificate = buildCertificate(
                subject = commonName,
                subjectKeys = leafKeys,
                issuerName = this.certificate.subjectX500Principal.name,
                issuerKey = keyPair.private,
                isCa = false,
                validityDays = validityDays,
                serverAuth = serverAuth,
                clientAuth = clientAuth,
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

    /** Creates a self-signed CA. */
    public fun authority(commonName: String = "openaap test CA", keyType: KeyType = KeyType.RSA_2048): Authority {
        val keys = generateKeyPair(keyType)
        val certificate = buildCertificate(
            subject = commonName,
            subjectKeys = keys,
            issuerName = "CN=$commonName",
            issuerKey = keys.private,
            isCa = true,
            validityDays = 365,
            serverAuth = false,
            clientAuth = false,
        )
        return Authority(certificate, keys)
    }

    /** Creates a standalone self-signed certificate with no CA above it. */
    public fun selfSigned(
        commonName: String,
        keyType: KeyType = KeyType.RSA_2048,
        validityDays: Long = 30,
    ): Leaf {
        val keys = generateKeyPair(keyType)
        val certificate = buildCertificate(
            subject = commonName,
            subjectKeys = keys,
            issuerName = "CN=$commonName",
            issuerKey = keys.private,
            isCa = false,
            validityDays = validityDays,
            serverAuth = true,
            clientAuth = true,
        )
        return Leaf(listOf(certificate), keys.private)
    }

    private fun generateKeyPair(keyType: KeyType): KeyPair = when (keyType) {
        KeyType.RSA_2048 -> KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        KeyType.RSA_4096 -> KeyPairGenerator.getInstance("RSA").apply { initialize(4096, random) }.generateKeyPair()
        KeyType.EC_P256 -> KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
    }

    private fun buildCertificate(
        subject: String,
        subjectKeys: KeyPair,
        issuerName: String,
        issuerKey: PrivateKey,
        isCa: Boolean,
        validityDays: Long,
        serverAuth: Boolean,
        clientAuth: Boolean,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        // Backdate slightly: head units frequently boot with a wrong clock, and a
        // notBefore in the future is a classic cause of an unexplained handshake
        // failure in a car.
        val notBefore = Date(now - 24 * 3600 * 1000L)
        val notAfter = Date(now + validityDays * 24 * 3600 * 1000L)
        val serial = BigInteger(64, random)

        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuerName),
            serial,
            notBefore,
            notAfter,
            X500Name("CN=$subject"),
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

        val signatureAlgorithm = if (issuerKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(signatureAlgorithm).build(issuerKey)
        val holder = builder.build(signer)

        // Round-trip through the JCA CertificateFactory so the returned object is
        // a plain platform certificate and callers never need BouncyCastle on
        // their classpath -- Android in particular ships its own provider.
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(holder.encoded.inputStream()) as X509Certificate
    }
}

/** A [CredentialProvider] backed by generated test material. */
public class StaticCredentialProvider(
    override val name: String,
    override val chain: List<X509Certificate>,
    override val privateKey: PrivateKey,
    override val trustAnchors: List<X509Certificate> = emptyList(),
) : CredentialProvider {

    public companion object {
        public fun of(
            name: String,
            leaf: TestPki.Leaf,
            trustAnchors: List<X509Certificate> = emptyList(),
        ): StaticCredentialProvider =
            StaticCredentialProvider(name, leaf.chain, leaf.privateKey, trustAnchors)
    }
}
