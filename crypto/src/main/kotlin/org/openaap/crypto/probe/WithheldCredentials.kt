/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto.probe

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import java.security.KeyStore
import org.openaap.crypto.CredentialProvider

/**
 * A credential that exists and is never offered, so the TLS handshake cannot
 * complete.
 *
 * The phone is the TLS server, so it is the side that must produce a
 * certificate before any cipher suite can be agreed. A server with none refuses
 * at the `ServerHello`: it can satisfy no suite the client offered, and its
 * stack emits a fatal `handshake_failure` rather than a certificate the peer
 * then judges. That is the point — this provokes a failure of the *handshake*
 * rather than a failure of an *identity*, which is the one distinction a probe
 * that only ever varies certificates can never draw.
 *
 * It is deliberately one probe and not three. "Offer no mutually acceptable
 * cipher suite", "present no client certificate" and "present no server
 * certificate" all arrive at the same peer-visible event — a fatal alert before
 * the handshake settles — and the first of them cannot even be arranged
 * reliably, because it depends on guessing a suite list the head unit does not
 * publish. Withholding the certificate produces that event deterministically,
 * with no assumption about the peer at all. Spending a connection on each of
 * three routes to one observation is exactly the mistake this project cannot
 * afford.
 *
 * A key is still generated, because [CredentialProvider] requires one and code
 * that returns a null key would be a trap for the next reader. It is never put
 * into a key store and therefore never presented. Elliptic curve rather than
 * RSA purely because generating it is fast and nothing will ever use it.
 */
public class WithheldCredentials : CredentialProvider {

    override val name: String = "no-certificate"

    override val chain: List<X509Certificate> = emptyList()

    override val privateKey: PrivateKey by lazy {
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
            .generateKeyPair()
            .private
    }

    override val trustAnchors: List<X509Certificate> = emptyList()

    /**
     * Key managers over an empty key store.
     *
     * Returning an empty array instead would let the platform fall back to its
     * own default key managers on some providers, which would quietly restore a
     * certificate and turn this probe into a copy of the baseline — a variant
     * that measures nothing while looking like it measured something.
     */
    override fun keyManagers(): Array<KeyManager> {
        val empty = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(empty, CredentialProvider.PASSWORD)
        return factory.keyManagers
    }
}
