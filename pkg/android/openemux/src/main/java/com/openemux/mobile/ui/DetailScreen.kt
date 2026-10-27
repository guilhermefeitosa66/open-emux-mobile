package com.openemux.mobile.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import android.text.format.Formatter
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.CART_COLORS
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.SHADERS
import com.openemux.mobile.data.SaveState
import com.openemux.mobile.data.shaderById
import com.retroarch.R

private enum class Sheet { None, Core, Shader, States }

/** One of the game's settings: what the list row or the tabletop tile shows, and what a tap opens. */
private class Setting(val label: String, val value: String, val isDefault: Boolean, val onClick: (() -> Unit)?)

/** How a game's detail is laid out: a phone's column, the library's side pane, or two columns on a tablet. */
enum class DetailStyle { Phone, Pane, TwoColumns }

@Composable
fun DetailScreen(key: String) {
    val layout = LocalWindowLayout.current
    // Opened on its own beside a library that a resize gives its pane back: the game goes back there.
    ReturnToSplit(layout.libraryPane && !LocalTabletop.current, Routes.LIBRARY, key)
    RomDetail(key, if (layout.twoColumnDetail) DetailStyle.TwoColumns else DetailStyle.Phone)
}

/**
 * A game's detail in [style]. [active] tells whether its buttons answer the
 * gamepad (the pane only while it holds focus); [onClose] runs after the game
 * is removed, and [onOpenFull] opens the pane's game on its own screen.
 */
@Composable
fun RomDetail(key: String, style: DetailStyle, active: Boolean = true, onClose: (() -> Unit)? = null, onOpenFull: (() -> Unit)? = null) {
    val a = LocalActions.current
    val lib = a.app.library
    val roms by lib.roms.collectAsState()
    val meta by lib.meta.collectAsState()
    val revision by lib.revision.collectAsState()
    val rom = roms.firstOrNull { it.key == key } ?: return
    val romMeta = meta.roms[key]
    val states = remember(rom, revision) { lib.states(rom) }
    val cheevos by a.app.achievements.state.collectAsState()
    // Hardcore refuses loading a state: no "Continue", and the states stay for when it is off.
    val hardcore = cheevos.hardcoreActive
    val auto = if (hardcore) null else states.firstOrNull { it.isAuto }
    val context = LocalContext.current
    fun playState(st: SaveState) {
        if (hardcore) Toast.makeText(context, R.string.detail_hardcore_no_load, Toast.LENGTH_SHORT).show()
        else a.play(rom, st.isAuto, st.slot.takeIf { !st.isAuto })
    }
    val playtime = remember(rom, revision) { lib.playtimeSeconds(rom) }
    val core = lib.effectiveCore(rom)
    val shader = lib.effectiveShader(rom)
    val color = lib.effectiveColor(rom)
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val close = onClose ?: { a.nav.popBackStack(); Unit }
    // L1/R1 walk this console's games in the library's order; in the pane they stay the library's.
    val siblings = roms.filter { it.console == rom.console }
    fun sibling(step: Int) {
        val next = siblings.getOrNull(siblings.indexOfFirst { it.key == key } + step) ?: return
        a.nav.navigate(Routes.rom(next.key)) { popUpTo(Routes.ROM) { inclusive = true } }
    }
    val walk = style != DetailStyle.Pane
    GamepadButtons(
        hints = listOf(
            "A" to stringResource(if (auto != null) R.string.detail_continue else R.string.detail_play),
            "X" to stringResource(R.string.detail_favorite),
            "Y" to stringResource(R.string.detail_menu),
            "B" to stringResource(if (walk) R.string.detail_back else R.string.library_hint_list),
        ),
        l1 = if (walk) ({ sibling(-1) }) else null,
        r1 = if (walk) ({ sibling(1) }) else null,
        x = { lib.updateRom(key) { it.copy(favorite = !it.favorite) } },
        y = { menu = true },
        enabled = active,
    )
    val playFocus = rememberSaveable { mutableStateOf<String?>(null) }
    // In the pane the split brings focus back to its side; restoring "play" when the gamepad wakes would pull it off the list.
    val playFocusModifier = if (style == DetailStyle.Pane) Modifier else Modifier.restorableFocus("play", playFocus, initial = true)
    val tabletop = LocalTabletop.current
    val compact = LocalWindowLayout.current.width == SizeClass.Compact

    // The screen's blocks, laid out per style on a flat screen and split across the fold in tabletop.
    val favoriteButton: @Composable () -> Unit = {
        RoundIconButton(
            Icons.Star, stringResource(if (romMeta?.favorite == true) R.string.detail_favorite_remove else R.string.detail_favorite_add),
            { lib.updateRom(key) { it.copy(favorite = !it.favorite) } },
            tint = if (romMeta?.favorite == true) Oe.Star else Oe.Muted, background = Oe.Control.copy(alpha = 0.8f),
            filled = romMeta?.favorite == true,
        )
    }
    val menuButton: @Composable () -> Unit = {
        Box {
            RoundIconButton(Icons.Dots, stringResource(R.string.detail_more_options), { menu = true }, background = Oe.Control.copy(alpha = 0.8f), filled = true)
            androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Oe.Surface2) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.detail_share), style = Type.Body) },
                    leadingIcon = { Icon(Icons.Share, Oe.Text, 20.dp) },
                    onClick = {
                        menu = false
                        a.app.shareCoordinator.startSend(listOf(rom))
                        a.nav.navigate(Routes.SHARE)
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.detail_remove_from_library), style = Type.Body.copy(color = Oe.Danger)) },
                    onClick = { menu = false; confirmDelete = true },
                )
            }
        }
    }
    val header: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Back, stringResource(R.string.detail_back), { a.nav.popBackStack() }, background = Oe.Control.copy(alpha = 0.8f))
            Spacer(Modifier.weight(1f))
            favoriteButton()
            Spacer(Modifier.width(8.dp))
            menuButton()
        }
    }
    val cartridge: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Cartridge(rom.console, labelFor(rom), rom.title, color, Modifier.fillMaxWidth(), pending = labelPending(rom), cover = a.app.covers.urlFor(rom))
            Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.83f).height(10.dp).blur(6.dp).clip(CircleShape).background(Color(0x8C000000)))
        }
    }
    val playButtons: @Composable (Dp, Dp) -> Unit = { height, restart ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (auto != null) {
                PrimaryButton(stringResource(R.string.detail_continue), { a.play(rom, true, null) }, Modifier.weight(1f).then(playFocusModifier), icon = Icons.Play, height = height)
                SecondaryButton(stringResource(R.string.detail_restart), { a.play(rom, false, null) }, Modifier.width(restart), height = height)
            } else {
                PrimaryButton(stringResource(R.string.detail_play), { a.play(rom, false, null) }, Modifier.weight(1f).then(playFocusModifier), icon = Icons.Play, height = height)
            }
        }
    }
    val statesSection: @Composable (Int, Dp) -> Unit = { count, thumb ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.detail_save_states), style = Type.Section, modifier = Modifier.weight(1f))
                if (states.isNotEmpty()) {
                    Text(
                        stringResource(R.string.detail_see_all, states.size), style = Type.Small.copy(color = Oe.AccentText),
                        modifier = Modifier.gamepadFocus(corner = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { sheet = Sheet.States }.padding(4.dp),
                    )
                }
            }
            if (states.isEmpty()) {
                Text(stringResource(R.string.detail_no_states), style = Type.Small, modifier = Modifier.padding(top = 8.dp))
            } else {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    states.take(count).forEach { st ->
                        StateThumb(st, Modifier.weight(1f), thumb) { playState(st) }
                    }
                    repeat(count - states.size.coerceAtMost(count)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    val colorHeader: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.detail_cart_color), style = Type.Section, modifier = Modifier.weight(1f))
            Text(
                stringResource(CART_COLORS.first { it.id == color }.label) + if (romMeta?.color == null) stringResource(R.string.detail_default_suffix) else "",
                style = Type.Small,
            )
        }
    }
    val colorBlock: @Composable () -> Unit = {
        Column {
            colorHeader()
            ColorSwatches(color, Modifier.padding(top = 10.dp)) { id -> lib.updateRom(key) { it.copy(color = id) } }
        }
    }
    // The game's settings: label, value, whether the console's default applies, and what a tap opens.
    val settings = listOf(
        Setting(stringResource(R.string.detail_core), core.name, romMeta?.core == null) { sheet = Sheet.Core },
        Setting(stringResource(R.string.detail_shader), shader.name(), romMeta?.shader == null) { sheet = Sheet.Shader },
        Setting(stringResource(R.string.detail_controller_skin), "OpenEmux ${rom.console.short}", false, null),
        Setting(stringResource(R.string.detail_cheats), stringResource(R.string.detail_coming_soon), false, null),
    )
    val settingsCard: @Composable (Modifier) -> Unit = { modifier ->
        Card(modifier) {
            settings.forEachIndexed { i, it ->
                ListRow(it.label, value = it.value, divider = i > 0, onClick = it.onClick,
                    trailing = if (it.isDefault) ({ Tag(stringResource(R.string.detail_default_tag), Oe.Surface2, Oe.Muted) }) else null)
            }
        }
    }

    if (tabletop && style != DetailStyle.Pane) {
        TabletopDeck {
            MarginFocusScroll { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 20.dp)) {
                playButtons(54.dp, if (compact) 120.dp else 160.dp)
                Spacer(Modifier.height(18.dp))
                statesSection(if (compact) 3 else 4, 76.dp)
                Spacer(Modifier.height(18.dp))
                SettingTiles(settings, columns = if (compact) 2 else 4)
                if (rom.console.frame != null) {
                    Spacer(Modifier.height(18.dp))
                    colorBlock()
                }
            } }
        }
        // The top half: the game itself, art beside the title when the half is wide enough.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = maxHeight
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(top = 14.dp)) {
                header()
                val overline = "${rom.console.short} · ${core.name}"
                val meta = formatPlaytime(playtime)
                if (compact) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        cartridge(Modifier.width(minOf(264.dp, (room - 160.dp) * 1.6f).coerceAtLeast(120.dp)))
                        DetailTitle(overline, rom.title, meta, TextAlign.Center)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.matchParentSize().drawBehind { drawCircle(Brush.radialGradient(listOf(Color(0x617C3AED), Color(0x007C3AED)), radius = size.maxDimension * 0.6f), radius = size.maxDimension * 0.6f) })
                            cartridge(Modifier.width(minOf(320.dp, (room - 90.dp) * 1.6f).coerceAtLeast(140.dp)))
                        }
                        Box(Modifier.weight(1f)) { DetailTitle(overline, rom.title, meta, TextAlign.Start) }
                    }
                }
            }
        }
    } else when (style) {
        DetailStyle.Phone -> Box(Modifier.fillMaxSize()) {
            // The violet glow behind the cartridge.
            Glow(Modifier.fillMaxWidth().height(340.dp).offset(y = 40.dp))
            MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                header()

                cartridge(Modifier.fillMaxWidth().padding(top = 6.dp).wrapContentWidth().width(264.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rom.title, style = Type.Title, textAlign = TextAlign.Center)
                    Text(
                        "${rom.console.displayName} · ${core.name} · ${formatPlaytime(playtime)}",
                        style = Type.Small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Box(Modifier.padding(horizontal = 20.dp)) { playButtons(52.dp, 120.dp) }
                Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) { statesSection(3, 58.dp) }

                if (rom.console.frame != null) Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) { colorBlock() }

                settingsCard(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
                Spacer(Modifier.height(28.dp))
                Spacer(Modifier.height(hintBarInset))
            } }
        }

        DetailStyle.Pane -> Box(Modifier.fillMaxSize()) {
            Glow(Modifier.fillMaxWidth().height(320.dp).offset(y = 20.dp))
            // Room for the tab bar if one ever sits under the pane (beside the rail it is 0dp), and Down stops at the last item.
            MarginFocusScroll(trailing = FocusMargin + TabBarHeight + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().holdFocusAtEnd(FocusDirection.Down).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (onOpenFull != null) RoundIconButton(Icons.Expand, stringResource(R.string.library_open_full), onOpenFull, background = Oe.Control.copy(alpha = 0.8f))
                    favoriteButton()
                    menuButton()
                }
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cartridge(rom.console, labelFor(rom), rom.title, color, Modifier.width(236.dp), pending = labelPending(rom), cover = a.app.covers.urlFor(rom))
                    Text(rom.title, style = Type.Title.copy(fontSize = 23.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                    Text(
                        "${rom.console.displayName} · ${core.name} · ${formatPlaytime(playtime)}",
                        style = Type.Small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Box(Modifier.padding(top = 16.dp)) { playButtons(52.dp, 120.dp) }
                Box(Modifier.padding(top = 18.dp)) { statesSection(3, 62.dp) }
                settingsCard(Modifier.padding(top = 16.dp))
                Spacer(Modifier.height(TabBarHeight + 24.dp))
                Spacer(Modifier.height(hintBarInset))
            } }
        }

        DetailStyle.TwoColumns -> BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
            // The cartridge's column gives way first, down to 240dp, so the right one keeps up to 320dp;
            // below 840dp the gutters narrow too, or a 600dp window leaves the right column 216dp.
            val gap = if (maxWidth < 840.dp) 24.dp else 48.dp
            val room = maxWidth - gap * 3
            val left = minOf(460.dp, room - 320.dp).coerceAtLeast(240.dp)
            val right = minOf(560.dp, room - left)
            Row(Modifier.fillMaxSize().padding(horizontal = gap), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.width(left).fillMaxHeight().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                    Box(Modifier.padding(top = 22.dp)) { BackLink(rom.console.displayName) { a.nav.popBackStack() } }
                    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
                        Glow(Modifier.fillMaxWidth().height(360.dp))
                        Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cartridge(rom.console, labelFor(rom), rom.title, color, Modifier.widthIn(max = 400.dp).fillMaxWidth(), pending = labelPending(rom), cover = a.app.covers.urlFor(rom))
                            Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.8f).height(12.dp).blur(8.dp).clip(CircleShape).background(Color(0x8C000000)))
                        }
                    }
                    if (rom.console.frame != null) {
                        Column(Modifier.padding(top = 28.dp, bottom = 24.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Oe.Surface).padding(18.dp)) {
                            colorHeader()
                            ColorSwatches(color, Modifier.padding(top = 12.dp)) { id -> lib.updateRom(key) { it.copy(color = id) } }
                        }
                    }
                    Spacer(Modifier.height(hintBarInset))
                } }
                MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.width(right).fillMaxHeight().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 44.dp, bottom = 28.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("${rom.console.short} · ${core.name}".uppercase(), style = Type.Eyebrow)
                            Text(rom.title, style = Type.Display.copy(fontSize = 34.sp, lineHeight = 38.sp), modifier = Modifier.padding(top = 4.dp))
                            val last = romMeta?.lastPlayed ?: 0L
                            Text(
                                if (last > 0) stringResource(R.string.detail_played_last, formatPlaytime(playtime), formatAgo(last)) else formatPlaytime(playtime),
                                style = Type.Small, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            favoriteButton()
                            menuButton()
                        }
                    }
                    // Restart never takes more than 40% of a narrow column from Continue.
                    Box(Modifier.padding(top = 22.dp)) { playButtons(56.dp, minOf(160.dp, right * 0.4f)) }
                    Box(Modifier.padding(top = 26.dp)) { statesSection(detailStatesCount(right.value), 78.dp) }
                    Text(stringResource(R.string.detail_settings_for_game), style = Type.Section, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
                    settingsCard(Modifier)
                    // A stat of the file: off the main thread, like the other reads of this screen.
                    val size by produceState<Long?>(null, rom) { value = withContext(Dispatchers.IO) { rom.file.length() } }
                    Text(
                        listOfNotNull(rom.file.name, size?.let { Formatter.formatShortFileSize(context, it) }, rom.file.parentFile?.name).joinToString(" · "),
                        style = Type.Small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp),
                    )
                    Spacer(Modifier.height(hintBarInset))
                } }
            }
        }
    }

    when (sheet) {
        Sheet.Core -> CoreSheet(rom) { sheet = Sheet.None }
        Sheet.Shader -> ShaderSheet(rom) { sheet = Sheet.None }
        Sheet.States -> StatesSheet(states, ::playState) { sheet = Sheet.None }
        Sheet.None -> {}
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Oe.Sheet,
            title = { ImmersiveWindow(); Text(stringResource(R.string.detail_remove_title, rom.title), style = Type.Title.copy(fontSize = 20.sp)) },
            text = { Text(stringResource(R.string.detail_remove_message), style = Type.Small) },
            confirmButton = {
                TextButton({ confirmDelete = false; lib.delete(rom); close() }) { Text(stringResource(R.string.detail_remove), style = Type.Section.copy(color = Oe.Danger)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.detail_cancel), style = Type.Section) } },
        )
    }
}

/** The violet glow behind the cartridge. */
@Composable
private fun Glow(modifier: Modifier) {
    Box(
        modifier.drawBehind {
            drawCircle(
                Brush.radialGradient(listOf(Color(0x617C3AED), Color(0x007C3AED)), center = Offset(size.width / 2, size.height / 2), radius = size.width * 0.6f),
                radius = size.width * 0.6f,
            )
        }
    )
}

@Composable
private fun StateThumb(st: SaveState, modifier: Modifier, thumb: Dp = 58.dp, onClick: () -> Unit) {
    val corner = if (thumb > 58.dp) 12.dp else 10.dp
    Column(modifier.gamepadFocus(corner = corner).clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(thumb).clip(RoundedCornerShape(corner)).background(Color.Black)) {
            val bmp = remember(st.thumbnail) { st.thumbnail?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(if (st.isAuto) stringResource(R.string.game_state_auto) else stringResource(R.string.game_state_slot, st.slot), style = Type.Tag.copy(color = Color.White, fontSize = 10.sp))
            }
        }
        Text(formatAgo(st.modified), style = Type.Caption, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
    }
}

/** The top half's words in tabletop: console and core, the title, then the time played. */
@Composable
private fun DetailTitle(overline: String, title: String, meta: String, align: TextAlign) {
    val horizontal = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = horizontal) {
        Text(overline, style = Type.Eyebrow.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), textAlign = align)
        Text(title, style = Type.Display, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.padding(top = 6.dp))
        Text(meta, style = Type.Small.copy(fontSize = 14.sp), textAlign = align, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The game's settings as tiles, [columns] to a row, for the tabletop's bottom half. */
@Composable
private fun SettingTiles(settings: List<Setting>, columns: Int) {
    val defaultSuffix = stringResource(R.string.detail_default_suffix)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        settings.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { SettingTile(it.label, if (it.isDefault) it.value + defaultSuffix else it.value, it.onClick, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ColorSwatches(selected: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    FocusRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CART_COLORS.forEach { c ->
            Box(
                Modifier.size(38.dp).gamepadFocus(corner = 19.dp).clip(CircleShape)
                    .border(2.dp, if (c.id == selected) Oe.AccentText else Color.Transparent, CircleShape)
                    .clickable(onClickLabel = stringResource(c.label), role = Role.RadioButton) { onPick(c.id) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(c.swatch).border(1.dp, Oe.Text.copy(alpha = 0.18f), CircleShape))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoreSheet(rom: Rom, onClose: () -> Unit) {
    val a = LocalActions.current
    val lib = a.app.library
    val meta by lib.meta.collectAsState()
    val choice = meta.roms[rom.key]?.core
    val defaultCore = rom.console.core(lib.consoleSettings(rom.console).core)
    val states = remember(rom) { lib.states(rom) }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Oe.Sheet) {
        ImmersiveWindow()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.detail_core_sheet_title), style = Type.Title.copy(fontSize = 22.sp))
            Text(rom.title, style = Type.Small, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            RadioCard(
                choice == null, stringResource(R.string.detail_console_default),
                stringResource(R.string.detail_core_default_desc, defaultCore.name, rom.console.short),
                onClick = { lib.updateRom(rom.key) { it.copy(core = null) } },
                background = Oe.Raised,
            )
            Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.detail_pin_for_rom), style = Type.Tag.copy(color = Oe.Faint, fontSize = 12.sp, letterSpacing = 1.sp))
                HorizontalDivider(Modifier.padding(start = 10.dp).weight(1f), color = Oe.Divider)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rom.console.cores.forEach { c ->
                    RadioCard(
                        choice == c.id, c.name,
                        if (c.id == defaultCore.id) stringResource(R.string.detail_core_pinned_desc, c.name) else stringResource(c.description),
                        onClick = { lib.updateRom(rom.key) { it.copy(core = c.id) } },
                        background = Oe.Raised,
                    )
                }
            }
            if (states.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Oe.WarnBg).padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Warn, Oe.WarnText, 18.dp)
                    Text(
                        countString(R.plurals.detail_core_states_warning, states.size, states.size, lib.effectiveCore(rom).name),
                        style = Type.Small.copy(color = Oe.WarnText),
                    )
                }
            }
            PrimaryButton(stringResource(R.string.detail_done), onClose, Modifier.padding(top = 18.dp).fillMaxWidth(), height = 54.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShaderSheet(rom: Rom, onClose: () -> Unit) {
    val a = LocalActions.current
    val lib = a.app.library
    val meta by lib.meta.collectAsState()
    val choice = meta.roms[rom.key]?.shader
    val default = shaderById(lib.consoleSettings(rom.console).shader)
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Oe.Sheet) {
        ImmersiveWindow()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.detail_shader_sheet_title), style = Type.Title.copy(fontSize = 22.sp))
            Text(rom.title, style = Type.Small, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            RadioCard(
                choice == null, stringResource(R.string.detail_console_default), stringResource(R.string.detail_shader_default_desc, default.name(), rom.console.short),
                onClick = { lib.updateRom(rom.key) { it.copy(shader = null) } }, background = Oe.Raised,
            )
            Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.detail_pin_for_rom), style = Type.Tag.copy(color = Oe.Faint, fontSize = 12.sp, letterSpacing = 1.sp))
                HorizontalDivider(Modifier.padding(start = 10.dp).weight(1f), color = Oe.Divider)
            }
            FocusRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SHADERS.forEach { s ->
                    ShaderTile(s.id, s.name(), choice == s.id) { lib.updateRom(rom.key) { it.copy(shader = s.id) } }
                }
            }
            PrimaryButton(stringResource(R.string.detail_done), onClose, Modifier.padding(top = 18.dp).fillMaxWidth(), height = 54.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatesSheet(states: List<SaveState>, onPick: (SaveState) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onClose, containerColor = Oe.Sheet) {
        ImmersiveWindow()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.detail_save_states), style = Type.Title.copy(fontSize = 22.sp))
            Text(stringResource(R.string.detail_states_sheet_hint), style = Type.Small, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp - FocusOutset))
            // As many cells as the sheet's width holds, as tall as the states need up to most of the window.
            MarginFocusScroll { LazyVerticalGrid(
                GridCells.Adaptive(160.dp), Modifier.heightIn(max = (LocalWindowLayout.current.heightDp * 0.7f).dp),
                contentPadding = PaddingValues(vertical = FocusOutset),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(states) { st ->
                    Column(Modifier.gamepadFocus(corner = 10.dp).clickable(role = Role.Button) { onClose(); onPick(st) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                            val bmp = remember(st.thumbnail) { st.thumbnail?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
                            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(if (st.isAuto) stringResource(R.string.game_state_auto) else stringResource(R.string.game_state_slot, st.slot), style = Type.Tag.copy(color = Color.White, fontSize = 10.sp))
                            }
                        }
                        Text(formatAgo(st.modified), style = Type.Caption, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
                    }
                }
            } }
        }
    }
}

/** A frame of a game with the shader's look painted over it. */
@Composable
fun ShaderTile(id: String, name: String, selected: Boolean, onClick: () -> Unit) {
    val crt = id == "zfast" || id == "geom"
    Column(Modifier.width(108.dp).gamepadFocus(corner = 12.dp).clickable(onClickLabel = name, role = Role.RadioButton, onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(108.dp, 76.dp).clip(RoundedCornerShape(if (crt) 16.dp else 10.dp))
                .border(if (selected) 2.5.dp else 1.dp, if (selected) Oe.AccentBright else Oe.Border, RoundedCornerShape(if (crt) 16.dp else 10.dp))
                .background(Color.Black),
        ) {
            Image(
                androidx.compose.ui.res.painterResource(com.retroarch.R.drawable.shader_sample), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alpha = if (id == "sharp") 1f else 0.95f,
            )
            Box(Modifier.fillMaxSize().drawBehind { drawShaderLook(id) })
        }
        Text(name, style = Type.Caption.copy(color = Oe.Text), modifier = Modifier.alpha(if (selected) 1f else 0.9f))
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShaderLook(id: String) {
    val step = 3.dp.toPx()
    val line = Color(0x73000000)
    if (id == "dot") {
        var x = 0f
        while (x < size.width) { drawRect(line, Offset(x, 0f), androidx.compose.ui.geometry.Size(1f, size.height)); x += step }
    }
    if (id == "dot" || id == "zfast" || id == "geom") {
        var y = 0f
        while (y < size.height) { drawRect(line, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 1f)); y += step }
    }
    if (id == "zfast" || id == "geom") {
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99000000)), center = center, radius = size.maxDimension * 0.75f))
    }
}
