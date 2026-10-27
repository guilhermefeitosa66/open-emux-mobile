package com.openemux.mobile.ui.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openemux.mobile.share.Discovery
import com.openemux.mobile.share.Peer
import com.openemux.mobile.share.SendPrep
import com.openemux.mobile.share.SendSelection
import com.openemux.mobile.share.ShareAvailability
import com.openemux.mobile.share.ShareFormat
import com.openemux.mobile.share.ShareProtocol
import com.openemux.mobile.share.TransferState
import com.openemux.mobile.ui.BackLink
import com.openemux.mobile.ui.Card
import com.openemux.mobile.ui.EmptyState
import com.openemux.mobile.ui.FitText
import com.openemux.mobile.ui.FocusMargin
import com.openemux.mobile.ui.GamepadButtons
import com.openemux.mobile.ui.Icon
import com.openemux.mobile.ui.Icons
import com.openemux.mobile.ui.ListRow
import com.openemux.mobile.ui.LocalActions
import com.openemux.mobile.ui.LocalWindowLayout
import com.openemux.mobile.ui.MarginFocusScroll
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.Routes
import com.openemux.mobile.ui.SecondaryButton
import com.openemux.mobile.ui.Type
import com.openemux.mobile.ui.countString
import com.openemux.mobile.ui.hintBarInset
import com.openemux.mobile.ui.restorableFocus
import com.retroarch.R

/**
 * Routes `share` and `share/receive` (spec 01, 04, 07, 08, 11): the devices on
 * the network, this device, and, while sending, the wait, the progress and the
 * result in place of the list. [receive] is the mode the Import tab opens: no
 * games to send, the list read-only.
 */
@Composable
fun ShareScreen(receive: Boolean) {
    val a = LocalActions.current
    val share = a.app.shareCoordinator
    DisposableEffect(Unit) {
        share.openScreen(send = !receive)
        onDispose {
            // A send's result belongs to this screen: the next Share opens on the devices, not on it.
            if ((share.transfer.value as? TransferState.Finished)?.result?.outgoing == true && !receive) share.dismissResult()
            share.closeScreen()
        }
    }
    AskNotificationsOnce()
    val transfer by share.transfer.collectAsState()
    val availability by share.availability.collectAsState()
    val lan by share.lan.collectAsState()
    val prep by share.sendPrep.collectAsState()
    val roms by share.sendRoms.collectAsState()
    val outgoing = !receive && when (val t = transfer) {
        is TransferState.Waiting -> true
        is TransferState.Running -> t.outgoing
        is TransferState.Finished -> t.result.outgoing
        TransferState.Idle -> false
    }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = outgoing && transfer.busy) { confirmCancel = true }
    if (confirmCancel) CancelTransferDialog({ confirmCancel = false }) { confirmCancel = false; share.cancel() }
    GamepadButtons(
        hints = listOf(
            "A" to stringResource(if (receive) R.string.settings_hint_open else R.string.share_hint_send),
            "B" to stringResource(R.string.settings_hint_back),
        ),
    )
    val selection = (prep as? SendPrep.Ready)?.selection
    val wide = LocalWindowLayout.current.wide && !receive

    MarginFocusScroll(trailing = FocusMargin + hintBarInset) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
                BackLink(stringResource(if (receive) R.string.settings_tab_import else R.string.settings_tab_library)) {
                    if (outgoing && transfer.busy) confirmCancel = true else a.nav.popBackStack()
                }
                FitText(stringResource(if (receive) R.string.share_receive_title else R.string.share_title), Type.Display, Modifier.padding(top = 4.dp))
                Text(
                    when {
                        receive -> stringResource(R.string.share_receive_subtitle)
                        selection != null -> countString(R.plurals.share_subtitle, selection.gameCount, selection.gameCount, sizeText(selection.totalBytes))
                        else -> countString(R.plurals.share_games, roms.size, roms.size)
                    },
                    style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp),
                )
            }
            val unavailable = availability as? ShareAvailability.Unavailable
            when {
                outgoing -> Column(Modifier.padding(20.dp)) {
                    when (val t = transfer) {
                        is TransferState.Waiting -> WaitingView(t) { confirmCancel = true }
                        is TransferState.Running -> ProgressView(t) { share.cancel() }
                        is TransferState.Finished -> ResultView(t.result, { share.dismissResult(); a.nav.popBackStack() }, null)
                        TransferState.Idle -> Unit
                    }
                }
                unavailable != null -> EmptyState(
                    stringResource(R.string.share_unavailable_title),
                    messageText(ShareFormat.errorKey(unavailable.reason)), null, {},
                )
                !lan -> EmptyState(stringResource(R.string.share_unavailable_title), stringResource(R.string.share_unavailable_wifi), null, {})
                wide -> Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f)) { Devices(receive, selection) }
                    Column(Modifier.weight(1f).padding(top = 22.dp)) {
                        Text(stringResource(R.string.share_items_title), style = Type.Section, modifier = Modifier.padding(bottom = 10.dp))
                        when (val p = prep) {
                            is SendPrep.Ready -> WhatToSendList(p.selection, share::updateSelection)
                            else -> PrepLine(p)
                        }
                    }
                }
                else -> Column(Modifier.padding(horizontal = 20.dp)) {
                    if (!receive) {
                        Spacer(Modifier.height(16.dp))
                        Card {
                            when (val p = prep) {
                                is SendPrep.Ready -> ListRow(
                                    stringResource(R.string.share_items_title), subtitle = whatSummary(p.selection), minHeight = 60.dp,
                                    onClick = { a.nav.navigate(Routes.SHARE_ITEMS) },
                                )
                                else -> Box(Modifier.padding(14.dp)) { PrepLine(p) }
                            }
                        }
                    }
                    Devices(receive, selection)
                }
            }
            Spacer(Modifier.height(28.dp + hintBarInset))
        }
    }
}

/** The manifest being built, or why it could not be. */
@Composable
private fun PrepLine(prep: SendPrep) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (prep) {
            is SendPrep.Failed -> Text(messageText(prep.message), style = Type.Small.copy(color = Oe.Danger))
            else -> {
                Text(stringResource(R.string.share_preparing), style = Type.Body)
                val b = prep as? SendPrep.Building
                if (b != null && b.total > 0) {
                    LinearProgressIndicator({ b.done.toFloat() / b.total }, Modifier.fillMaxWidth(), color = Oe.AccentBright, trackColor = Oe.Divider)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Oe.AccentBright, trackColor = Oe.Divider)
                }
            }
        }
    }
}

/** "Super Metroid, Zelda and 3 more · 14 files". */
@Composable
private fun whatSummary(selection: SendSelection): String {
    val titles = selection.games.filter { it.parts.any { p -> selection.isChecked(it.id, p.part) } }.map { it.title }
    val names = when (titles.size) {
        0 -> stringResource(R.string.share_items_none)
        1 -> titles[0]
        2 -> stringResource(R.string.share_what_two, titles[0], titles[1])
        else -> countString(R.plurals.share_what_more, titles.size - 2, titles[0], titles[1], titles.size - 2)
    }
    return names + " · " + countString(R.plurals.share_files, selection.fileCount, selection.fileCount)
}

/** "DEVICES ON THIS NETWORK", the nothing-found state, and "THIS DEVICE". */
@Composable
private fun Devices(receive: Boolean, selection: SendSelection?) {
    val a = LocalActions.current
    val share = a.app.shareCoordinator
    val peers by share.peers.collectAsState()
    val searching by share.searching.collectAsState()
    val nothingFound by share.nothingFound.collectAsState()
    val warnings by share.warnings.collectAsState()
    val name by share.deviceName.collectAsState()
    val focus = rememberSaveable { mutableStateOf<String?>(null) }
    val canSend = !receive && selection != null && !selection.isEmpty

    if (Discovery.WARNING_LOCK_REFUSED in warnings) {
        Row(
            Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Oe.WarnBg).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Warn, Oe.WarnText, 18.dp)
            Text(stringResource(R.string.share_warning_multicast), style = Type.Small.copy(color = Oe.WarnText))
        }
    }
    Row(Modifier.padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.share_devices).uppercase(), style = Type.SectionCaps, modifier = Modifier.weight(1f))
        if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = Oe.AccentText, strokeWidth = 2.dp)
    }
    if (nothingFound && peers.none { it.compatible }) {
        NothingFoundCard()
    } else {
        if (!receive && selection != null && selection.isEmpty) {
            Text(stringResource(R.string.share_choose_something), style = Type.Small, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (peers.isNotEmpty()) Card {
            peers.forEachIndexed { i, peer ->
                key(peer.id, peer.fp) {
                    val enabled = peer.compatible && (receive || canSend)
                    Box(Modifier.restorableFocus("peer-${peer.id}-${peer.fp}", focus, initial = i == 0)) {
                        PeerRow(peer, i > 0, enabled, if (receive) null else ({ share.send(peer) }))
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.share_this_device).uppercase(), style = Type.SectionCaps, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    Card {
        ListRow(
            stringResource(R.string.share_visible_as, name ?: "…"), subtitle = stringResource(R.string.share_visible_hint),
            subtitleLines = 2, minHeight = 60.dp,
        )
        ListRow(
            stringResource(R.string.share_address_title), divider = true, minHeight = 56.dp,
            leading = { Icon(Icons.Qr, Oe.AccentText, 22.dp) },
            onClick = { a.nav.navigate(Routes.shareAddress("")) },
        )
    }
}

@Composable
private fun PeerRow(peer: Peer, divider: Boolean, enabled: Boolean, onClick: (() -> Unit)?) {
    val platform = platformName(peer.platform)
    val subtitle = if (peer.compatible) {
        stringResource(R.string.share_device_subtitle, platform, pathsText(peer.paths))
    } else {
        stringResource(R.string.share_device_incompatible, platform) + "\n" +
            stringResource(R.string.share_device_versions, peer.versions.max.toString(), ShareProtocol.VERSION.toString())
    }
    ListRow(
        peer.name, subtitle = subtitle, divider = divider, minHeight = 64.dp, subtitleLines = 2, enabled = enabled,
        onClick = if (enabled) onClick else null,
        leading = {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Oe.AccentTint), contentAlignment = Alignment.Center) {
                Icon(
                    when (ShareFormat.platformOf(peer.platform)) {
                        "phone" -> Icons.Phone
                        "tablet" -> Icons.Tablet
                        else -> Icons.Desktop
                    },
                    Oe.AccentText, 22.dp,
                )
            }
        },
    )
}

/** Spec 04: what to try when nobody shows up. */
@Composable
private fun NothingFoundCard() {
    val a = LocalActions.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 8.dp).size(64.dp).clip(RoundedCornerShape(20.dp)).background(Oe.Surface), contentAlignment = Alignment.Center) {
            Icon(Icons.WifiOff, Oe.AccentText, 30.dp)
        }
        Text(stringResource(R.string.share_empty_title), style = Type.Title, textAlign = TextAlign.Center)
        Text(stringResource(R.string.share_empty_body), style = Type.Small, textAlign = TextAlign.Center)
        Card {
            Note(Icons.Warn, Oe.Warn, stringResource(R.string.share_empty_guest_lead), stringResource(R.string.share_empty_guest))
            HorizontalDivider(color = Oe.Divider, thickness = 1.dp)
            Note(Icons.Desktop, Oe.Muted, stringResource(R.string.share_empty_firewall_lead), stringResource(R.string.share_empty_firewall))
        }
        SecondaryButton(stringResource(R.string.share_address_title), { a.nav.navigate(Routes.shareAddress("")) }, Modifier.fillMaxWidth(), icon = Icons.Qr)
        Row(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Oe.Muted, strokeWidth = 2.dp)
            Text(stringResource(R.string.share_empty_searching), style = Type.Small)
        }
    }
}

@Composable
private fun Note(icon: String, tint: androidx.compose.ui.graphics.Color, lead: String, text: String) {
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, tint, 20.dp)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Oe.Text)) { append(lead) }
                append(" ")
                append(text)
            },
            style = Type.Small,
        )
    }
}

/** The notification permission (Android 13+), asked once, the first time a share screen opens. */
@Composable
private fun AskNotificationsOnce() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("openemux", Context.MODE_PRIVATE)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted || prefs.getBoolean(NOTIF_ASKED, false)) return@LaunchedEffect
        prefs.edit().putBoolean(NOTIF_ASKED, true).apply()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val NOTIF_ASKED = "share_notif_asked"
