package com.openemux.mobile.share

import com.openemux.mobile.share.http.HttpCodec
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** An offer the receiver took: its capability id and the code this side computed. */
data class OutgoingOffer(val offerId: String, val code: String)

/**
 * The sending side, talking to one receiver. Every request opens its own
 * connection (spec §7 allows it) and pins the certificate during the
 * handshake; the connection is closed whatever happens. [peerFingerprint] is
 * the fingerprint the receiver presented, the one the code is built from: the
 * expected one, or with none (a typed address) the first one seen.
 *
 * Failures are [ShareError]s: the server's own (with its extra fields, such as
 * the version ranges), `fingerprint_mismatch` when the pin fails, and
 * `unreachable` for any other connection or TLS failure.
 */
class ShareClient(
    private val host: String,
    private val port: Int = ShareProtocol.SERVER_PORT,
    expectedFingerprint: String? = null,
    private val socketFactory: (PinningTrustManager) -> SSLSocketFactory = { Tls.clientContext(it).socketFactory },
) {
    @Volatile var peerFingerprint: String? = expectedFingerprint
        private set

    /** `(status, JSON or null)`; an error answer throws its [ShareError]. */
    fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject?> {
        val pin = PinningTrustManager(peerFingerprint)
        val socket = socketFactory(pin).createSocket() as SSLSocket
        try {
            Tls.restrict(socket)
            socket.soTimeout = ShareProtocol.CONNECT_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), ShareProtocol.CONNECT_TIMEOUT_MS)
            // The pin is checked in here: on a mismatch no byte of HTTP is ever written.
            socket.startHandshake()
        } catch (e: IOException) {
            socket.close()
            if (pin.mismatch) throw ShareError(ErrorCode.FINGERPRINT_MISMATCH, "the device presented a different certificate")
            throw ShareError(ErrorCode.UNREACHABLE, e.message ?: e.javaClass.simpleName)
        }
        val response = try {
            socket.use {
                peerFingerprint = pin.observed
                it.soTimeout = ShareProtocol.IDLE_TIMEOUT_MS
                val headers = linkedMapOf(ShareProtocol.VERSION_HEADER to ShareProtocol.VERSION.toString(), "Connection" to "close")
                if (body != null) headers["Content-Type"] = "application/json"
                HttpCodec.writeRequest(it.outputStream, method, path, headers, body?.toString()?.toByteArray(Charsets.UTF_8))
                HttpCodec.readResponse(BufferedInputStream(it.inputStream))
            }
        } catch (e: IOException) {
            throw ShareError(ErrorCode.UNREACHABLE, e.message ?: e.javaClass.simpleName)
        }
        val json = response.json()
        if (response.status >= 400) throw ShareError.fromEnvelope(response.status, json)
        return response.status to json
    }

    /** The receiver's `/info`; `incompatible_version` when no version is shared. */
    fun info(): PeerInfo {
        val info = PeerInfo.parse(request("GET", "/info").second)
        if (!info.compatible) throw ShareError.version(info.versions.min, info.versions.max)
        return info
    }

    /** Commit, offer, reveal: the receiver now shows the offer with its code, and so should we. */
    fun offer(sender: OfferSender, manifest: JSONObject): OutgoingOffer {
        info()
        val nonceS = Verification.newNonce()
        val body = JSONObject()
            .put("sender", JSONObject().put("id", sender.id).put("name", sender.name).put("pf", sender.platform).put("fp", sender.fp))
            .put("commit", Verification.commit(nonceS))
            .put("manifest", manifest)
        val created = request("POST", "/offer", body).second
        val offerId = created?.opt("offer_id") as? String
        val nonceR = created?.opt("nonce_r")
        if (offerId == null || !ReceiverOffers.OFFER_ID.matches(offerId) || !isHex(nonceR, 2 * Verification.NONCE_BYTES)) {
            throw ShareError.badRequest("malformed offer answer")
        }
        request("POST", "/offer/$offerId/reveal", JSONObject().put("nonce_s", nonceS.toHex()))
        val code = Verification.code(sender.fp, peerFingerprint!!, nonceS, (nonceR as String).hexToBytes())
        return OutgoingOffer(offerId, code)
    }

    /** `GET /offer/<id>`: `status`, and `items` once accepted. */
    fun poll(offerId: String): JSONObject {
        val data = request("GET", "/offer/$offerId").second
        if (data?.opt("status") !is String) throw ShareError.badRequest("malformed poll answer")
        return data
    }

    fun cancel(offerId: String) {
        request("POST", "/cancel/$offerId")
    }
}
