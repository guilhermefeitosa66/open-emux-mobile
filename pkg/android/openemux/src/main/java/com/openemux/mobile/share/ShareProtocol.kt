package com.openemux.mobile.share

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * OpenEmux Share Protocol v1: constants, errors and validators of the wire.
 *
 * Pure: no sockets, no threads. Everything here parses data a stranger on the
 * LAN sent, so every parser checks the size before it parses, accepts JSON only,
 * ignores unknown keys and answers null or [ShareError] instead of letting a
 * malformed value through. `docs/share-protocol.md` in the OpenEmux repository
 * is the normative description; the desktop counterpart is
 * `src/openemux/core/share/protocol.py`.
 */
object ShareProtocol {
    const val VERSION = 1
    const val MIN_VERSION = 1

    /** Header every request and response carries (a request for `/info` may lack it). */
    const val VERSION_HEADER = "OpenEmux-Share-Version"

    /** `_openemux._tcp.local.` as NsdManager wants it: no domain, no trailing dot. */
    const val SERVICE_TYPE = "_openemux._tcp"
    const val BEACON_PROTOCOL = "openemux-share"
    const val BEACON_GROUP = "239.255.77.77"
    const val BEACON_PORT = 53318
    const val BEACON_TTL = 1
    const val BEACON_INTERVAL_MS = 2_000L
    /** A path that expires on its own (the beacon) is dropped this long after its last hello. */
    const val PEER_EXPIRY_MS = 7_000L
    const val SERVER_PORT = 53319

    const val BEACON_MAX_BYTES = 1024
    const val BODY_MAX_BYTES = 1024 * 1024
    const val OFFER_TTL_MS = 60_000L
    const val CONNECT_TIMEOUT_MS = 10_000
    const val IDLE_TIMEOUT_MS = 30_000

    const val MAX_NAME_CHARS = 64
    const val MAX_ITEMS = 2000
    const val MAX_FILE_NAME_BYTES = 255

    const val SCHEME = "openemux-share://"

    const val TLS_PROTOCOL = "TLSv1.3"

    val PLATFORMS = listOf("desktop", "phone", "tablet")
    const val DEFAULT_PLATFORM = "desktop"
}

/** Shared by every class of the package, so `logcat -s OpenEmuxShare` shows the whole feature. */
internal const val TAG = "OpenEmuxShare"

/**
 * Error codes, on the wire and in the share layer's own failures. The UI
 * translates them (#591); nothing here is ever shown to a user as a sentence.
 * The last three never cross the network.
 */
object ErrorCode {
    const val BAD_REQUEST = "bad_request"
    const val INCOMPATIBLE_VERSION = "incompatible_version"
    const val BAD_COMMIT = "bad_commit"
    const val NOT_FOUND = "not_found"
    const val BUSY = "busy"
    const val EXPIRED = "expired"
    const val CANCELLED = "cancelled"
    const val DECLINED = "declined"
    const val TOO_LARGE = "too_large"
    const val NOT_IMPLEMENTED = "not_implemented"
    const val INTERNAL = "internal"
    const val FINGERPRINT_MISMATCH = "fingerprint_mismatch"
    const val UNREACHABLE = "unreachable"
    const val UNAVAILABLE = "unavailable"

    /** The HTTP status each wire error is answered with. */
    val HTTP_STATUS = mapOf(
        BAD_REQUEST to 400,
        INCOMPATIBLE_VERSION to 400,
        BAD_COMMIT to 403,
        NOT_FOUND to 404,
        BUSY to 409,
        EXPIRED to 410,
        CANCELLED to 410,
        DECLINED to 410,
        TOO_LARGE to 413,
        NOT_IMPLEMENTED to 501,
        INTERNAL to 500,
    )
}

/**
 * A protocol failure carrying its [ErrorCode]. [details] holds the extra
 * fields of the error envelope (the version ranges of `incompatible_version`,
 * or which part is unavailable).
 */
class ShareError(
    val code: String,
    override val message: String = code,
    val details: Map<String, Any?> = emptyMap(),
) : Exception(message) {
    val httpStatus: Int get() = ErrorCode.HTTP_STATUS[code] ?: 500

    fun toEnvelope(): JSONObject = JSONObject().apply {
        for ((key, value) in details) put(key, value ?: JSONObject.NULL)
        put("error", code)
        put("message", message)
    }

    companion object {
        /** The error an answer carried; `internal` when it carried none. */
        fun fromEnvelope(status: Int, body: JSONObject?): ShareError {
            val code = body?.opt("error") as? String ?: return ShareError(ErrorCode.INTERNAL, "HTTP $status")
            val details = body.keys().asSequence().filter { it != "error" && it != "message" }
                .associateWith { body.opt(it) }
            return ShareError(code, body.opt("message") as? String ?: code, details)
        }

        fun badRequest(message: String) = ShareError(ErrorCode.BAD_REQUEST, message)

        /** The `incompatible_version` error naming both sides' versions. */
        fun version(peerMin: Int?, peerMax: Int?) = ShareError(
            ErrorCode.INCOMPATIBLE_VERSION,
            "version ${ShareProtocol.VERSION} vs $peerMax",
            mapOf(
                "supported" to JSONObject().put("min", ShareProtocol.MIN_VERSION).put("max", ShareProtocol.VERSION),
                "peer" to JSONObject().put("min", peerMin ?: JSONObject.NULL).put("max", peerMax ?: JSONObject.NULL),
            ),
        )
    }
}

/** A version range a peer advertised. Unreadable versions come out as 0..0, never dropped. */
data class VersionRange(val min: Int, val max: Int) {
    /** The highest version both sides speak, or null. */
    fun negotiate(): Int? {
        if (min > max) return null
        val best = minOf(max, ShareProtocol.VERSION)
        return best.takeIf { it >= maxOf(min, ShareProtocol.MIN_VERSION) }
    }

    val compatible: Boolean get() = negotiate() != null

    companion object {
        val OWN = VersionRange(ShareProtocol.MIN_VERSION, ShareProtocol.VERSION)

        /** `(vmin, v)` from advertised values: missing `v` is 0; `vmin` missing or above `v` is `v`. */
        fun parse(rawV: Any?, rawVmin: Any?): VersionRange {
            val v = toInt(rawV) ?: return VersionRange(0, 0)
            val vmin = toInt(rawVmin)?.takeIf { it <= v } ?: v
            return VersionRange(vmin, v)
        }
    }
}

/** What every discovery path, and `/info`, hands the peer list, normalised. */
data class Announcement(
    val id: String,
    val name: String,
    val platform: String,
    val versions: VersionRange,
    val port: Int,
    val fp: String,
) {
    val compatible: Boolean get() = versions.compatible

    companion object {
        fun of(id: String, name: Any?, platform: Any?, v: Any?, vmin: Any?, port: Int, fp: String) =
            Announcement(id, displayName(name, id), sanitizePlatform(platform), VersionRange.parse(v, vmin), port, fp)
    }
}

private val HEX = Regex("[0-9a-f]+")

fun isHex(value: Any?, length: Int): Boolean =
    value is String && value.length == length && HEX.matches(value)

fun isDeviceId(value: Any?) = isHex(value, 32)

fun isFingerprint(value: Any?) = isHex(value, 64)

/** A device name fit to show: no control characters (Cc, Cf), trimmed, at most 64 characters. */
fun sanitizeName(name: Any?): String {
    if (name !is String) return ""
    val kept = StringBuilder()
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        val type = Character.getType(cp)
        if (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()) kept.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    val trimmed = kept.toString().trim()
    return cutCodePoints(trimmed, ShareProtocol.MAX_NAME_CHARS).trim()
}

/** [sanitizeName], or the first six characters of the id when nothing is left. */
fun displayName(name: Any?, id: String): String = sanitizeName(name).ifEmpty { id.take(6) }

fun sanitizePlatform(platform: Any?): String =
    if (platform is String && platform in ShareProtocol.PLATFORMS) platform else ShareProtocol.DEFAULT_PLATFORM

/** `fp` in groups of four, the way both apps display it. */
fun formatFingerprint(fp: String): String = fp.chunked(4).joinToString(" ")

/** A non-negative integer written in ASCII digits, or null. */
fun parseDecimal(value: Any?): Int? {
    if (value !is String || value.isEmpty() || value.length > 9) return null
    if (value.any { it !in '0'..'9' }) return null
    return value.toInt()
}

/** An integer JSON value or decimal string; booleans and fractions are not integers. */
internal fun toInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    else -> parseDecimal(value)
}

internal fun toPort(value: Any?): Int? = toInt(value)?.takeIf { it in 1..65535 }

/** The first [count] characters of [text], never splitting a surrogate pair. */
internal fun cutCodePoints(text: String, count: Int): String {
    if (text.codePointCount(0, text.length) <= count) return text
    return text.substring(0, text.offsetByCodePoints(0, count))
}

/**
 * [bytes] as a JSON object, or null. A deeply nested body is malformed, not a
 * crash: org.json recurses, so the stack overflow is caught here too.
 */
internal fun parseJsonObject(bytes: ByteArray): JSONObject? {
    val text = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        return null
    }
    return try {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        if (tokener.nextClean() != 0.toChar()) null else value as? JSONObject
    } catch (_: JSONException) {
        null
    } catch (_: StackOverflowError) {
        null
    }
}

/** A `GET /info` answer, normalised; `bad_request` when unusable. */
data class PeerInfo(val id: String, val name: String, val platform: String, val versions: VersionRange) {
    val compatible: Boolean get() = versions.compatible

    companion object {
        fun parse(payload: JSONObject?): PeerInfo {
            val id = payload?.opt("id")
            if (payload == null || !isDeviceId(id)) throw ShareError.badRequest("malformed /info")
            id as String
            return PeerInfo(
                id, displayName(payload.opt("name"), id), sanitizePlatform(payload.opt("pf")),
                VersionRange.parse(payload.opt("v"), payload.opt("vmin")),
            )
        }
    }
}
