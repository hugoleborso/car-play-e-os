/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.crypto

import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Supplies the key material an AAP session presents and the trust policy it
 * applies to its peer.
 *
 * This interface is the whole point of the crypto module. Android Auto's
 * session is protected by mutual TLS, and the phone is the side that presents
 * a certificate. A production Android Auto phone presents a certificate chained
 * to a Google-operated CA; we have no such certificate and, per the project's
 * clean-room rule, will not obtain one by extracting it from Google's app.
 *
 * What we can do is make the credential a first-class, swappable input. That
 * turns an unanswerable question -- "would a head unit accept a certificate we
 * are allowed to generate?" -- into an experiment: present a series of
 * different credentials to a real head unit and record which handshake step it
 * rejects. See `docs/03-trust-model.md` and the probe matrix in
 * [org.openaap.crypto.probe].
 */
public interface CredentialProvider {

    /** Identifies this credential in logs and probe reports. */
    public val name: String

    /** The certificate chain presented to the peer, leaf first. */
    public val chain: List<X509Certificate>

    /** The private key matching the leaf of [chain]. */
    public val privateKey: PrivateKey

    /**
     * Certificates trusted when validating the peer.
     *
     * Empty means "trust anything", which is the right default when probing:
     * we want the peer's opinion of *us* to be the only thing that can fail, so
     * that a rejection unambiguously locates the wall.
     */
    public val trustAnchors: List<X509Certificate>

    /** Whether to fail the handshake when the peer's certificate does not chain to [trustAnchors]. */
    public val verifyPeer: Boolean get() = trustAnchors.isNotEmpty()

    public fun keyManagers(): Array<KeyManager> {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("openaap", privateKey, PASSWORD, chain.toTypedArray())
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, PASSWORD)
        return factory.keyManagers
    }

    public fun trustManagers(): Array<TrustManager> {
        if (!verifyPeer) return arrayOf(AcceptAllTrustManager(trustAnchors))
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            trustAnchors.forEachIndexed { index, certificate -> setCertificateEntry("anchor-$index", certificate) }
        }
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(keyStore)
        return factory.trustManagers
    }

    public companion object {
        internal val PASSWORD: CharArray = "openaap".toCharArray()
    }
}

/**
 * Records the peer chain but never rejects it.
 *
 * Used when the peer's identity is not what we are testing. Accepting any peer
 * certificate is not a security decision here: an AAP session is a local link
 * to a head unit the user physically owns, and refusing to complete a handshake
 * would only hide the information we are trying to collect. The chain we saw is
 * still captured so diagnostics can report what the head unit presented.
 */
public class AcceptAllTrustManager(
    private val anchors: List<X509Certificate> = emptyList(),
) : X509TrustManager {

    /** The chain the peer presented on the most recent handshake, if any. */
    @Volatile
    public var observedPeerChain: List<X509Certificate> = emptyList()
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        observedPeerChain = chain?.toList() ?: emptyList()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        observedPeerChain = chain?.toList() ?: emptyList()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = anchors.toTypedArray()
}
