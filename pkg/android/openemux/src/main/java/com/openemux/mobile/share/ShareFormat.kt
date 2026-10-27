package com.openemux.mobile.share

import java.math.BigDecimal
import java.math.RoundingMode

/** The unit a size is shown in; the screens map it to a string resource. */
enum class SizeUnit { B, KB, MB, GB }

/** A size ready to show: [value] already rounded, in [unit]. */
data class SizeText(val value: String, val unit: SizeUnit)

/** What a screen says about how something ended or why it cannot go on; each maps to one string. */
enum class ShareMessage {
    DECLINED, EXPIRED, CANCELLED, BUSY, FINGERPRINT_MISMATCH, INCOMPATIBLE_VERSION, TOO_LARGE, NO_SPACE,
    SOURCE_CHANGED, BAD_REQUEST, UNREACHABLE, CONNECTION_LOST, TLS13, KEYSTORE, MULTICAST_LOCK_REFUSED, GENERIC,
}

/** Why an item or a game does not travel; each maps to one string. */
enum class SkipNote {
    EXISTS_SKIP, INCOMPATIBLE_STATE, UNSUPPORTED_CONSOLE, USER_UNCHECKED, INVALID_ITEM, NO_SPACE, UNIT_INCOMPLETE,
    TOO_MANY_ITEMS, GENERIC,
}

/**
 * The formatting rules both Share nearby screens share with the desktop
 * (#589): decimal units, the order of discovery paths, the platform of an
 * unknown device, the layer's codes as messages. Pure, no Android types.
 */
object ShareFormat {
    /** Decimal units, as the desktop shows them: whole bytes and kB, one decimal from MB up. */
    fun formatSize(bytes: Long): SizeText {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1_000 -> SizeText(b.toString(), SizeUnit.B)
            b < 1_000_000 -> SizeText(round(b, 1_000, 0), SizeUnit.KB)
            b < 1_000_000_000 -> SizeText(round(b, 1_000_000, 1), SizeUnit.MB)
            else -> SizeText(round(b, 1_000_000_000, 1), SizeUnit.GB)
        }
    }

    private fun round(bytes: Long, unit: Long, decimals: Int): String =
        BigDecimal(bytes).divide(BigDecimal(unit), decimals, RoundingMode.HALF_UP).toPlainString()

    /** The paths a peer was found by, in the order the screens name them. */
    fun orderedPaths(paths: Set<PeerPath>): List<PeerPath> = PATH_ORDER.filter { it in paths }

    private val PATH_ORDER = listOf(PeerPath.MDNS, PeerPath.BEACON, PeerPath.MANUAL)

    /** One of [ShareProtocol.PLATFORMS]; anything else is a desktop. */
    fun platformOf(code: String?): String = code?.takeIf { it in ShareProtocol.PLATFORMS } ?: ShareProtocol.DEFAULT_PLATFORM

    /** The message for an error code, a send/receive status or an unavailability reason. */
    fun errorKey(code: String?): ShareMessage = when (code) {
        ErrorCode.DECLINED -> ShareMessage.DECLINED
        ErrorCode.EXPIRED -> ShareMessage.EXPIRED
        ErrorCode.CANCELLED -> ShareMessage.CANCELLED
        ErrorCode.BUSY -> ShareMessage.BUSY
        ErrorCode.FINGERPRINT_MISMATCH -> ShareMessage.FINGERPRINT_MISMATCH
        ErrorCode.INCOMPATIBLE_VERSION -> ShareMessage.INCOMPATIBLE_VERSION
        ErrorCode.TOO_LARGE -> ShareMessage.TOO_LARGE
        ErrorCode.NO_SPACE -> ShareMessage.NO_SPACE
        ErrorCode.SOURCE_CHANGED -> ShareMessage.SOURCE_CHANGED
        ErrorCode.BAD_REQUEST -> ShareMessage.BAD_REQUEST
        ErrorCode.UNREACHABLE -> ShareMessage.UNREACHABLE
        ErrorCode.IDLE_TIMEOUT -> ShareMessage.CONNECTION_LOST
        ShareService.REASON_TLS13 -> ShareMessage.TLS13
        ShareService.REASON_KEYSTORE -> ShareMessage.KEYSTORE
        Discovery.WARNING_LOCK_REFUSED -> ShareMessage.MULTICAST_LOCK_REFUSED
        else -> ShareMessage.GENERIC
    }

    fun reasonKey(reason: String?): SkipNote = when (reason) {
        SkipReason.EXISTS_SKIP -> SkipNote.EXISTS_SKIP
        SkipReason.INCOMPATIBLE_STATE -> SkipNote.INCOMPATIBLE_STATE
        SkipReason.UNSUPPORTED_CONSOLE -> SkipNote.UNSUPPORTED_CONSOLE
        SkipReason.USER_UNCHECKED -> SkipNote.USER_UNCHECKED
        SkipReason.INVALID_ITEM -> SkipNote.INVALID_ITEM
        ErrorCode.NO_SPACE -> SkipNote.NO_SPACE
        SkipReason.UNIT_INCOMPLETE -> SkipNote.UNIT_INCOMPLETE
        SkipReason.TOO_MANY_ITEMS -> SkipNote.TOO_MANY_ITEMS
        else -> SkipNote.GENERIC
    }

    /** `482913` as `482 913`. */
    fun formatCode(code: String): String = Verification.formatCode(code)

    /** The code for a screen reader, digit by digit: `4 8 2 9 1 3`. */
    fun spokenCode(code: String): String = code.filter { it.isDigit() }.toList().joinToString(" ")

    /** A 64-hex fingerprint as two lines of four groups of four; a shorter one as far as it goes. */
    fun fingerprintLines(fp: String): List<String> =
        fp.take(32).chunked(4).chunked(4).map { it.joinToString(" ") }
}
