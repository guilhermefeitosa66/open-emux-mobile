package com.openemux.mobile.share

import com.openemux.mobile.share.http.HttpCodec
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** An offer the receiver took: its capability id and the code this side computed. */
data class OutgoingOffer(val offerId: String, val code: String)

/** What [OutgoingTransfer] asks of the receiver; [ShareClient] in the app, a fake in tests. */
interface OfferClient {
    fun offer(sender: OfferSender, manifest: JSONObject): OutgoingOffer
    fun poll(offerId: String): JSONObject
    fun cancel(offerId: String)
    fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit)
}

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
) : OfferClient {
    @Volatile var peerFingerprint: String? = expectedFingerprint
        private set

    /** `(status, JSON or null)`; an error answer throws its [ShareError]. */
    fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject?> {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf<String, String>()
        if (bytes != null) headers["Content-Type"] = "application/json"
        return exchange(method, path, headers, bytes?.size?.toLong() ?: 0) { it.write(bytes ?: ByteArray(0)) }
    }

    /**
     * Uploads [size] bytes of [file] as item [itemId] (`PUT /file`), in
     * 256 KiB chunks. [onChunk] hears each one and may throw to stop: the
     * connection is closed and the exception goes up as it is. A file that
     * ends before [size] is `source_changed`.
     */
    override fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit) {
        val headers = linkedMapOf("Content-Type" to "application/octet-stream")
        val source = try {
            file.inputStream()
        } catch (e: IOException) {
            throw ShareError(ErrorCode.SOURCE_CHANGED, e.message ?: "unreadable")
        }
        source.use { input ->
            exchange("PUT", "/file/$offerId/$itemId", headers, size) { output ->
                val buffer = ByteArray(UPLOAD_CHUNK)
                var left = size
                while (left > 0) {
                    val n = try {
                        input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    } catch (e: IOException) {
                        throw ShareError(ErrorCode.SOURCE_CHANGED, e.message ?: "unreadable")
                    }
                    if (n < 0) throw ShareError(ErrorCode.SOURCE_CHANGED, "${file.name} is shorter than offered")
                    output.write(buffer, 0, n)
                    left -= n
                    onChunk(n)
                }
            }
        }
    }

    /** One request on its own pinned connection; [writeBody] writes exactly [length] bytes. */
    private fun exchange(
        method: String,
        path: String,
        extraHeaders: Map<String, String>,
        length: Long,
        writeBody: (OutputStream) -> Unit,
    ): Pair<Int, JSONObject?> {
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
                headers.putAll(extraHeaders)
                val output = BufferedOutputStream(it.outputStream, UPLOAD_CHUNK)
                HttpCodec.writeHead(output, method, path, headers, length)
                writeBody(output)
                output.flush()
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
    override fun offer(sender: OfferSender, manifest: JSONObject): OutgoingOffer {
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
    override fun poll(offerId: String): JSONObject {
        val data = request("GET", "/offer/$offerId").second
        if (data?.opt("status") !is String) throw ShareError.badRequest("malformed poll answer")
        return data
    }

    override fun cancel(offerId: String) {
        request("POST", "/cancel/$offerId")
    }

    private companion object {
        const val UPLOAD_CHUNK = 256 * 1024
    }
}
