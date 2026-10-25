package com.openemux.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Tabletop: a foldable half open with the fold across, the bottom half on the
// table. The screen keeps its content on the top half; its actions (its deck)
// and the tabs go to the bottom half; the band around the fold stays empty.

/**
 * What the bottom half shows: the deck of the screen on top. Owned, so a
 * screen leaving after the next one arrived (a navigation's cross-fade)
 * cannot clear the newcomer's deck.
 */
class DeckSlot<T> {
    private var owner: Any? = null

    var content by mutableStateOf<T?>(null)
        private set

    fun set(owner: Any, content: T) {
        this.owner = owner
        this.content = content
    }

    fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        content = null
    }
}

/** True while the window is in tabletop: screens leave their deck out of the top half. */
val LocalTabletop = staticCompositionLocalOf { false }

val LocalDeckSlot = staticCompositionLocalOf { DeckSlot<@Composable () -> Unit>() }

/** Puts [content] on the bottom half while this screen is on screen. Only call it in tabletop. */
@Composable
fun TabletopDeck(content: @Composable () -> Unit) {
    val slot = LocalDeckSlot.current
    val latest = rememberUpdatedState(content)
    val owner = remember { Any() }
    DisposableEffect(slot, owner) {
        val deck: @Composable () -> Unit = { latest.value() }
        slot.set(owner, deck)
        onDispose { slot.clear(owner) }
    }
}

/**
 * The band and the bottom half, under the top half: a hairline on the fold,
 * then the deck, the gamepad hints and, on the tab screens, the tabs.
 * B from the bottom half takes the gamepad back up to the content.
 */
@Composable
fun ColumnScope.TabletopBottom(band: Band, route: String?, showTabs: Boolean, top: FocusRequester) {
    Box(Modifier.fillMaxWidth().height((band.endDp - band.startDp).dp), contentAlignment = Alignment.Center) {
        HorizontalDivider(color = Oe.Surface2, thickness = 1.dp)
    }
    var focused by remember { mutableStateOf(false) }
    BackHandler(enabled = focused && InputMode.gamepad) { runCatching { top.requestFocus() } }
    Column(Modifier.fillMaxWidth().weight(1f).onFocusChanged { focused = it.hasFocus }.focusGroup()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LocalDeckSlot.current.content?.invoke()
            ArtworkDoneToast(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        }
        HintBar(GamepadBus.hints)
        if (showTabs) TabletopBar(route)
    }
}

/** The tabs as one row across the bottom half: icon and label side by side, the active one in a pill. */
@Composable
private fun TabletopBar(route: String?) {
    val nav = LocalActions.current.nav
    Column(Modifier.fillMaxWidth().background(Oe.Surface)) {
        HorizontalDivider(color = Oe.Surface2, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(67.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TAB_ITEMS.forEach { tab ->
                val active = tab.isActive(route)
                val color = if (active) Oe.AccentText else Oe.Muted
                Row(
                    Modifier
                        .height(44.dp)
                        .gamepadFocus(corner = 22.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (active) Oe.AccentTint else Oe.Surface)
                        .semantics { selected = active }
                        .clickable(role = Role.Tab) { if (tab.route == Routes.SHELF || tab.route != route) nav.navigateTab(tab.route) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(tab.icon, color, 20.dp)
                    Text(
                        stringResource(tab.label),
                        style = Type.Caption.copy(color = color, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                    )
                }
            }
        }
    }
}
