package com.openemux.mobile.share.http

import com.openemux.mobile.share.ErrorCode
import com.openemux.mobile.share.ShareError
import com.openemux.mobile.share.ShareProtocol
import com.openemux.mobile.share.parseJsonObject
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The little HTTP/1.1 the share protocol needs, for the server and the client
 * alike: a request or status line, headers (8 KiB at most, all of them),
 * `Content-Length` bodies only (no chunked encoding) and keep-alive. Bodies are
 * handed out as streams bounded by their length, so a file upload is never read
 * into memory, and a JSON body is refused over 1 MiB before a byte of it is read.
 */
object HttpCodec {
    const val MAX_HEADER_BYTES = 8 * 1024

    private val REASONS = mapOf(
        200 to "OK", 201 to "Created", 204 to "No Content", 400 to "Bad Request", 403 to "Forbidden",
        404 to "Not Found", 409 to "Conflict", 410 to "Gone", 413 to "Content Too Large",
        500 to "Internal Server Error", 501 to "Not Implemented",
    )

    /** The next request on [input], or null when the peer closed the connection between requests. */
    fun readRequest(input: InputStream): HttpRequest? {
        val head = readHead(input) ?: return null
        val parts = head.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw ShareError.badRequest("bad request line")
        val headers = parseHeaders(head)
        val length = contentLength(headers)
        return HttpRequest(parts[0], parts[1].substringBefore('?'), headers, BoundedInputStream(input, length), length)
    }

    fun writeRequest(output: OutputStream, method: String, path: String, headers: Map<String, String>, body: ByteArray?) {
        val head = StringBuilder("$method $path HTTP/1.1\r\n")
        writeHeaders(head, headers, body)
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        body?.let(output::write)
        output.flush()
    }

    /** The response on [input]; its body is read whole, and refused over [maxBody] bytes. */
    fun readResponse(input: InputStream, maxBody: Int = ShareProtocol.BODY_MAX_BYTES): HttpResponse {
        val head = readHead(input) ?: throw EOFException("connection closed before the answer")
        val parts = head.first().split(' ', limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull()
        if (!parts[0].startsWith("HTTP/1.") || status == null) throw IOException("bad status line")
        val headers = parseHeaders(head)
        val length = contentLength(headers)
        if (length > maxBody) throw IOException("answer over $maxBody bytes")
        val body = BoundedInputStream(input, length).readBytes()
        if (body.size.toLong() != length) throw EOFException("answer cut short")
        return HttpResponse(status, headers, body)
    }

    fun writeResponse(output: OutputStream, status: Int, headers: Map<String, String>, body: ByteArray?) {
        val head = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Unknown"}\r\n")
        writeHeaders(head, headers, body)
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        body?.let(output::write)
        output.flush()
    }

    private fun writeHeaders(head: StringBuilder, headers: Map<String, String>, body: ByteArray?) {
        for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n\r\n")
    }

    /** The header block as lines, or null on a clean end of stream before it starts. */
    private fun readHead(input: InputStream): List<String>? {
        val bytes = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (bytes.size() == 0) return null
                throw EOFException("connection closed inside the headers")
            }
            if (bytes.size() >= MAX_HEADER_BYTES) throw ShareError.badRequest("headers over $MAX_HEADER_BYTES bytes")
            bytes.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == CRLFCRLF) break
        }
        val text = bytes.toString(Charsets.ISO_8859_1.name())
        return text.removeSuffix("\r\n\r\n").split("\r\n")
    }

    private fun parseHeaders(head: List<String>): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        for (line in head.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw ShareError.badRequest("bad header line")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "content-length" && name in headers && headers[name] != value) throw ShareError.badRequest("conflicting Content-Length")
            headers[name] = value
        }
        return headers
    }

    private fun contentLength(headers: Map<String, String>): Long {
        if ("transfer-encoding" in headers) throw ShareError.badRequest("Transfer-Encoding is not supported")
        val raw = headers["content-length"] ?: return 0
        if (raw.isEmpty() || raw.length > 18 || raw.any { it !in '0'..'9' }) throw ShareError.badRequest("bad Content-Length")
        return raw.toLong()
    }

    private const val CRLFCRLF = 0x0d0a0d0a
}

class HttpRequest(
    val method: String,
    val path: String,
    /** Lower-case names. */
    val headers: Map<String, String>,
    val body: BoundedInputStream,
    val contentLength: Long,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** The body as a JSON object: `too_large` over 1 MiB (unread), `bad_request` when it is not one. */
    fun readJson(): JSONObject {
        if (contentLength > ShareProtocol.BODY_MAX_BYTES) throw ShareError(ErrorCode.TOO_LARGE, "body over 1 MiB")
        val bytes = body.readBytes()
        if (bytes.size.toLong() != contentLength) throw EOFException("body cut short")
        return parseJsonObject(bytes) ?: throw ShareError.badRequest("body is not a JSON object")
    }
}

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun json(): JSONObject? = if (body.isEmpty()) null else parseJsonObject(body)
}

/** At most [limit] bytes of [input]; closing it leaves [input] open. */
class BoundedInputStream(private val input: InputStream, limit: Long) : InputStream() {
    var remaining: Long = limit
        private set

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = input.read()
        if (b >= 0) remaining-- else remaining = 0
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val n = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) remaining = 0 else remaining -= n
        return n
    }

    override fun available(): Int = minOf(input.available().toLong(), remaining).toInt()

    /** Reads what is left away, up to [max] bytes; false when more was left than that. */
    fun discard(max: Long): Boolean {
        if (remaining > max) return false
        val sink = ByteArray(16 * 1024)
        while (read(sink) >= 0) Unit
        return true
    }

    override fun close() = Unit
}
