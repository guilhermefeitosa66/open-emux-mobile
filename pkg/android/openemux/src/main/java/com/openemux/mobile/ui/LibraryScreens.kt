package com.openemux.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.Rom
import com.retroarch.R

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen() {
    val a = LocalActions.current
    val roms by a.app.library.roms.collectAsState()
    val meta by a.app.library.meta.collectAsState()
    val focusedShelf = rememberSaveable { mutableStateOf<String?>(null) }
    val lib = a.app.library
    val present = lib.orderedConsoles(meta.consoleOrder).filter { c -> roms.any { it.console == c } }
    // While a shelf is dragged: the order on screen, the dragged console and how far it is from its slot.
    var live by remember { mutableStateOf<List<Console>?>(null) }
    var dragged by remember { mutableStateOf<Console?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var menuFor by remember { mutableStateOf<Console?>(null) }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    GamepadButtons(
        hints = listOf(
            "A" to stringResource(R.string.library_hint_open), "Y" to stringResource(R.string.library_hint_order),
            "SELECT" to stringResource(R.string.library_search), "L1 R1" to stringResource(R.string.library_hint_tabs),
        ),
        select = { a.nav.navigate(Routes.library("SEARCH")) },
        y = { menuFor = Console.byId(focusedShelf.value) },
    )

    val favoritesShort = stringResource(R.string.library_favorites_eyebrow)
    val favoritesName = stringResource(R.string.library_favorites)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.oe_logo), contentDescription = null, modifier = Modifier.height(34.dp))
                // On a narrow screen the credit wraps under the name instead of pushing the button.
                FlowRow(
                    Modifier.padding(start = 10.dp).weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("OpenEmux", style = Type.Title.copy(fontSize = 18.sp))
                    PoweredByRetroArch()
                }
                RoundIconButton(Icons.Search, stringResource(R.string.library_search_all_consoles), { a.nav.navigate(Routes.library("SEARCH")) })
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp)) {
                Text(stringResource(R.string.library_shelf_title), style = Type.Display)
                val consoles = Console.entries.count { c -> roms.any { it.console == c } }
                Text("${countString(R.plurals.library_consoles_count, consoles, consoles)} · ${countString(R.plurals.library_games_count, roms.size, roms.size)}", style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp))
            }
            ArtworkSyncPill(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp))
        }
        if (roms.isEmpty()) {
            item {
                EmptyState(
                    stringResource(R.string.library_shelf_empty_title),
                    stringResource(R.string.library_shelf_empty_body),
                    stringResource(R.string.library_shelf_empty_action),
                    { a.nav.navigate(Routes.IMPORT) },
                )
            }
        } else {
            val favorites = roms.filter { meta.roms[it.key]?.favorite == true }
            val shown = live ?: present
            val shelves = buildList {
                if (favorites.isNotEmpty()) add(Triple(favoritesShort, favoritesName, favorites) to "FAV")
                shown.forEach { c -> add(Triple(c.short, c.displayName, roms.filter { it.console == c }) to c.id) }
            }
            items(shelves, key = { it.second }) { (shelf, filter) ->
                val (short, name, list) = shelf
                val console = Console.byId(filter)
                val (background, ink) = when (filter) {
                    "FAV" ->
                        if (Oe.dark) Brush.linearGradient(listOf(Color(0xFF5B3FA8), Color(0xFF3A2A6E))) to Color.White
                        else Brush.linearGradient(listOf(Color(0xFFDDD3F8), Color(0xFFC6B8F0))) to Color(0xFF2E1F66)
                    else -> console!!.shelf(Oe.dark).let { (back, text) -> SolidColor(back) to text }
                }
                val isDragged = console != null && console == dragged
                val moveUp = stringResource(R.string.library_shelf_move_up)
                val moveDown = stringResource(R.string.library_shelf_move_down)
                ShelfCard(
                    short, name, countString(R.plurals.library_games_count, list.size, list.size), list, background, ink,
                    Modifier
                        .then(if (isDragged) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset; scaleX = 1.03f; scaleY = 1.03f } else Modifier.animateItem())
                        .restorableFocus(filter, focusedShelf, initial = shelves.first().second == filter)
                        .then(
                            if (console == null) Modifier else Modifier
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(moveUp) { lib.moveConsole(console, present, -1) },
                                        CustomAccessibilityAction(moveDown) { lib.moveConsole(console, present, 1) },
                                    )
                                }
                                .reorderGestures(
                                    onStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        live = present; dragged = console; dragOffset = 0f
                                    },
                                    onDrag = { dy ->
                                        dragOffset += dy
                                        val items = listState.layoutInfo.visibleItemsInfo
                                        val self = items.firstOrNull { it.key == console.id }
                                        if (self != null) {
                                            val center = self.offset + self.size / 2 + dragOffset
                                            val target = items.firstOrNull { info ->
                                                info.key != console.id && live?.any { it.id == info.key } == true &&
                                                    center >= info.offset && center < info.offset + info.size
                                            }
                                            if (target != null) {
                                                val order = live!!.toMutableList()
                                                order.remove(console)
                                                order.add(order.indexOfFirst { it.id == target.key }.let { if (target.index > self.index) it + 1 else it }, console)
                                                live = order
                                                // The card is laid out in its new slot: keep it under the finger.
                                                dragOffset += self.offset - target.offset
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onEnd = { moved ->
                                        val order = live
                                        if (moved && order != null) {
                                            // Only the shelves on screen moved; the others keep their slots in the full order.
                                            val it = order.iterator()
                                            lib.setConsoleOrder(lib.orderedConsoles().map { c -> if (c in present) it.next() else c })
                                        } else if (!moved) {
                                            menuFor = console
                                        }
                                        live = null; dragged = null; dragOffset = 0f
                                    },
                                )
                        ),
                ) {
                    a.nav.navigate(Routes.library(filter))
                }
            }
        }
        item { Spacer(BottomSpace) }
    }

    menuFor?.let { console ->
        val i = present.indexOf(console)
        ModalBottomSheet({ menuFor = null }, containerColor = Oe.Sheet) {
            ImmersiveWindow()
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(console.displayName, style = Type.Title.copy(fontSize = 22.sp))
                Text(stringResource(R.string.library_shelf_order_tip), style = Type.Small, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Card {
                    // Only the moves that have somewhere to go, as the desktop sidebar does.
                    var divider = false
                    if (i > 0) {
                        ListRow(stringResource(R.string.library_shelf_move_up), onClick = { lib.moveConsole(console, present, -1); menuFor = null })
                        divider = true
                    }
                    if (i in 0 until present.lastIndex) {
                        ListRow(stringResource(R.string.library_shelf_move_down), divider = divider, onClick = { lib.moveConsole(console, present, 1); menuFor = null })
                        divider = true
                    }
                    if (meta.consoleOrder.isNotEmpty()) {
                        ListRow(stringResource(R.string.library_shelf_reset_order), divider = divider, onClick = { lib.resetConsoleOrder(); menuFor = null })
                    }
                }
            }
        }
    }
}

/**
 * Long press, then drag: reorders. Long press and let go: [onEnd] with false.
 * Watches the Initial pass so that, once the press is long, the card's click
 * and the list's scroll never see the gesture.
 */
private fun Modifier.reorderGestures(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: (moved: Boolean) -> Unit): Modifier = composed {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (released != null) return@awaitEachGesture
            start()
            var travel = 0f
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                val dy = change.position.y - change.previousPosition.y
                travel += kotlin.math.abs(dy)
                if (dy != 0f) drag(dy)
            }
            end(travel > viewConfiguration.touchSlop)
        }
    }
}

@Composable
private fun ShelfCard(short: String, name: String, count: String, roms: List<Rom>, background: Brush, ink: Color, focus: Modifier, onClick: () -> Unit) {
    val a = LocalActions.current
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(108.dp)
            .gamepadFocus(corner = 22.dp, scale = 1.02f)
            .then(focus)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        val fan = roms.sortedByDescending { a.app.library.romMeta(it.key).lastPlayed }.take(3)
        val placements = listOf(Triple(92.dp, 26.dp, -9f), Triple(52.dp, 16.dp, -2f), Triple(8.dp, 30.dp, 7f))
        fan.forEachIndexed { i, rom ->
            val (right, top, angle) = placements[i + (3 - fan.size)]
            val w = when {
                // Tall shells and boxes are sized by height, to stay inside the card.
                rom.console.cartAspect < 1.2f -> 84.dp * rom.console.cartAspect
                rom.console.cartAspect < 1.65f -> 100.dp
                else -> 118.dp
            }
            Box(Modifier.align(Alignment.TopEnd).padding(end = right, top = top).width(w).rotate(angle)) {
                Cartridge(rom.console, labelFor(rom), rom.title, a.app.library.effectiveColor(rom), Modifier.fillMaxWidth(), pending = labelPending(rom), cover = a.app.covers.urlFor(rom))
            }
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 18.dp).width(150.dp)) {
            Text(short, style = Type.Tag.copy(color = ink.copy(alpha = 0.72f), fontSize = 12.sp, letterSpacing = 1.sp))
            Text(name, style = Type.Title.copy(fontSize = 19.sp, lineHeight = 22.sp, color = ink))
            Text(count, style = Type.Small.copy(color = ink.copy(alpha = 0.78f)), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private enum class ViewMode { Carts, Covers, List }
private enum class SortMode(@StringRes val label: Int) { Az(R.string.library_sort_az), Recent(R.string.library_sort_recent) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(filter: String) {
    val a = LocalActions.current
    val lib = a.app.library
    val roms by lib.roms.collectAsState()
    val meta by lib.meta.collectAsState()
    val searchRequested = filter == "SEARCH"
    var mode by rememberSaveable { mutableStateOf(ViewMode.Carts) }
    var sort by rememberSaveable { mutableStateOf(SortMode.Az) }
    var searching by rememberSaveable { mutableStateOf(searchRequested) }
    var query by rememberSaveable { mutableStateOf("") }
    var current by rememberSaveable { mutableStateOf(if (searchRequested) "ALL" else filter) }

    val console = Console.byId(current)
    val list = roms
        .filter {
            when (current) {
                "FAV" -> meta.roms[it.key]?.favorite == true
                "ALL" -> true
                else -> it.console.id == current
            }
        }
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .let { l -> if (sort == SortMode.Recent) l.sortedByDescending { meta.roms[it.key]?.lastPlayed ?: 0 } else l }
    // "All", favorites and search come grouped by console, in the user's order (desktop issue #384);
    // a single console needs no header. The page's sort holds inside each group.
    val groups: List<Pair<Console?, List<Rom>>> =
        if (console != null) listOf(null to list)
        else lib.orderedConsoles(meta.consoleOrder).mapNotNull { c -> list.filter { it.console == c }.takeIf { it.isNotEmpty() }?.let { c to it } }
    val firstKey = groups.firstOrNull()?.second?.firstOrNull()?.key
    val focusedRom = rememberSaveable { mutableStateOf<String?>(null) }
    val chipIds = listOf("ALL", "FAV") + lib.orderedConsoles(meta.consoleOrder).filter { c -> roms.any { it.console == c } }.map { it.id }
    fun chip(step: Int) {
        current = chipIds[(chipIds.indexOf(current).coerceAtLeast(0) + step).coerceIn(0, chipIds.lastIndex)]
        focusedRom.value = null
    }
    GamepadButtons(
        hints = listOf(
            "A" to stringResource(R.string.library_hint_detail), "X" to stringResource(R.string.library_hint_favorite),
            "Y" to stringResource(R.string.library_hint_options), "B" to stringResource(R.string.library_shelf_title),
        ),
        l1 = { chip(-1) },
        r1 = { chip(1) },
        x = { focusedRom.value?.let { key -> lib.updateRom(key) { it.copy(favorite = !it.favorite) } } },
        y = { focusedRom.value?.let { a.nav.navigate(Routes.rom(it)) } },
        select = { searching = !searching; if (!searching) query = "" },
    )

    if (LocalWindowLayout.current.short && mode != ViewMode.List) {
        LandscapeLibrary(
            title = when (current) { "FAV" -> stringResource(R.string.library_favorites); "ALL" -> stringResource(R.string.library_all_games); else -> console!!.displayName },
            list = list, groups = groups, roms = roms, current = current, onPick = { current = it },
            mode = mode, onMode = { mode = it },
            searching = searching, query = query, onQuery = { query = it },
            onSearch = { searching = !searching; if (!searching) query = "" },
            sort = sort, onSort = { sort = if (sort == SortMode.Az) SortMode.Recent else SortMode.Az },
            focusedRom = focusedRom,
        )
        return
    }

    // One game in the grid, in view mode m.
    val tile: @Composable (Rom, ViewMode) -> Unit = { rom, m ->
            val romMeta = meta.roms[rom.key]
            val open = { a.nav.navigate(Routes.rom(rom.key)) }
            val focus = Modifier.restorableFocus(rom.key, focusedRom, initial = rom.key == firstKey)
            when (m) {
                ViewMode.Carts -> CappedCell(GridSpec.Carts) {
                    Column(
                        it.gamepadFocus(corner = 10.dp, scale = 1.06f).then(focus).combinedClickable(onClick = open, onLongClick = open),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Cartridge(rom.console, labelFor(rom), rom.title, lib.effectiveColor(rom), Modifier.fillMaxWidth(), pending = labelPending(rom), cover = a.app.covers.urlFor(rom))
                            if (romMeta?.favorite == true) FavBadge(Modifier.padding(4.dp))
                        }
                        Text(
                            rom.title, style = Type.Caption.copy(color = Oe.Text, fontSize = 13.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                        )
                    }
                }
                ViewMode.Covers -> CappedCell(GridSpec.Covers) {
                    Column(it.gamepadFocus(corner = 10.dp, scale = 1.06f).then(focus).combinedClickable(onClick = open, onLongClick = open)) {
                        Box {
                            BoxArt(rom, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
                            if (romMeta?.favorite == true) FavBadge(Modifier.padding(4.dp))
                        }
                        Text(rom.title, style = Type.Caption.copy(color = Oe.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                ViewMode.List -> Row(
                    Modifier.fillMaxWidth().gamepadFocus(corner = 12.dp, inset = true).then(focus).clickable(onClick = open).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        BoxArt(rom, Modifier.clip(RoundedCornerShape(6.dp)), maxHeight = 64.dp)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(rom.title, style = Type.BodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(rom.console.displayName, style = Type.Small)
                    }
                    if (romMeta?.favorite == true) Icon(Icons.Star, Oe.Star, 16.dp, filled = true)
                }
            }
    }
    // A console without cartridge art shows its covers even in the cartridge view; the pick itself stays.
    val shown = if (mode == ViewMode.Carts && console != null && console.frame == null) ViewMode.Covers else mode
    // On a mixed page each console's group decides for itself, so each lays out its own columns.
    val perGroup = console == null && mode == ViewMode.Carts && groups.any { it.first?.frame == null }
    // As many columns as the width holds, never fewer than a phone shows.
    val spec = when (shown) { ViewMode.Carts -> GridSpec.Carts; ViewMode.Covers -> GridSpec.Covers; ViewMode.List -> GridSpec.List }
    // Staggered, so each box art keeps its own proportions in the covers mode.
    LazyVerticalStaggeredGrid(
        AdaptiveCells(spec),
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = TabBarHeight + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(spec.gap.dp),
        verticalItemSpacing = if (shown == ViewMode.List) 0.dp else 18.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { BackLink(stringResource(R.string.library_shelf_title)) { a.nav.popBackStack(Routes.SHELF, false) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RoundIconButton(if (searching) Icons.Close else Icons.Search, stringResource(if (searching) R.string.library_close_search else R.string.library_search), {
                            searching = !searching; if (!searching) query = ""
                        })
                        RoundIconButton(Icons.Sort, stringResource(R.string.library_sort_by, stringResource(sort.label)), {
                            sort = if (sort == SortMode.Az) SortMode.Recent else SortMode.Az
                        })
                    }
                }
                if (searching) SearchField(query, autoFocus = searchRequested) { query = it }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        val eyebrow = when (current) {
                            "FAV" -> stringResource(R.string.library_favorites_eyebrow)
                            "ALL" -> stringResource(R.string.library_all_consoles_eyebrow)
                            else -> "${console!!.short} · ${console.core(lib.consoleSettings(console).core).name}"
                        }
                        Text(eyebrow, style = Type.Eyebrow)
                        FitText(
                            when (current) { "FAV" -> stringResource(R.string.library_favorites); "ALL" -> stringResource(R.string.library_all_games); else -> console!!.displayName },
                            style = Type.Display, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(countString(R.plurals.library_games_count, list.size, list.size), style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(bottom = 4.dp))
                }
                ArtworkSyncPill(Modifier.padding(top = 16.dp))
                ConsoleChips(roms, current, Modifier.padding(top = 14.dp)) { current = it }
                Row(Modifier.padding(top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (sort == SortMode.Az) R.string.library_section_carts else R.string.library_section_recent), style = Type.Section.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
                    ViewToggle(mode) { mode = it }
                }
                if (list.isEmpty()) {
                    Text(
                        if (query.isNotBlank()) stringResource(R.string.library_no_results, query) else stringResource(R.string.library_empty_list),
                        style = Type.Small, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
        groups.forEach { (group, groupRoms) ->
            if (group != null) item(key = "group-${group.id}", span = StaggeredGridItemSpan.FullLine) { GroupHeader(group, groupRoms.size) }
            if (perGroup && group != null) {
                val groupMode = if (group.frame == null) ViewMode.Covers else ViewMode.Carts
                item(key = "grid-${group.id}", span = StaggeredGridItemSpan.FullLine) {
                    GroupGrid(groupRoms, if (groupMode == ViewMode.Covers) GridSpec.Covers else GridSpec.Carts) { tile(it, groupMode) }
                }
            } else {
                items(groupRoms, key = { it.key }) { rom -> tile(rom, shown) }
            }
        }
    }
}

/** A group's games in as many staggered columns as [spec] fits, filled in turn like the grid's lanes. */
@Composable
private fun GroupGrid(roms: List<Rom>, spec: GridSpec, tile: @Composable (Rom) -> Unit) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columns = gridColumns(maxWidth.value, spec.min, spec.gap, spec.floor)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spec.gap.dp)) {
        repeat(columns) { c ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                roms.filterIndexed { i, _ -> i % columns == c }.forEach { tile(it) }
            }
        }
    }
}

/** The grid's lanes: as many as [spec] fits in the width, sharing it evenly. */
private data class AdaptiveCells(private val spec: GridSpec) : StaggeredGridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): IntArray =
        cellSizes(availableSize, spacing, gridColumns(availableSize.toDp().value, spec.min, spacing.toDp().value, spec.floor))
}

/** A card no wider than [spec]'s maximum, centred in a wider cell; [content] gets the capped modifier. */
@Composable
private fun CappedCell(spec: GridSpec, content: @Composable (Modifier) -> Unit) =
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { content(Modifier.widthIn(max = spec.max.dp).fillMaxWidth()) }

/** A console's header over its games on a mixed page. */
@Composable
private fun GroupHeader(console: Console, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ConsoleChip(console)
        Text(console.displayName, style = Type.Section.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(countString(R.plurals.library_games_count, count, count), style = Type.Caption)
    }
}

@Composable
private fun BoxArt(rom: Rom, modifier: Modifier, maxHeight: Dp? = null) =
    CoverArt(LocalActions.current.app.covers.urlFor(rom), rom.title, modifier, maxHeight)

/** Landscape: a compact header and one row that scrolls sideways, three items at most in view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LandscapeLibrary(
    title: String,
    list: List<Rom>,
    groups: List<Pair<Console?, List<Rom>>>,
    roms: List<Rom>,
    current: String,
    onPick: (String) -> Unit,
    mode: ViewMode,
    onMode: (ViewMode) -> Unit,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    sort: SortMode,
    onSort: () -> Unit,
    focusedRom: MutableState<String?>,
) {
    val a = LocalActions.current
    val lib = a.app.library
    val meta by lib.meta.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding().padding(bottom = TabBarHeight)) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BackLink(stringResource(R.string.library_shelf_title)) { a.nav.popBackStack(Routes.SHELF, false) }
            FitText(title, style = Type.Title.copy(fontSize = 22.sp), modifier = Modifier.padding(start = 16.dp).weight(1f))
            Text(countString(R.plurals.library_games_count, list.size, list.size), style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(horizontal = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(if (searching) Icons.Close else Icons.Search, stringResource(if (searching) R.string.library_close_search else R.string.library_search), onSearch)
                RoundIconButton(Icons.Sort, stringResource(R.string.library_sort_by, stringResource(sort.label)), onSort)
                ViewToggle(mode, onMode)
            }
        }
        if (searching) Box(Modifier.padding(horizontal = 20.dp)) { SearchField(query, autoFocus = false, onChange = onQuery) }
        ConsoleChips(roms, current, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), onPick)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val margin = 32.dp
            val gap = 24.dp
            val third = (maxWidth - margin * 2 - gap * 2) / 3
            val caption = 28.dp
            // Room above and below, so nothing touches the edges.
            val itemH = maxHeight - caption - 56.dp
            if (list.isEmpty()) {
                Text(
                    if (query.isNotBlank()) stringResource(R.string.library_no_results, query) else stringResource(R.string.library_empty_list),
                    style = Type.Small, modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyRow(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = margin),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val firstKey = groups.firstOrNull()?.second?.firstOrNull()?.key
                groups.forEach { (group, groupRoms) ->
                    if (group != null) item(key = "group-${group.id}") { LandscapeGroupHeader(group, groupRoms.size) }
                    items(groupRoms, key = { it.key }) { rom ->
                        val open = { a.nav.navigate(Routes.rom(rom.key)) }
                        val favorite = meta.roms[rom.key]?.favorite == true
                        // At most three items a row: the cartridge fills the height it has, and a wide one
                        // widens its item past a third. A console without cartridge art shows its cover.
                        val itemMode = if (mode == ViewMode.Carts && rom.console.frame == null) ViewMode.Covers else mode
                        val w = if (itemMode == ViewMode.Carts) minOf((maxWidth - margin * 2) * 0.42f, itemH * rom.console.cartAspect) else null
                        Column(
                            Modifier.width(maxOf(third, w ?: third)).gamepadFocus(corner = 10.dp, scale = 1.06f)
                                .restorableFocus(rom.key, focusedRom, initial = rom.key == firstKey)
                                .combinedClickable(onClick = open, onLongClick = open),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box {
                                if (itemMode == ViewMode.Carts) {
                                    Cartridge(rom.console, labelFor(rom), rom.title, lib.effectiveColor(rom), Modifier.width(w!!), pending = labelPending(rom), cover = a.app.covers.urlFor(rom))
                                } else {
                                    BoxArt(rom, Modifier.clip(RoundedCornerShape(10.dp)), maxHeight = itemH)
                                }
                                if (favorite) FavBadge(Modifier.padding(4.dp))
                            }
                            Text(
                                rom.title, style = Type.Caption.copy(color = Oe.Text, fontSize = 13.sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.82f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** In the sideways row, a console's name stands before its games. */
@Composable
private fun LandscapeGroupHeader(console: Console, count: Int) {
    Column(Modifier.width(170.dp)) {
        ConsoleChip(console)
        Text(console.displayName, style = Type.Section.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(countString(R.plurals.library_games_count, count, count), style = Type.Caption, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ConsoleChips(roms: List<Rom>, current: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val library = LocalActions.current.app.library
    val meta by library.meta.collectAsState()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chips = listOf("ALL" to stringResource(R.string.library_chip_all), "FAV" to stringResource(R.string.library_favorites)) +
            library.orderedConsoles(meta.consoleOrder).filter { c -> roms.any { it.console == c } }.map { it.id to it.short }
        chips.forEach { (id, label) ->
            val active = id == current
            Box(
                Modifier.height(36.dp).gamepadFocus(corner = 18.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (active) Oe.Text else Oe.Surface)
                    .let { if (active) it else it.border(1.dp, Oe.Border, RoundedCornerShape(18.dp)) }
                    .clickable(role = Role.Tab) { onPick(id) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.Section.copy(fontSize = 14.sp, color = if (active) Oe.Bg else Oe.Text))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, autoFocus: Boolean, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Oe.Surface)
            .border(1.dp, Oe.Border, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, Oe.Muted, 18.dp)
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (value.isEmpty()) Text(stringResource(R.string.library_search_hint), style = Type.Body.copy(color = Oe.Faint))
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = Type.Body, cursorBrush = SolidColor(Oe.AccentText),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
}

@Composable
private fun FavBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape).background(Oe.Bg.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Star, Oe.Star, 13.dp, filled = true)
    }
}

@Composable
private fun ViewToggle(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Oe.Surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(ViewMode.Carts to Icons.Cart, ViewMode.Covers to Icons.Grid, ViewMode.List to Icons.List).forEach { (m, icon) ->
            val on = m == mode
            Box(
                Modifier.size(40.dp, 32.dp).gamepadFocus(corner = 9.dp).clip(RoundedCornerShape(9.dp)).background(if (on) Oe.Raised else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(m) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, if (on) Oe.Text else Oe.Muted, 18.dp) }
        }
    }
}

@Composable
fun RecentScreen() {
    val a = LocalActions.current
    val lib = a.app.library
    val roms by lib.roms.collectAsState()
    val meta by lib.meta.collectAsState()
    val recent = roms.filter { (meta.roms[it.key]?.lastPlayed ?: 0) > 0 }.sortedByDescending { meta.roms[it.key]!!.lastPlayed }
    GamepadButtons(hints = listOf("A" to stringResource(R.string.library_hint_detail), "L1 R1" to stringResource(R.string.library_hint_tabs)))

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Text(stringResource(R.string.library_recent_title), style = Type.Display, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp))
        }
        if (recent.isEmpty()) {
            item { EmptyState(stringResource(R.string.library_recent_empty_title), stringResource(R.string.library_recent_empty_body), null, {}) }
        }
        items(recent, key = { it.key }) { rom ->
            Row(
                Modifier.fillMaxWidth().gamepadFocus(corner = 0.dp, inset = true).clickable { a.nav.navigate(Routes.rom(rom.key)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cartridge(rom.console, labelFor(rom), rom.title, lib.effectiveColor(rom), Modifier.width(96.dp), cover = a.app.covers.urlFor(rom))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(rom.title, style = Type.BodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${rom.console.short} · ${formatAgo(meta.roms[rom.key]!!.lastPlayed)}", style = Type.Small, modifier = Modifier.padding(top = 2.dp))
                }
                Box(
                    Modifier.size(44.dp).gamepadFocus(corner = 22.dp).clip(CircleShape).background(Oe.Accent).clickable(onClickLabel = stringResource(R.string.library_continue_game, rom.title), role = Role.Button) { a.play(rom, true, null) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Play, Color.White, 18.dp, filled = true) }
            }
        }
        item { Spacer(BottomSpace) }
    }
}

/** The discreet engine credit next to the app's name. */
@Composable
private fun PoweredByRetroArch() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.library_powered_by) + " RetroArch", style = Type.Caption.copy(fontSize = 11.sp, color = Oe.Faint))
        androidx.compose.foundation.Image(
            painterResource(R.drawable.ic_retroarch), contentDescription = null, modifier = Modifier.size(14.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Oe.Faint),
        )
    }
}
