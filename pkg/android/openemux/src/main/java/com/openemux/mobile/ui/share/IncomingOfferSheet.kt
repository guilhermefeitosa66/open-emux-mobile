package com.openemux.mobile.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.Console
import com.openemux.mobile.share.Conflict
import com.openemux.mobile.share.OfferGame
import com.openemux.mobile.share.OfferModel
import com.openemux.mobile.share.ShareCoordinator
import com.openemux.mobile.share.TransferState
import com.openemux.mobile.ui.BackLink
import com.openemux.mobile.ui.Card
import com.openemux.mobile.ui.ConsoleChip
import com.openemux.mobile.ui.GamepadButtons
import com.openemux.mobile.ui.Icon
import com.openemux.mobile.ui.Icons
import com.openemux.mobile.ui.ListRow
import com.openemux.mobile.ui.SheetBarIcons
import com.openemux.mobile.ui.LocalActions
import com.openemux.mobile.ui.MarginFocusScroll
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.PrimaryButton
import com.openemux.mobile.ui.Routes
import com.openemux.mobile.ui.SecondaryButton
import com.openemux.mobile.ui.Type
import com.openemux.mobile.ui.countString
import com.openemux.mobile.ui.gamepadFocus
import com.openemux.mobile.ui.restorableFocus
import com.retroarch.R
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The incoming offer over any screen of the main activity (spec 05/06), then
 * the receive's progress and result. Never dismissed by a drag or a tap
 * outside: back (and B) declines, Decline has the first focus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingOfferSheet() {
    val a = LocalActions.current
    val share = a.app.shareCoordinator
    val offer by share.pendingOffer.collectAsState()
    val transfer by share.transfer.collectAsState()
    val receiving = when (val t = transfer) {
        is TransferState.Running -> !t.outgoing
        is TransferState.Finished -> !t.result.outgoing
        else -> false
    }
    if (offer == null && !receiving) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    var page by rememberSaveable { mutableStateOf(Page.OFFER) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        {}, sheetState = state, containerColor = Oe.Sheet,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        SheetBarIcons()
        BackHandler {
            when {
                offer != null && page == Page.CONFLICTS -> page = Page.OFFER
                offer != null -> share.decline()
                transfer.busy -> confirmCancel = true
                else -> share.dismissResult()
            }
        }
        MarginFocusScroll {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                val model = offer
                when {
                    model != null && page == Page.CONFLICTS -> ConflictsPage(model, share) { page = Page.OFFER }
                    model != null -> OfferPage(model, share) { page = Page.CONFLICTS }
                    else -> when (val t = transfer) {
                        is TransferState.Running -> ProgressView(t) { share.cancel() }
                        is TransferState.Finished -> {
                            val console = t.result.firstConsole
                            ResultView(
                                t.result, { share.dismissResult() },
                                console?.let { { share.dismissResult(); a.nav.navigate(Routes.library(it)) } },
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    if (confirmCancel) CancelTransferDialog({ confirmCancel = false }) { confirmCancel = false; share.cancel() }
}

private enum class Page { OFFER, CONFLICTS }

@Composable
private fun OfferPage(model: OfferModel, share: ShareCoordinator, onConflicts: () -> Unit) {
    val focus = rememberSaveable { mutableStateOf<String?>(null) }
    GamepadButtons(
        hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.share_offer_decline)),
    )
    var now by remember { mutableLongStateOf(share.now()) }
    LaunchedEffect(model.offer.offerId) {
        while (true) {
            now = share.now()
            delay(1000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.share_offer_title), style = Type.Title)
        val games = model.games.size
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Oe.Text)) { append(model.sender) }
                append(" ")
                append(countString(R.plurals.share_offer_lead, games, games, sizeText(model.totalBytes)))
            },
            style = Type.Small.copy(fontSize = 15.sp, lineHeight = 21.sp),
        )
        CodeCard(model.code, stringResource(R.string.share_offer_code_hint, model.sender))
        Card {
            model.games.forEachIndexed { i, game ->
                if (i > 0) HorizontalDivider(color = Oe.Divider, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Console.byId(game.console)?.let { ConsoleChip(it, 36.dp) }
                    Column(Modifier.weight(1f)) {
                        Text(game.title, style = Type.BodyMedium, maxLines = 2)
                        Text(gameSummary(game), style = Type.Small)
                    }
                }
            }
        }
        val states = model.incompatibleStates
        if (states > 0) NoteLine(Icons.Warn, Oe.Warn, countString(R.plurals.share_offer_note_states, states, states))
        model.unsupported.forEach { NoteLine(Icons.Info, Oe.Muted, stringResource(R.string.share_offer_note_unsupported, it.title, consoleShort(it.console))) }
        val conflicts = model.conflicts().size
        if (conflicts > 0) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).gamepadFocus(corner = 14.dp).clip(RoundedCornerShape(14.dp)).background(Oe.WarnBg)
                    .selectable(false, role = Role.Button, onClick = onConflicts).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Warn, Oe.WarnText, 18.dp)
                Text(countString(R.plurals.share_offer_conflicts, conflicts, conflicts), style = Type.BodyMedium.copy(color = Oe.WarnText), modifier = Modifier.weight(1f))
                Icon(Icons.Chevron, Oe.WarnText, 16.dp)
            }
        }
        SpaceError(model)
        model.error?.let { Text(messageText(it, model.sender), style = Type.Small.copy(color = Oe.Danger)) }
        val left = model.secondsLeft(now)
        Text(
            stringResource(R.string.share_offer_expires, "%d:%02d".format(left / 60, left % 60)),
            style = Type.Small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                stringResource(R.string.share_offer_decline), { share.decline() },
                Modifier.weight(1f).restorableFocus("decline", focus, initial = true),
            )
            PrimaryButton(stringResource(R.string.share_offer_accept), { share.accept() }, Modifier.weight(1f), enabled = model.spaceOk)
        }
    }
}

@Composable
private fun gameSummary(game: OfferGame): String {
    val parts = listOfNotNull(
        if (game.hasRom) stringResource(R.string.share_offer_part_game) else null,
        if (game.saves > 0) countString(R.plurals.share_offer_part_saves, game.saves, game.saves) else null,
        if (game.states > 0) countString(R.plurals.share_offer_part_states, game.states, game.states) else null,
        if (game.cover) stringResource(R.string.share_offer_part_cover) else null,
        if (game.label) stringResource(R.string.share_offer_part_label) else null,
    )
    return parts.joinToString(", ") + " · " + sizeText(game.bytes)
}

@Composable
private fun NoteLine(icon: String, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, tint, 18.dp)
        Text(text, style = Type.Small)
    }
}

/** "Not enough space…": Accept stays off until the choices make it fit. */
@Composable
private fun SpaceError(model: OfferModel) {
    val (needed, free, missing) = model.shortage ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Oe.DangerBg).padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Alert, Oe.Danger, 18.dp)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.share_space_lead)) }
                append(" ")
                append(stringResource(R.string.share_space_body, sizeText(needed), sizeText(free), sizeText(missing)))
            },
            style = Type.Small.copy(color = Oe.Danger),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictsPage(model: OfferModel, share: ShareCoordinator, onBack: () -> Unit) {
    GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.settings_hint_back)))
    val focus = rememberSaveable { mutableStateOf<String?>(null) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackLink(stringResource(R.string.share_offer_title), onBack)
        Text(stringResource(R.string.share_conflicts_title), style = Type.Title)
        Text(stringResource(R.string.share_conflicts_hint), style = Type.Small)
        model.conflicts().forEachIndexed { index, c ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${c.title} · ${kindName(c.kind)}", style = Type.BodyMedium)
                    DateLine(R.string.share_conflicts_yours, format.format(Date(c.yours)), c.yoursNewer)
                    DateLine(R.string.share_conflicts_incoming, format.format(Date(c.incoming)), c.incomingNewer)
                    FlowRow(
                        Modifier.padding(top = 8.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        c.allowed.forEachIndexed { i, policy ->
                            PolicyChip(c, policy, Modifier.restorableFocus("c-${c.itemId}-$policy", focus, initial = index == 0 && i == 0)) {
                                share.setOverride(c.itemId, policy)
                            }
                        }
                    }
                }
            }
        }
        if (model.skipped.isNotEmpty()) {
            Text(stringResource(R.string.share_conflicts_skipped).uppercase(), style = Type.SectionCaps, modifier = Modifier.padding(top = 10.dp))
            Card {
                model.skipped.forEachIndexed { i, s ->
                    ListRow("${s.title} · ${kindName(s.kind)}", subtitle = skipText(s.reason), divider = i > 0, subtitleLines = 2)
                }
            }
        }
        SpaceError(model)
    }
}

@Composable
private fun DateLine(label: Int, date: String, newer: Boolean) {
    Text(
        buildAnnotatedString {
            append(stringResource(label, date))
            if (newer) withStyle(SpanStyle(color = Oe.OkText, fontWeight = FontWeight.SemiBold)) {
                append(" · ")
                append(stringResource(R.string.share_conflicts_newer))
            }
        },
        style = Type.Small,
    )
}

@Composable
private fun PolicyChip(conflict: Conflict, policy: com.openemux.mobile.share.Policy, modifier: Modifier, onClick: () -> Unit) {
    val selected = conflict.policy == policy
    Box(
        modifier.height(36.dp).gamepadFocus(corner = 18.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) Oe.AccentSoft else Oe.Surface2)
            .let { if (selected) it else it.border(1.dp, Oe.Border, RoundedCornerShape(18.dp)) }
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(policyName(policy), style = Type.Section.copy(fontSize = 14.sp, color = if (selected) Oe.AccentOnSoft else Oe.Text), maxLines = 1)
    }
}
