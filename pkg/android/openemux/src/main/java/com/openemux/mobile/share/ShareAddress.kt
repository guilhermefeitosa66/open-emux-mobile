package com.openemux.mobile.share

/**
 * The manual address and the QR payload: `openemux-share://host:port#fp`.
 *
 * What a user types when discovery cannot see the other device, and what the
 * QR code encodes. Parsing accepts the full URI, `host`, `host:port` and
 * `[v6]:port`; the fingerprint, when present, pins the certificate.
 */
data class ShareAddress(val host: String, val port: Int = ShareProtocol.SERVER_PORT, val fp: String? = null) {
    /** Always all three parts when the fingerprint is known (what the QR code carries). */
    fun format(): String {
        val shown = if (':' in host) "[$host]" else host
        val uri = "${ShareProtocol.SCHEME}$shown:$port"
        return if (fp != null) "$uri#$fp" else uri
    }

    companion object {
        /** The address in [text]; `ShareError(bad_request)` when it is not one. */
        fun parse(text: String): ShareAddress {
            var rest = text.trim()
            if (rest.lowercase().startsWith(ShareProtocol.SCHEME)) rest = rest.substring(ShareProtocol.SCHEME.length)
            val hash = rest.indexOf('#')
            val fp = if (hash < 0) null else rest.substring(hash + 1).replace(" ", "").replace(":", "").lowercase().ifEmpty { null }
            if (hash >= 0) rest = rest.substring(0, hash)
            if (fp != null && !isFingerprint(fp)) throw bad(text)
            val (host, port) = splitHostPort(rest.trimEnd('/'), text)
            if (host.isEmpty() || host.any { it.isWhitespace() || it in "/?@[]" }) throw bad(text)
            return ShareAddress(host, port, fp)
        }

        private fun splitHostPort(rest: String, text: String): Pair<String, Int> {
            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close < 0) throw bad(text)
                val after = rest.substring(close + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) throw bad(text)
                val port = if (after.isEmpty()) ShareProtocol.SERVER_PORT else port(after.substring(1), text)
                return rest.substring(1, close) to port
            }
            if (rest.count { it == ':' } == 1) {
                val colon = rest.indexOf(':')
                return rest.substring(0, colon) to port(rest.substring(colon + 1), text)
            }
            // A bare host, or a bare IPv6 literal: no port can be told apart from it.
            return rest to ShareProtocol.SERVER_PORT
        }

        private fun port(value: String, text: String): Int = toPort(parseDecimal(value)) ?: throw bad(text)

        private fun bad(text: String) = ShareError.badRequest("not an address: \"$text\"")
    }
}
