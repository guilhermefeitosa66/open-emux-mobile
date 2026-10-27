package com.openemux.mobile.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.share.ResultKind
import com.openemux.mobile.share.ShareFormat
import com.openemux.mobile.share.ShareMessage
import com.openemux.mobile.share.ShareResult
import com.openemux.mobile.share.TransferState
import com.openemux.mobile.ui.GamepadButtons
import com.openemux.mobile.ui.Icon
import com.openemux.mobile.ui.Icons
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.PrimaryButton
import com.openemux.mobile.ui.SecondaryButton
import com.openemux.mobile.ui.Type
import com.openemux.mobile.ui.Card
import com.openemux.mobile.ui.ListRow
import com.openemux.mobile.ui.countString
import com.openemux.mobile.ui.gamepadFocus
import com.retroarch.R

/** The verification code card (spec 05): big digits, read one by one. */
@Composable
fun CodeCard(code: String, caption: String, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.share_code_spoken, ShareFormat.spokenCode(code))
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Oe.AccentTint).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.share_code_eyebrow).uppercase(), style = Type.Eyebrow)
        Text(
            ShareFormat.formatCode(code),
            style = Type.Title.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = 6.sp),
            modifier = Modifier.padding(vertical = 6.dp).semantics { contentDescription = spoken },
        )
        Text(caption, style = Type.Small, textAlign = TextAlign.Center)
    }
}

/** The sender's wait for an answer (spec 07). */
@Composable
fun WaitingView(state: TransferState.Waiting, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Oe.AccentText, strokeWidth = 2.5.dp)
        Text(stringResource(R.string.share_wait_title, state.peer), style = Type.Section, textAlign = TextAlign.Center)
        state.code?.let { CodeCard(it, stringResource(R.string.share_wait_hint, state.peer)) }
        SecondaryButton(stringResource(R.string.share_cancel), onCancel, Modifier.fillMaxWidth())
    }
}

/** Bytes moving, either way (spec 07); Cancel asks first. */
@Composable
fun ProgressView(state: TransferState.Running, onCancel: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val p = state.progress
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(if (state.outgoing) R.string.share_progress_sending else R.string.share_progress_receiving, state.peer),
            style = Type.Section.copy(fontSize = 17.sp),
        )
        val fraction = if (p != null && p.totalBytes > 0) p.doneBytes.toFloat() / p.totalBytes else 0f
        LinearProgressIndicator(
            { fraction }, Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = Oe.AccentBright, trackColor = Oe.Divider, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(
                stringResource(R.string.share_progress_bytes, sizeText(p?.doneBytes ?: 0), sizeText(p?.totalBytes ?: 0)),
                style = Type.Small, modifier = Modifier.weight(1f),
            )
            if (p != null) Text(stringResource(R.string.share_progress_files, (p.filesDone + 1).coerceAtMost(p.fileCount), p.fileCount), style = Type.Small)
        }
        if (p != null) Text(p.name + " · " + kindName(p.kind), style = Type.Small.copy(color = Oe.Text), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Info, Oe.Muted, 18.dp)
            Text(stringResource(R.string.share_progress_note), style = Type.Small)
        }
        DangerButton(stringResource(R.string.share_progress_cancel), { confirm = true }, Modifier.padding(top = 4.dp).fillMaxWidth())
    }
    if (confirm) CancelTransferDialog({ confirm = false }) { confirm = false; onCancel() }
}

/** A button whose action loses something: red on red. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(52.dp).gamepadFocus(corner = 16.dp).clip(RoundedCornerShape(16.dp)).background(Oe.DangerBg)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Button.copy(color = Oe.Danger, fontSize = 15.sp)) }
}

@Composable
fun CancelTransferDialog(onKeep: () -> Unit, onCancel: () -> Unit) {
    val swallow = {}
    GamepadButtons(
        hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.share_cancel_confirm_keep)),
        l1 = swallow, r1 = swallow, x = swallow, y = swallow, select = swallow, start = swallow,
    )
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = Oe.Sheet,
        title = { Text(stringResource(R.string.share_cancel_confirm_title), style = Type.Title.copy(fontSize = 20.sp)) },
        text = { Text(stringResource(R.string.share_cancel_confirm_body), style = Type.Small) },
        confirmButton = {
            TextButton(onCancel) { Text(stringResource(R.string.share_progress_cancel), style = Type.Section.copy(color = Oe.Danger)) }
        },
        dismissButton = { TextButton(onKeep) { Text(stringResource(R.string.share_cancel_confirm_keep), style = Type.Section) } },
    )
}

/** How it ended (spec 08). [onShow] is the received games' "Show in Library", when there is a console to open. */
@Composable
fun ResultView(result: ShareResult, onClose: () -> Unit, onShow: (() -> Unit)?) {
    val ok = result.kind == ResultKind.RECEIVED || result.kind == ResultKind.SENT
    // Some of the games did not arrive (#615): a warning, and the list of them.
    val short = ok && result.missing.isNotEmpty()
    val none = short && result.games == 0
    val peer = result.peer
    val (title, body) = when (result.kind) {
        ResultKind.RECEIVED -> when {
            none -> stringResource(R.string.share_result_received_none) to stringResource(R.string.share_result_received_none_body, peer)
            short -> countString(R.plurals.share_result_received_partial, result.total, result.games, result.total) to
                stringResource(R.string.share_result_received_partial_body, peer)
            else -> countString(R.plurals.share_result_received_title, result.games, result.games) to
                // Everything was skipped or kept: nothing new is in the library.
                stringResource(if (result.games > 0) R.string.share_result_received_body else R.string.share_result_received_body_none, peer)
        }
        ResultKind.SENT -> when {
            none -> stringResource(R.string.share_result_sent_none) to stringResource(R.string.share_result_sent_none_body, peer)
            short -> countString(R.plurals.share_result_sent_partial, result.total, result.games, result.total) to
                stringResource(R.string.share_result_sent_partial_body, peer, result.games)
            else -> countString(R.plurals.share_result_sent_title, result.games, result.games) to stringResource(R.string.share_result_sent_body, peer)
        }
        ResultKind.CANCELLED -> stringResource(R.string.share_result_cancelled_title) to
            if (result.outgoing) stringResource(R.string.share_result_cancelled_body, peer) else stringResource(R.string.share_result_cancelled_body_here)
        ResultKind.DECLINED -> stringResource(R.string.share_result_declined_title) to stringResource(R.string.share_result_declined_body, peer)
        ResultKind.EXPIRED -> stringResource(R.string.share_result_expired_title) to stringResource(R.string.share_result_expired_body, peer)
        ResultKind.CONNECTION_LOST -> stringResource(R.string.share_result_lost_title) to stringResource(R.string.share_result_lost_body, peer)
        ResultKind.FAILED -> stringResource(R.string.share_result_failed_title) to messageText(result.error ?: ShareMessage.GENERIC, peer)
    }
    val (badge, tint, icon) = when {
        short -> Triple(Oe.WarnBg, Oe.Warn, Icons.Warn)
        ok -> Triple(Oe.OkBg, Oe.Ok, Icons.Check)
        else -> Triple(Oe.DangerBg, Oe.Danger, Icons.Close)
    }
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(badge),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, tint, 28.dp, stroke = 2.4f) }
        Text(title, style = Type.Title.copy(fontSize = 22.sp), textAlign = TextAlign.Center)
        Text(body, style = Type.Small.copy(fontSize = 14.sp), textAlign = TextAlign.Center)
        if (result.kind == ResultKind.RECEIVED && result.keptNewer > 0) {
            Text(countString(R.plurals.share_result_kept, result.keptNewer, result.keptNewer), style = Type.Small, textAlign = TextAlign.Center)
        }
        if (short) {
            Text(
                stringResource(if (result.outgoing) R.string.share_result_not_sent else R.string.share_result_not_received).uppercase(),
                style = Type.SectionCaps, modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            )
            Card {
                result.missing.forEachIndexed { i, game ->
                    val reason = if (result.outgoing) sentSkipText(game.reason, peer) else skipText(game.reason.orEmpty())
                    ListRow(game.title, subtitle = consoleShort(game.console) + " · " + reason, divider = i > 0, subtitleLines = 2)
                }
            }
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.share_close), onClose, Modifier.weight(1f))
            if (onShow != null) PrimaryButton(stringResource(R.string.share_result_show), onShow, Modifier.weight(1f))
        }
    }
}
