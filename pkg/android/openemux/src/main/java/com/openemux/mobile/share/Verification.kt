package com.openemux.mobile.share

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The six-digit verification code both screens show (spec §9).
 *
 * Commit-reveal: the sender commits to `nonce_s` before it learns `nonce_r`,
 * and the receiver picks `nonce_r` before it learns `nonce_s`, so a man in the
 * middle gets one blind guess per offer. Nonces and fingerprints enter the
 * hashes as raw bytes, never as hex text.
 */
object Verification {
    const val NONCE_BYTES = 32
    private val COMMIT_LABEL = "openemux-share-v1/commit".toByteArray(Charsets.US_ASCII)
    private val CODE_LABEL = "openemux-share-v1/code".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    fun commit(nonceS: ByteArray): String = sha256(COMMIT_LABEL, nonceS).toHex()

    /** Whether [nonceS] is what [commit] promised, compared in constant time. */
    fun checkCommit(commit: String, nonceS: ByteArray): Boolean =
        constantTimeEquals(commit(nonceS), commit.lowercase())

    /** The code as six digits; [fpSender] and [fpReceiver] are 64-hex fingerprints. */
    fun code(fpSender: String, fpReceiver: String, nonceS: ByteArray, nonceR: ByteArray): String {
        val digest = sha256(CODE_LABEL, fpSender.hexToBytes(), fpReceiver.hexToBytes(), nonceS, nonceR)
        var value = 0UL
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toUByte().toULong())
        return (value % 1_000_000UL).toString().padStart(6, '0')
    }

    /** `093444` as `093 444`. */
    fun formatCode(code: String): String = "${code.take(3)} ${code.drop(3)}"

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Even-length hex to bytes; callers have validated it with [isHex]. */
internal fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { ((Character.digit(this[2 * it], 16) shl 4) or Character.digit(this[2 * it + 1], 16)).toByte() }

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
