package com.openemux.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.Rom

@Composable
fun ShelfScreen() {
    val a = LocalActions.current
    val roms by a.app.library.roms.collectAsState()
    val meta by a.app.library.meta.collectAsState()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Oe.Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Cart, Color.White, 18.dp, stroke = 2.2f)
                }
                Text("OpenEmux", style = Type.Title.copy(fontSize = 18.sp), modifier = Modifier.padding(start = 10.dp).weight(1f))
                RoundIconButton(Icons.Search, "Buscar em todos os consoles", { a.nav.navigate(Routes.library("SEARCH")) })
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp)) {
                Text("Estante", style = Type.Display)
                val consoles = Console.entries.count { c -> roms.any { it.console == c } }
                Text("$consoles ${if (consoles == 1) "console" else "consoles"} · ${roms.size} jogos", style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp))
            }
            ArtworkSyncPill(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp))
        }
        if (roms.isEmpty()) {
            item {
                EmptyState(
                    "Sua estante está vazia",
                    "Importe as ROMs dos jogos que você tem. GBA (.gba) e Super Nintendo (.sfc, .smc), soltas ou em .zip.",
                    "Importar ROMs",
                    { a.nav.navigate(Routes.IMPORT) },
                )
            }
        } else {
            val favorites = roms.filter { meta.roms[it.key]?.favorite == true }
            val shelves = buildList {
                if (favorites.isNotEmpty()) add(Triple("FAVORITOS", "Favoritos", favorites) to "FAV")
                Console.entries.forEach { c ->
                    val list = roms.filter { it.console == c }
                    if (list.isNotEmpty()) add(Triple(c.short, c.displayName, list) to c.id)
                }
            }
            items(shelves) { (shelf, filter) ->
                val (short, name, list) = shelf
                val background = when (filter) {
                    "FAV" -> Brush.linearGradient(listOf(Color(0xFF5B3FA8), Color(0xFF3A2A6E)))
                    else -> SolidColor(Console.byId(filter)!!.shelfColor)
                }
                ShelfCard(short, name, "${list.size} ${if (list.size == 1) "jogo" else "jogos"}", list, background) {
                    a.nav.navigate(Routes.library(filter))
                }
            }
        }
        item { Spacer(BottomSpace) }
    }
}

@Composable
private fun ShelfCard(short: String, name: String, count: String, roms: List<Rom>, background: Brush, onClick: () -> Unit) {
    val a = LocalActions.current
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(108.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        val fan = roms.sortedByDescending { a.app.library.romMeta(it.key).lastPlayed }.take(3)
        val placements = listOf(Triple(92.dp, 26.dp, -9f), Triple(52.dp, 16.dp, -2f), Triple(8.dp, 30.dp, 7f))
        fan.forEachIndexed { i, rom ->
            val (right, top, angle) = placements[i + (3 - fan.size)]
            val w = if (rom.console.frameAspect < 1.65f) 100.dp else 118.dp
            Box(Modifier.align(Alignment.TopEnd).padding(end = right, top = top).width(w).rotate(angle)) {
                Cartridge(rom.console, labelFor(rom), rom.title, a.app.library.effectiveColor(rom), Modifier.fillMaxWidth(), pending = labelPending(rom))
            }
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 18.dp).width(150.dp)) {
            Text(short, style = Type.Tag.copy(color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, letterSpacing = 1.sp))
            Text(name, style = Type.Title.copy(fontSize = 19.sp, lineHeight = 22.sp, color = Color.White))
            Text(count, style = Type.Small.copy(color = Color.White.copy(alpha = 0.78f)), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private enum class ViewMode { Carts, Covers, List }
private enum class SortMode(val label: String) { Az("A–Z"), Recent("Recentes") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(filter: String) {
    val a = LocalActions.current
    val lib = a.app.library
    val roms by lib.roms.collectAsState()
    val meta by lib.meta.collectAsState()
    val revision by lib.revision.collectAsState()
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
    val continueRom = remember(list, meta, revision) {
        list.filter { (meta.roms[it.key]?.lastPlayed ?: 0) > 0 }.maxByOrNull { meta.roms[it.key]!!.lastPlayed }
    }

    val columns = when (mode) { ViewMode.Carts -> 2; ViewMode.Covers -> 3; ViewMode.List -> 1 }
    // Staggered, so each box art keeps its own proportions in the covers mode.
    LazyVerticalStaggeredGrid(
        StaggeredGridCells.Fixed(columns),
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = TabBarHeight + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(if (mode == ViewMode.Covers) 12.dp else 16.dp),
        verticalItemSpacing = if (mode == ViewMode.List) 0.dp else 18.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { BackLink("Estante") { a.nav.popBackStack(Routes.SHELF, false) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RoundIconButton(if (searching) Icons.Close else Icons.Search, if (searching) "Fechar busca" else "Buscar", {
                            searching = !searching; if (!searching) query = ""
                        })
                        RoundIconButton(Icons.Sort, "Ordenar: ${sort.label}", {
                            sort = if (sort == SortMode.Az) SortMode.Recent else SortMode.Az
                        })
                    }
                }
                if (searching) SearchField(query, autoFocus = searchRequested) { query = it }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        val eyebrow = when (current) {
                            "FAV" -> "FAVORITOS"
                            "ALL" -> "TODOS OS CONSOLES"
                            else -> "${console!!.short} · ${console.core(lib.consoleSettings(console).core).name}"
                        }
                        Text(eyebrow, style = Type.Eyebrow)
                        Text(
                            when (current) { "FAV" -> "Favoritos"; "ALL" -> "Todos os jogos"; else -> console!!.displayName },
                            style = Type.Display, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text("${list.size} ${if (list.size == 1) "jogo" else "jogos"}", style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(bottom = 4.dp))
                }
                ArtworkSyncPill(Modifier.padding(top = 16.dp))
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val chips = listOf("ALL" to "Todos", "FAV" to "Favoritos") + Console.entries.map { it.id to it.short }
                    chips.forEach { (id, label) ->
                        val active = id == current
                        Box(
                            Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
                                .background(if (active) Oe.Text else Oe.Surface)
                                .let { if (active) it else it.border(1.dp, Color(0xFF2E2E38), RoundedCornerShape(18.dp)) }
                                .clickable(role = Role.Tab) { current = id }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = Type.Section.copy(fontSize = 14.sp, color = if (active) Oe.Bg else Color(0xFFD2D2DC)))
                        }
                    }
                }
                if (continueRom != null && query.isBlank()) ContinueCard(continueRom)
                Row(Modifier.padding(top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sort == SortMode.Az) "Cartuchos" else "Jogados por último", style = Type.Section.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
                    ViewToggle(mode) { mode = it }
                }
                if (list.isEmpty()) {
                    Text(
                        if (query.isNotBlank()) "Nada encontrado para \"$query\"." else "Nenhum jogo aqui ainda.",
                        style = Type.Small, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
        items(list, key = { it.key }) { rom ->
            val romMeta = meta.roms[rom.key]
            val open = { a.nav.navigate(Routes.rom(rom.key)) }
            when (mode) {
                ViewMode.Carts -> Column(
                    Modifier.combinedClickable(onClick = open, onLongClick = open),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Cartridge(rom.console, labelFor(rom), rom.title, lib.effectiveColor(rom), Modifier.fillMaxWidth(), pending = labelPending(rom))
                        if (romMeta?.favorite == true) FavBadge(Modifier.padding(4.dp))
                    }
                    Text(
                        rom.title, style = Type.Caption.copy(color = Color(0xFFE6E6EC), fontSize = 13.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    )
                }
                ViewMode.Covers -> Column(Modifier.combinedClickable(onClick = open, onLongClick = open)) {
                    Box {
                        BoxArt(rom, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
                        if (romMeta?.favorite == true) FavBadge(Modifier.padding(4.dp))
                    }
                    Text(rom.title, style = Type.Caption.copy(color = Color(0xFFE6E6EC)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                ViewMode.List -> Row(
                    Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 8.dp),
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
    }
}

/** Box art at its own aspect ratio; a placeholder of the usual box shape until it loads. */
@Composable
private fun BoxArt(rom: Rom, modifier: Modifier, maxHeight: Dp? = null) {
    val url = LocalActions.current.app.covers.urlFor(rom)
    var aspect by remember(url) { mutableStateOf(boxAspects[url]) }
    val shape = Modifier.let { if (maxHeight != null) it.height(maxHeight) else it.fillMaxWidth() }
        .aspectRatio(aspect ?: 0.72f, matchHeightConstraintsFirst = maxHeight != null)
    Box(modifier.then(shape).background(Oe.Surface), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(
                url, rom.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f) {
                        aspect = size.width / size.height
                        boxAspects[url] = aspect!!
                    }
                },
            )
        }
        if (url == null || aspect == null) {
            Text(
                rom.title, style = Type.Caption.copy(color = Oe.Muted, fontSize = if (maxHeight != null) 9.sp else 12.sp),
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp),
            )
        }
    }
}

/** Loaded box art proportions, so scrolling back does not reflow the grid. */
private val boxAspects = HashMap<String, Float>()

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
            if (value.isEmpty()) Text("Buscar jogo", style = Type.Body.copy(color = Oe.Faint))
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
    Box(modifier.size(24.dp).clip(CircleShape).background(Color(0xD9121216)), contentAlignment = Alignment.Center) {
        Icon(Icons.Star, Oe.Star, 13.dp, filled = true)
    }
}

@Composable
private fun ViewToggle(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Oe.Surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(ViewMode.Carts to Icons.Cart, ViewMode.Covers to Icons.Grid, ViewMode.List to Icons.List).forEach { (m, icon) ->
            val on = m == mode
            Box(
                Modifier.size(40.dp, 32.dp).clip(RoundedCornerShape(9.dp)).background(if (on) Color(0xFF2E2E38) else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(m) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, if (on) Oe.Text else Oe.Muted, 18.dp) }
        }
    }
}

@Composable
private fun ContinueCard(rom: Rom) {
    val a = LocalActions.current
    val lib = a.app.library
    val revision by lib.revision.collectAsState()
    val auto = remember(rom, revision) { lib.autoState(rom) ?: lib.states(rom).firstOrNull() }
    Row(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Oe.Surface)
            .border(1.dp, Oe.Border, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button) { a.play(rom, true, null) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(108.dp, 72.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            val thumb = remember(auto) { auto?.thumbnail?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Cartridge(rom.console, labelFor(rom), rom.title, lib.effectiveColor(rom), Modifier.align(Alignment.Center).width(96.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("CONTINUAR", style = Type.Tag.copy(color = Oe.Muted, fontSize = 12.sp, letterSpacing = 0.8.sp))
            Text(rom.title, style = Type.Section.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(
                if (auto != null) "Estado automático · ${formatAgo(auto.modified)}" else "Jogado ${formatAgo(lib.romMeta(rom.key).lastPlayed)}",
                style = Type.Small, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(Oe.Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Play, Color.White, 18.dp, filled = true)
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

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Text("Recentes", style = Type.Display, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp))
        }
        if (recent.isEmpty()) {
            item { EmptyState("Nada jogado ainda", "Os jogos que você abrir aparecem aqui, com o último estado salvo.", null, {}) }
        }
        items(recent, key = { it.key }) { rom ->
            Row(
                Modifier.fillMaxWidth().clickable { a.nav.navigate(Routes.rom(rom.key)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cartridge(rom.console, labelFor(rom), rom.title, lib.effectiveColor(rom), Modifier.width(96.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(rom.title, style = Type.BodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${rom.console.short} · ${formatAgo(meta.roms[rom.key]!!.lastPlayed)}", style = Type.Small, modifier = Modifier.padding(top = 2.dp))
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Oe.Accent).clickable(onClickLabel = "Continuar ${rom.title}", role = Role.Button) { a.play(rom, true, null) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Play, Color.White, 18.dp, filled = true) }
            }
        }
        item { Spacer(BottomSpace) }
    }
}
