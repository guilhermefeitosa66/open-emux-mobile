package com.openemux.mobile.share

import android.util.Log
import com.openemux.mobile.share.http.HttpCodec
import com.openemux.mobile.share.http.HttpRequest
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Told about incoming offers. The server never decides on its own. */
interface IncomingOfferListener {
    /** An offer was revealed: show it with its code; answer through [ShareServer.accept] or [ShareServer.decline]. */
    fun onOffer(offer: IncomingOffer)
    /** The sender cancelled. */
    fun onCancelled(offerId: String) = Unit
}

/** Receives one file body (`PUT /file`); throw a [ShareError] to refuse it. */
fun interface FileReceiver {
    fun receive(offer: IncomingOffer, item: Item, body: InputStream)
}

/**
 * The receiving side: an HTTPS server over TLS 1.3 on 53319 (any free port
 * when that one is taken), one thread per connection, answering the routes of
 * spec §8. Offers wait for the user's answer through [accept] and [decline];
 * the default [listener] hears nothing, so offers expire unanswered until a
 * screen plugs in. File bodies go to [fileReceiver], which answers
 * `501 not_implemented` until the transfer lands (#595).
 */
class ShareServer(
    private val identity: DeviceIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val preferredPort: Int = ShareProtocol.SERVER_PORT,
    private val bindAddress: InetSocketAddress? = null,
) {
    @Volatile var listener: IncomingOfferListener = object : IncomingOfferListener {
        override fun onOffer(offer: IncomingOffer) = Unit
    }
    @Volatile var fileReceiver: FileReceiver = FileReceiver { _, _, _ ->
        throw ShareError(ErrorCode.NOT_IMPLEMENTED, "file transfer is not implemented")
    }

    val offers = ReceiverOffers(identity.fingerprint, clock)

    /** HTTP requests that reached the router: a refused handshake never counts. */
    val requestCount = AtomicInteger()

    @Volatile var port: Int = 0
        private set

    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ThreadPoolExecutor? = null
    private val connections = Collections.synchronizedSet(mutableSetOf<Socket>())
    /** Connections uploading into each offer, closed if the receiver cancels it. */
    private val uploads = mutableMapOf<String, MutableSet<Socket>>()

    val listening: Boolean get() = serverSocket != null

    /** Listens; returns the port actually bound. */
    @Synchronized
    fun start(): Int {
        serverSocket?.let { return port }
        val context = Tls.serverContext(identity.privateKey, identity.certificate)
        val socket = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.reuseAddress = true
        try {
            try {
                socket.bind(bindAddress?.let { InetSocketAddress(it.address, preferredPort) } ?: InetSocketAddress(preferredPort))
            } catch (_: IOException) {
                socket.bind(bindAddress?.let { InetSocketAddress(it.address, 0) } ?: InetSocketAddress(0))
            }
        } catch (e: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw e
        }
        socket.enabledProtocols = arrayOf(ShareProtocol.TLS_PROTOCOL)
        socket.needClientAuth = false
        port = socket.localPort
        serverSocket = socket
        val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "share-connection").apply { isDaemon = true }
        }
        executor = pool
        acceptThread = Thread({ acceptLoop(socket, pool) }, "share-server").apply { isDaemon = true; start() }
        Log.i(TAG, "share server listening on $port")
        return port
    }

    @Synchronized
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        offers.cancelAll()
        try {
            socket.close()
        } catch (_: IOException) {
        }
        synchronized(connections) { connections.toList() }.forEach(::closeQuietly)
        executor?.shutdownNow()
        executor = null
        acceptThread?.join(JOIN_TIMEOUT_MS)
        acceptThread = null
        synchronized(uploads) { uploads.clear() }
        Log.i(TAG, "share server stopped")
    }

    /** The user accepted; [answers] lists what to send and what to skip. */
    fun accept(offerId: String, answers: List<ItemAnswer>) = offers.answer(offerId, true, answers)

    fun decline(offerId: String) = offers.answer(offerId, false)

    /** Cancel from this side; an upload in progress is cut. */
    fun cancel(offerId: String) {
        offers.cancelByReceiver(offerId)
        synchronized(uploads) { uploads.remove(offerId)?.toList() }?.forEach(::closeQuietly)
    }

    private fun acceptLoop(server: ServerSocket, pool: ThreadPoolExecutor) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            try {
                pool.execute { serve(client as SSLSocket, server) }
            } catch (_: RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(socket: SSLSocket, server: ServerSocket) {
        connections += socket
        try {
            // stop() closes the server before it closes the connections: one added after that is closed here.
            if (server.isClosed) return
            socket.soTimeout = ShareProtocol.CONNECT_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = ShareProtocol.IDLE_TIMEOUT_MS
            val input = BufferedInputStream(socket.inputStream)
            val output = BufferedOutputStream(socket.outputStream)
            while (!socket.isClosed) {
                if (!handleOne(socket, input, output)) break
            }
        } catch (e: IOException) {
            // A failed handshake (a wrong client, a scan) or a dropped connection is not news.
            Log.d(TAG, "share connection dropped (${e.message})")
        } finally {
            connections -= socket
            closeQuietly(socket)
        }
    }

    /** One request and its answer; false when the connection should end. */
    private fun handleOne(socket: Socket, input: InputStream, output: OutputStream): Boolean {
        val request = try {
            HttpCodec.readRequest(input) ?: return false
        } catch (e: ShareError) {
            respond(output, e.httpStatus, e.toEnvelope())
            return false
        }
        requestCount.incrementAndGet()
        var keepAlive = request.header("connection")?.equals("close", ignoreCase = true) != true
        val (status, body) = try {
            // A body the route left unread would be taken for the next request.
            route(request, socket).also { keepAlive = keepAlive && request.body.discard(DISCARD_MAX_BYTES) }
        } catch (e: ShareError) {
            keepAlive = keepAlive && request.body.discard(DISCARD_MAX_BYTES)
            e.httpStatus to e.toEnvelope()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "share server failed on ${request.method} ${request.path}", e)
            keepAlive = keepAlive && request.body.discard(DISCARD_MAX_BYTES)
            500 to ShareError(ErrorCode.INTERNAL, "internal error").toEnvelope()
        }
        respond(output, status, body)
        return keepAlive
    }

    private fun respond(output: OutputStream, status: Int, body: JSONObject?) {
        val headers = linkedMapOf(ShareProtocol.VERSION_HEADER to ShareProtocol.VERSION.toString())
        if (body != null) headers["Content-Type"] = "application/json"
        HttpCodec.writeResponse(output, status, headers, body?.toString()?.toByteArray(Charsets.UTF_8))
    }

    internal fun route(request: HttpRequest, socket: Socket?): Pair<Int, JSONObject?> {
        val parts = request.path.split('/').filter { it.isNotEmpty() }
        val method = request.method
        if (method == "GET" && parts == listOf("info")) return 200 to identity.info()
        checkVersion(request.header(ShareProtocol.VERSION_HEADER))
        return when {
            method == "POST" && parts == listOf("offer") -> 201 to offers.create(request.readJson())
            method == "POST" && parts.size == 3 && parts[0] == "offer" && parts[2] == "reveal" -> {
                val incoming = offers.reveal(parts[1], request.readJson())
                listener.onOffer(incoming)
                200 to JSONObject().put("code", incoming.code)
            }
            method == "GET" && parts.size == 2 && parts[0] == "offer" -> 200 to offers.poll(parts[1])
            method == "POST" && parts.size == 2 && parts[0] == "cancel" -> {
                if (offers.cancelBySender(parts[1])) listener.onCancelled(parts[1])
                204 to null
            }
            method == "PUT" && parts.size == 3 && parts[0] == "file" -> {
                receive(parts[1], parts[2], request, socket)
                204 to null
            }
            else -> throw ShareError(ErrorCode.NOT_FOUND, "no such endpoint")
        }
    }

    private fun receive(offerId: String, itemId: String, request: HttpRequest, socket: Socket?) {
        val (incoming, item) = offers.beginUpload(offerId, itemId, request.contentLength)
        if (socket != null) synchronized(uploads) { uploads.getOrPut(offerId) { mutableSetOf() } += socket }
        try {
            fileReceiver.receive(incoming, item, request.body)
        } finally {
            if (socket != null) synchronized(uploads) { uploads[offerId]?.remove(socket) }
        }
        offers.uploaded(offerId, itemId)
    }

    private fun checkVersion(header: String?) {
        val peer = parseDecimal(header)
        if (peer == null || VersionRange(peer, peer).negotiate() == null) throw ShareError.version(peer, peer)
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 16
        const val JOIN_TIMEOUT_MS = 2_000L
        /** The most an error answer reads away to keep the connection; past that it closes it. */
        const val DISCARD_MAX_BYTES = 4L * 1024 * 1024
    }
}
