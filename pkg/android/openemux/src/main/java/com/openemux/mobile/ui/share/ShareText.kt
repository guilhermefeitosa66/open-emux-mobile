package com.openemux.mobile.ui.share

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openemux.mobile.data.Console
import com.openemux.mobile.share.Item
import com.openemux.mobile.share.PeerPath
import com.openemux.mobile.share.Policy
import com.openemux.mobile.share.SendPart
import com.openemux.mobile.share.ShareFormat
import com.openemux.mobile.share.ShareMessage
import com.openemux.mobile.share.ShareProtocol
import com.openemux.mobile.share.SizeUnit
import com.openemux.mobile.share.SkipNote
import com.retroarch.R
import java.text.DecimalFormatSymbols

/** "3.1 MB" with [ShareFormat.formatSize]'s numbers, the unit and the decimal mark of the app's language. */
fun sizeLabel(context: Context, bytes: Long): String {
    val size = ShareFormat.formatSize(bytes)
    val value = size.value.replace('.', DecimalFormatSymbols.getInstance(context.resources.configuration.locales[0]).decimalSeparator)
    return context.getString(
        when (size.unit) {
            SizeUnit.B -> R.string.share_size_b
            SizeUnit.KB -> R.string.share_size_kb
            SizeUnit.MB -> R.string.share_size_mb
            SizeUnit.GB -> R.string.share_size_gb
        },
        value,
    )
}

@Composable
fun sizeText(bytes: Long): String = sizeLabel(LocalContext.current, bytes)

@Composable
fun platformName(platform: String): String = stringResource(
    when (ShareFormat.platformOf(platform)) {
        "phone" -> R.string.share_platform_phone
        "tablet" -> R.string.share_platform_tablet
        else -> R.string.share_platform_desktop
    },
)

/** "mDNS and beacon": the paths in their fixed order. */
@Composable
fun pathsText(paths: Set<PeerPath>): String {
    val names = ShareFormat.orderedPaths(paths).map {
        stringResource(
            when (it) {
                PeerPath.MDNS -> R.string.share_path_mdns
                PeerPath.BEACON -> R.string.share_path_beacon
                PeerPath.MANUAL -> R.string.share_path_manual
            },
        )
    }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> stringResource(R.string.share_paths_and, names.dropLast(1).joinToString(", "), names.last())
    }
}

@Composable
fun partName(part: SendPart): String = stringResource(
    when (part) {
        SendPart.GAME -> R.string.share_items_game
        SendPart.SAVE -> R.string.share_items_save
        SendPart.STATES -> R.string.share_items_states
        SendPart.COVER -> R.string.share_items_cover
        SendPart.LABEL -> R.string.share_items_label
        SendPart.DETAILS -> R.string.share_items_details
    },
)

@Composable
fun kindName(kind: String): String = stringResource(
    when (kind) {
        Item.ROM -> R.string.share_kind_rom
        Item.BATTERY_SAVE -> R.string.share_kind_save
        Item.STATE -> R.string.share_kind_state
        Item.COVER -> R.string.share_kind_cover
        else -> R.string.share_kind_label
    },
)

@Composable
fun policyName(policy: Policy): String = stringResource(
    when (policy) {
        Policy.NEWEST -> R.string.share_policy_newest
        Policy.KEEP_BOTH -> R.string.share_policy_keep_both
        Policy.REPLACE -> R.string.share_policy_replace
        Policy.SKIP -> R.string.share_policy_skip
    },
)

@Composable
fun skipText(reason: String): String = stringResource(
    when (ShareFormat.reasonKey(reason)) {
        SkipNote.EXISTS_SKIP -> R.string.share_reason_exists
        SkipNote.INCOMPATIBLE_STATE -> R.string.share_reason_incompatible_state
        SkipNote.UNSUPPORTED_CONSOLE -> R.string.share_reason_unsupported
        SkipNote.USER_UNCHECKED -> R.string.share_reason_unchecked
        SkipNote.INVALID_ITEM -> R.string.share_reason_invalid
        SkipNote.NO_SPACE -> R.string.share_reason_no_space
        SkipNote.UNIT_INCOMPLETE -> R.string.share_reason_unit_incomplete
        SkipNote.TOO_MANY_ITEMS -> R.string.share_reason_too_many
        SkipNote.GENERIC -> R.string.share_reason_generic
    },
)

/** Why [peer] did not take a game this device sent (#615): an unknown or missing answer reads as not accepted. */
@Composable
fun sentSkipText(reason: String?, peer: String): String = when (ShareFormat.reasonKey(reason)) {
    SkipNote.EXISTS_SKIP -> stringResource(R.string.share_reason_sent_exists, peer)
    SkipNote.INVALID_ITEM, SkipNote.INCOMPATIBLE_STATE -> stringResource(R.string.share_reason_sent_cant_place, peer)
    SkipNote.UNSUPPORTED_CONSOLE -> stringResource(R.string.share_reason_sent_unsupported, peer)
    SkipNote.UNIT_INCOMPLETE, SkipNote.TOO_MANY_ITEMS, SkipNote.NO_SPACE -> skipText(reason!!)
    SkipNote.USER_UNCHECKED, SkipNote.GENERIC -> stringResource(R.string.share_reason_sent_not_accepted, peer)
}

/** A layer error or an unavailability reason as a sentence; [peer] names the other device where the text does. */
@Composable
fun messageText(message: ShareMessage, peer: String = "", peerVersion: Int? = null): String = when (message) {
    ShareMessage.DECLINED -> stringResource(R.string.share_result_declined_body, peer)
    ShareMessage.EXPIRED -> stringResource(R.string.share_result_expired_body, peer)
    ShareMessage.CANCELLED -> stringResource(R.string.share_result_cancelled_body, peer)
    ShareMessage.BUSY -> stringResource(R.string.share_error_busy, peer)
    ShareMessage.FINGERPRINT_MISMATCH -> stringResource(R.string.share_address_err_identity)
    ShareMessage.INCOMPATIBLE_VERSION ->
        stringResource(R.string.share_address_err_version, peerVersion?.toString() ?: "?", ShareProtocol.VERSION.toString())
    ShareMessage.TOO_LARGE, ShareMessage.NO_SPACE -> stringResource(R.string.share_error_no_space)
    ShareMessage.SOURCE_CHANGED -> stringResource(R.string.share_error_source_changed)
    ShareMessage.BAD_REQUEST -> stringResource(R.string.share_address_err_parse)
    ShareMessage.UNREACHABLE -> stringResource(R.string.share_address_err_reach, peer)
    ShareMessage.CONNECTION_LOST -> stringResource(R.string.share_result_lost_body, peer)
    ShareMessage.TLS13 -> stringResource(R.string.share_unavailable_tls13)
    ShareMessage.KEYSTORE -> stringResource(R.string.share_unavailable_keystore)
    ShareMessage.MULTICAST_LOCK_REFUSED -> stringResource(R.string.share_warning_multicast)
    ShareMessage.GENERIC -> stringResource(R.string.share_error_generic)
}

/** The console's short name for an id off the wire, the id itself for one this app does not know. */
fun consoleShort(id: String): String = Console.byId(id)?.short ?: id
