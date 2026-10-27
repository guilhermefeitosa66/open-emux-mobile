package com.openemux.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The saved-state key a screen of its own hands its item back under, when a resize makes it a pane again. */
const val PICKED = "openemux.picked"

/** The list's handle on its picked item, so a gamepad leaving the detail lands on it; null outside a split's list. */
val LocalPaneOpener = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * Marks the list item whose detail sits beside the list while [picked], so Back
 * from the detail focuses it again. Goes before the item's own focus target.
 */
@Composable
fun Modifier.paneOpener(picked: Boolean): Modifier {
    val opener = LocalPaneOpener.current
    return if (picked && opener != null) this.focusRequester(opener) else this
}

/**
 * A list with the picked item's detail beside it. Exactly one of [listWidth]
 * and [detailWidth] is set; the other side takes the rest. Back focuses the
 * list again (gamepad in the detail), on the item marked with [paneOpener]
 * (as Left out of the detail does),
 * then clears the pick, then leaves the screen; each side brings focus back to
 * the item it last had.
 */
@Composable
fun ListDetailScaffold(
    listWidth: Dp?,
    detailWidth: Dp?,
    selected: Boolean,
    onClear: () -> Unit,
    list: @Composable () -> Unit,
    detail: @Composable (focusInDetail: Boolean) -> Unit,
    detailBackground: Color = Color.Transparent,
) {
    val listFocus = remember { FocusRequester() }
    val opener = remember { FocusRequester() }
    var focusInDetail by remember { mutableStateOf(false) }
    // Composed with the screen's NavHost entry, so it goes when the screen goes.
    BackHandler(enabled = backStep(selected, focusInDetail, InputMode.gamepad) != BackStep.Leave) {
        when (backStep(selected, focusInDetail, InputMode.gamepad)) {
            // The list's own restorer only knows its first group, which would start over at the top.
            // Without a picked item in view (scrolled off a lazy grid), the list's restorer still takes focus back.
            BackStep.FocusList -> if (!runCatching { opener.requestFocus() }.getOrDefault(false)) runCatching { listFocus.requestFocus() }
            BackStep.ClearSelection -> onClear()
            BackStep.Leave -> {}
        }
    }
    Row(Modifier.fillMaxSize()) {
        Box(
            // Left from the detail lands on the picked item; with it scrolled off, the restorer below takes over.
            side(listWidth)
                .focusProperties { onEnter = { if (entersOnPicked(requestedFocusDirection)) runCatching { opener.requestFocus() } } }
                .focusGroup()
                .focusRequester(listFocus).focusRestorer().focusGroup(),
        ) {
            CompositionLocalProvider(LocalPaneOpener provides opener, content = list)
        }
        VerticalDivider(color = Oe.Surface2, thickness = 1.dp)
        Box(
            side(detailWidth).background(detailBackground)
                .onFocusChanged { focusInDetail = it.hasFocus }.focusRestorer().focusGroup(),
        ) { CompositionLocalProvider(LocalPaneOpener provides null) { detail(focusInDetail) } }
    }
}

/** A side of the split: [width] wide, or the room the other side leaves. */
private fun RowScope.side(width: Dp?): Modifier =
    if (width != null) Modifier.width(width).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()

/** The quiet stand-in of a detail pane with nothing picked. */
@Composable
fun PanePlaceholder(icon: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Oe.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, Oe.Muted, 28.dp)
        }
        Text(text, style = Type.Small, textAlign = TextAlign.Center)
    }
}

/** Calls [onChange] when [split] crosses over after the first composition, never for how it started. */
@Composable
fun OnSplitChange(split: Boolean, onChange: (SplitChange) -> Unit) {
    // Saveable: a density change recreates the activity, and the crossing must still be seen.
    val before = rememberSaveable { mutableStateOf(split) }
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(split) {
        splitChange(before.value, split)?.let { latest(it) }
        before.value = split
    }
}

/**
 * On a screen of its own opened from a list + detail [parent]: when a resize
 * gives the parent its pane back, hands [key] to it and goes back there.
 */
@Composable
fun ReturnToSplit(split: Boolean, parent: String, key: String) {
    val nav = LocalActions.current.nav
    OnSplitChange(split) { change ->
        val previous = nav.previousBackStackEntry
        if (change == SplitChange.Joined && previous?.destination?.route == parent) {
            previous.savedStateHandle[PICKED] = key
            nav.popBackStack()
        }
    }
}
