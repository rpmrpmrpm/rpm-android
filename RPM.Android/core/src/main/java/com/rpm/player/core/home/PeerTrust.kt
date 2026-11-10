package com.rpm.player.core.home

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for the cloud and the home network in one client. A computer serves TLS over a self-signed
 * certificate made from its own key, at a bare IP address; for an address pinned here, the
 * certificate's public key must be byte for byte the key the computer advertised (`PeerCertificate`
 * does the same), and names, chains and dates are not looked at. Every other host is checked the
 * ordinary way, so a computer's 302 to the cloud's storage is checked against the system's
 * authorities, not against the pin.
 */
class PeerTrust {

    /** Address (an IPv4 literal) and port, as `address:port`, to the key advertised there. The port is part of
     * the pin because a NAS serves each of its libraries with its own key on its own port, all at one address. */
    private val pins = ConcurrentHashMap<String, ByteArray>()

    fun pin(address: String, port: Int, publicKey: ByteArray) {
        pins["$address:$port"] = publicKey
    }

    private val system: X509ExtendedTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()

    private fun pinned(chain: Array<out X509Certificate>, address: String?, port: Int): Boolean {
        val key = address?.let { pins["$it:$port"] } ?: return false
        if (chain.isEmpty() || !chain[0].publicKey.encoded.contentEquals(key)) {
            throw CertificateException("the computer at $address did not present the key it advertised")
        }
        return true
    }

    private val trust = object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) {
            if (!pinned(chain, socket.inetAddress?.hostAddress, socket.port)) system.checkServerTrusted(chain, authType, socket)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) {
            if (!pinned(chain, engine.peerHost, engine.peerPort)) system.checkServerTrusted(chain, authType, engine)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            system.checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) =
            system.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) =
            system.checkClientTrusted(chain, authType, engine)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    /** A pinned address is the computer whose key was just checked; its certificate names the device
     * id, not the address. */
    private val names = HostnameVerifier { host, session ->
        pins.keys.any { it.startsWith("$host:") } || HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }

    /** [base] with this trust. */
    fun client(base: OkHttpClient): OkHttpClient {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return base.newBuilder().sslSocketFactory(tls.socketFactory, trust).hostnameVerifier(names).build()
    }
}
