package com.openemux.mobile.share

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS 1.3 only, no CA and no host name check: the client pins the server's
 * certificate fingerprint inside the trust manager, so a mismatch fails the
 * handshake itself, before a single byte of HTTP is sent (spec §7).
 */
object Tls {
    /** What the server presents: the identity's key and self-signed certificate. */
    fun serverContext(privateKey: PrivateKey, certificate: X509Certificate): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(SingleKeyManager(privateKey, certificate)), null, null) }

    /** A client context checking the server against [pin]; see [PinningTrustManager]. */
    fun clientContext(pin: PinningTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pin), null) }

    /** Restricts [socket] to TLS 1.3, whatever the platform would also offer. */
    fun restrict(socket: SSLSocket) {
        socket.enabledProtocols = arrayOf(ShareProtocol.TLS_PROTOCOL)
    }
}

/** Always answers with the one key and certificate the device has. */
private class SingleKeyManager(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getCertificateChain(alias: String?) = arrayOf(certificate)
    override fun getPrivateKey(alias: String?) = key

    private companion object {
        const val ALIAS = "openemux-share"
    }
}

/**
 * Compares the SHA-256 of the server's DER certificate with [expected] in
 * constant time and throws on mismatch, which aborts the handshake. With
 * nothing expected (a typed address: trust on first use) it accepts and
 * records what it saw in [observed].
 */
class PinningTrustManager(private val expected: String?) : X509ExtendedTrustManager() {
    @Volatile var observed: String? = null
        private set
    @Volatile var mismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val fp = DeviceIdentity.fingerprint(leaf)
        observed = fp
        if (expected != null && !Verification.constantTimeEquals(fp, expected)) {
            mismatch = true
            throw CertificateException("fingerprint mismatch")
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    // There is no client certificate in the protocol.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
