package com.openemux.mobile.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.ui.unit.dp
import com.openemux.mobile.share.SendPart
import com.openemux.mobile.share.SendPrep
import com.openemux.mobile.share.SendSelection
import com.openemux.mobile.share.Tri
import com.openemux.mobile.ui.BackLink
import com.openemux.mobile.ui.Card
import com.openemux.mobile.ui.DISABLED_ALPHA
import com.openemux.mobile.ui.FocusMargin
import com.openemux.mobile.ui.GamepadButtons
import com.openemux.mobile.ui.Icon
import com.openemux.mobile.ui.Icons
import com.openemux.mobile.ui.LocalActions
import com.openemux.mobile.ui.MarginFocusScroll
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.PrimaryButton
import com.openemux.mobile.ui.Type
import com.openemux.mobile.ui.countString
import com.openemux.mobile.ui.gamepadFocus
import com.openemux.mobile.ui.hintBarInset
import com.openemux.mobile.ui.restorableFocus
import com.retroarch.R

/** Route `share/items` (spec 02): the checklist as a page of its own, on a phone. */
@Composable
fun WhatToSendScreen() {
    val a = LocalActions.current
    val share = a.app.shareCoordinator
    DisposableEffect(Unit) {
        share.openScreen(send = true)
        onDispose { share.closeScreen() }
    }
    val prep by share.sendPrep.collectAsState()
    val ready = prep as? SendPrep.Ready
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        MarginFocusScroll(trailing = FocusMargin + hintBarInset) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(6.dp))
                BackLink(stringResource(R.string.share_title)) { a.nav.popBackStack() }
                Text(stringResource(R.string.share_items_title), style = Type.Title, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                if (ready != null) WhatToSendList(ready.selection, share::updateSelection)
                Spacer(Modifier.height(20.dp))
            }
        }
        if (ready != null) {
            val s = ready.selection
            Column(Modifier.fillMaxWidth().background(Oe.Sheet)) {
                HorizontalDivider(color = Oe.Border, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sizeText(s.totalBytes), style = Type.Section)
                        Text(gamesAndFiles(s.gameCount, s.fileCount), style = Type.Small)
                    }
                    PrimaryButton(stringResource(R.string.share_done), { a.nav.popBackStack() }, height = 48.dp, sidePadding = 28.dp)
                }
                Spacer(Modifier.height(hintBarInset))
            }
        }
    }
}

@Composable
fun gamesAndFiles(games: Int, files: Int): String =
    countString(R.plurals.share_games, games, games) + " · " + countString(R.plurals.share_files, files, files)

/** One card per game, its parts underneath when expanded; the excluded games last, greyed out. */
@Composable
fun WhatToSendList(selection: SendSelection, onChange: (SendSelection) -> Unit) {
    var focusedGame by remember { mutableStateOf<String?>(null) }
    val focus = rememberSaveable { mutableStateOf<String?>(null) }
    GamepadButtons(
        hints = listOf(
            "A" to stringResource(R.string.share_hint_toggle), "X" to stringResource(R.string.share_hint_toggle_game),
            "B" to stringResource(R.string.settings_hint_back),
        ),
        x = { focusedGame?.let { onChange(selection.toggleGame(it)) } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        selection.games.forEachIndexed { index, game ->
            var expanded by rememberSaveable(game.id) { mutableStateOf(false) }
            val state = selection.state(game.id)
            val onFocus = Modifier.onFocusChanged { if (it.hasFocus) focusedGame = game.id }
            Card(onFocus) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp)
                        .restorableFocus("game-${game.id}", focus, initial = index == 0)
                        .gamepadFocus(corner = 0.dp, inset = true)
                        .triStateToggleable(state.toggleable(), role = Role.Checkbox) { onChange(selection.toggleGame(game.id)) }
                        .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TriCheck(state, 22)
                    Column(Modifier.weight(1f)) {
                        Text(game.title, style = Type.BodyMedium, maxLines = 2)
                        val on = game.parts.count { selection.isChecked(game.id, it.part) }
                        val items = if (on == game.parts.size) countString(R.plurals.share_items_count, on, on)
                        else countString(R.plurals.share_items_of, game.parts.size, on, game.parts.size)
                        Text("${consoleShort(game.console)} · $items · ${sizeText(selection.gameBytes(game.id))}", style = Type.Small)
                    }
                    val label = stringResource(if (expanded) R.string.share_items_collapse else R.string.share_items_expand)
                    Box(
                        Modifier.size(44.dp).gamepadFocus(corner = 22.dp).clip(CircleShape)
                            .clickable(onClickLabel = label, role = Role.Button) { expanded = !expanded },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Chevron, Oe.Chevron, 18.dp, modifier = Modifier.rotate(if (expanded) 270f else 90f)) }
                }
                if (expanded) game.parts.forEach { part ->
                    HorizontalDivider(color = Oe.Divider, thickness = 1.dp)
                    val checked = selection.isChecked(game.id, part.part)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .gamepadFocus(corner = 0.dp, inset = true)
                            .triStateToggleable(if (checked) ToggleableState.On else ToggleableState.Off, role = Role.Checkbox) {
                                onChange(selection.toggle(game.id, part.part))
                            }
                            .padding(start = 48.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TriCheck(if (checked) Tri.CHECKED else Tri.UNCHECKED, 20)
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (part.part == SendPart.STATES) stringResource(R.string.share_items_states_n, part.count) else partName(part.part),
                                style = Type.Body,
                            )
                            when (part.part) {
                                SendPart.STATES -> Text(stringResource(R.string.share_items_states_hint), style = Type.Small)
                                SendPart.DETAILS -> Text(stringResource(R.string.share_items_details_hint), style = Type.Small)
                                else -> Unit
                            }
                        }
                        if (part.part != SendPart.DETAILS) Text(sizeText(part.bytes), style = Type.Small)
                    }
                }
            }
        }
        selection.excluded.forEach { ex ->
            Card(Modifier.alpha(DISABLED_ALPHA)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TriCheck(Tri.UNCHECKED, 22)
                    Column(Modifier.weight(1f)) {
                        Text(ex.title, style = Type.BodyMedium, maxLines = 2)
                        Text("${consoleShort(ex.console)} · ${skipText(ex.reason)}", style = Type.Small)
                    }
                }
            }
        }
    }
}

private fun Tri.toggleable() = when (this) {
    Tri.CHECKED -> ToggleableState.On
    Tri.MIXED -> ToggleableState.Indeterminate
    Tri.UNCHECKED -> ToggleableState.Off
}

/** The design's check box: filled with a tick, filled with a dash, or an empty outline. */
@Composable
fun TriCheck(state: Tri, size: Int) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(size.dp).clip(shape)
            .background(if (state == Tri.UNCHECKED) Color.Transparent else Oe.Accent)
            .let { if (state == Tri.UNCHECKED) it.border(2.dp, Oe.Chevron, shape) else it },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            Tri.CHECKED -> Icon(Icons.Check, Color.White, (size - 6).dp, stroke = 3f)
            Tri.MIXED -> Box(Modifier.width((size / 2).dp).height(2.5.dp).clip(CircleShape).background(Color.White))
            Tri.UNCHECKED -> Unit
        }
    }
}
