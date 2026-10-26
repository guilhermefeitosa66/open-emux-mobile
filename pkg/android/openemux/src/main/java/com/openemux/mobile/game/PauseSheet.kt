package com.openemux.mobile.game

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.SHADERS
import com.openemux.mobile.ui.DISABLED_ALPHA
import com.openemux.mobile.ui.FocusOutset
import com.openemux.mobile.ui.HintBar
import com.openemux.mobile.ui.MarginFocusScroll
import com.openemux.mobile.ui.Icon
import com.openemux.mobile.ui.Icons
import com.openemux.mobile.ui.ListRow
import com.openemux.mobile.ui.LocalWindowLayout
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.OpenEmuxTheme
import com.openemux.mobile.ui.RadioCard
import com.openemux.mobile.ui.Tag
import com.openemux.mobile.ui.Type
import com.openemux.mobile.ui.formatAgo
import com.openemux.mobile.ui.gamepadFocus
import com.openemux.mobile.ui.restorableFocus
import com.retroarch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PauseSession(
    val title: String,
    val cartPath: String?,
    val statesDir: File,
    val romBase: String,
    val shadersDir: File,
    val shader: String,
    val coreName: String,
    val startedAt: Long,
    /** RetroAchievements hardcore: the engine refuses loading states; the app leaves fast forward out too. */
    val hardcore: Boolean = false,
    /** Whether the engine has fast forward on as the sheet opens (GameActivity.onToggleStates). */
    val fastForward: Boolean = false,
) {
    data class State(val slot: Int, val file: File, val thumb: File?, val modified: Long)

    fun states(): List<State> {
        val prefix = "$romBase.state"
        return statesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(".png") }
            .mapNotNull { f ->
                val suffix = f.name.removePrefix(prefix)
                val slot = when {
                    suffix.isEmpty() -> 0
                    suffix == ".auto" -> -1
                    suffix.all { it.isDigit() } && suffix.toInt() != com.openemux.mobile.data.SCRATCH_SLOT -> suffix.toInt()
                    else -> return@mapNotNull null
                }
                State(slot, f, File(f.path + ".png").takeIf { it.exists() }, f.lastModified())
            }
            .sortedByDescending { it.modified }
    }

    /** Copies the auto state where a slot command can reach it. */
    fun stageAuto(st: State): Int {
        val slot = com.openemux.mobile.data.SCRATCH_SLOT
        st.file.copyTo(File(statesDir, "$romBase.state$slot"), overwrite = true)
        return slot
    }

    companion object {
        fun fromIntent(intent: Intent, startedAt: Long, fastForward: Boolean = false) = PauseSession(
            title = intent.getStringExtra(GameExtras.TITLE) ?: "",
            cartPath = intent.getStringExtra(GameExtras.CART),
            statesDir = File(intent.getStringExtra(GameExtras.STATES_DIR) ?: "/"),
            romBase = intent.getStringExtra(GameExtras.ROM_BASE) ?: "",
            shadersDir = File(intent.getStringExtra(GameExtras.SHADERS_DIR) ?: "/"),
            shader = intent.getStringExtra(GameExtras.SHADER) ?: "none",
            coreName = intent.getStringExtra(GameExtras.CORE_NAME) ?: "",
            startedAt = startedAt,
            hardcore = intent.getBooleanExtra(GameExtras.HARDCORE, false),
            fastForward = fastForward,
        )
    }
}

/**
 * Survives the dialog: toggles the engine keeps for the whole session. Fast forward is not here: the engine reports
 * it (PauseSession.fastForward), so the sheet agrees with the skin's strip and the gamepad hotkey.
 */
private object SessionToggles {
    var muted = false
    var shader: String? = null
}

private enum class Page { Main, States, Shader }

@Composable
fun PauseSheet(
    session: PauseSession,
    onResume: () -> Unit,
    onQuit: () -> Unit,
    /** NDS: switches to the next screen layout and names it; null for other consoles. */
    onScreens: (() -> String)? = null,
    /**
     * Fast forward on (true) or off, as the tile now shows. Sent by the activity after the sheet closes: the engine
     * refuses to turn it on while paused.
     */
    onFastForward: (Boolean) -> Unit = {},
) {
    OpenEmuxTheme {
        var page by remember { mutableStateOf(Page.Main) }
        var toast by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        // Flipped at once on a tap; the engine's own value comes in again the next time the sheet opens.
        var fastForward by remember { mutableStateOf(session.fastForward && !session.hardcore) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val states = remember(refresh) { session.states() }
        val cart = remember { session.cartPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
        val window = LocalWindowLayout.current
        // A tablet gets a side panel on the right, the game still showing on the left (TabPausa).
        val panel = window.tablet
        val panelWidth = min(440.dp, (window.widthDp * 0.55f).dp)
        // A wide window (a phone on its side) is short: the main page goes in two columns when they fit, and whatever still overflows scrolls.
        val landscape = window.wide && !panel

        fun command(cmd: String, message: String? = null, after: () -> Unit = {}) {
            scope.launch {
                withContext(Dispatchers.IO) { RaCommand.send(cmd) }
                if (message != null) toast = message
                after()
            }
        }

        fun load(st: PauseSession.State) {
            scope.launch {
                val reply = withContext(Dispatchers.IO) {
                    val slot = if (st.slot < 0) session.stageAuto(st) else st.slot
                    RaCommand.ask("LOAD_STATE_SLOT $slot", 3000)
                }
                if (reply?.contains("ERROR") == true) toast = context.getString(R.string.game_toast_state_other_core)
                else onResume()
            }
        }

        LaunchedEffect(toast) {
            if (toast != null) { delay(1800); toast = null }
        }
        // B on a gamepad arrives as BACK: out of a sub-page first, then back to the game.
        BackHandler { if (page != Page.Main) page = Page.Main else onResume() }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color(0x8C08080A))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onResume)
            )
            if (panel) {
                PausedArea(
                    if (session.hardcore) emptyList() else states.take(4),
                    Modifier.fillMaxSize().padding(end = panelWidth),
                    ::load,
                )
            }
            val shape = if (panel) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                if (panel) {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(panelWidth)
                        .shadow(24.dp, shape)
                        .clip(shape)
                        .background(Oe.Sheet)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .widthIn(max = if (landscape) 760.dp else 520.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Oe.Sheet)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)
                },
            ) {
                if (!panel) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(Oe.Chevron))
                    Spacer(Modifier.height(14.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page != Page.Main) {
                        SheetIconButton(Icons.Back, stringResource(R.string.game_back)) { page = Page.Main }
                    } else if (cart != null) {
                        Image(cart, null, Modifier.width(if (landscape) 56.dp else 92.dp), contentScale = ContentScale.FillWidth)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (page) { Page.Main -> session.title; Page.States -> stringResource(R.string.game_save_states); Page.Shader -> stringResource(R.string.game_shader) },
                            style = Type.Title.copy(fontSize = 17.sp, lineHeight = 21.sp),
                            maxLines = 2,
                        )
                        val minutes = ((System.currentTimeMillis() - session.startedAt) / 60000).coerceAtLeast(0)
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                when (page) {
                                    Page.Main -> if (minutes < 1) stringResource(R.string.game_playing_now, session.coreName) else stringResource(R.string.game_playing_for, minutes.toInt(), session.coreName)
                                    Page.States -> stringResource(R.string.game_tap_to_load)
                                    Page.Shader -> stringResource(R.string.game_shader_this_session)
                                },
                                style = Type.Small, modifier = Modifier.weight(1f, fill = false),
                            )
                            if (page == Page.Main && session.hardcore) Tag(stringResource(R.string.game_hardcore_tag), Oe.WarnBg, Oe.WarnText)
                        }
                    }
                    SheetIconButton(Icons.Close, stringResource(R.string.game_back_to_game), onResume)
                }

                // On the panel the page fills the height, so Quit stays at its foot.
                MarginFocusScroll { Column(Modifier.weight(1f, fill = panel).verticalScroll(rememberScrollState())) {
                if (toast != null) {
                    Row(
                        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Oe.OkBg).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Check, Oe.OkText, 18.dp, stroke = 2.4f)
                        Text(toast!!, style = Type.Body.copy(color = Oe.OkText, fontSize = 14.sp))
                    }
                }

                AnimatedContent(
                    page,
                    transitionSpec = { (fadeIn(tween(160)) + slideInVertically { it / 12 }) togetherWith fadeOut(tween(120)) },
                    label = "page",
                ) { current ->
                    when (current) {
                        Page.Main -> MainPage(
                            landscape = landscape,
                            hardcore = session.hardcore,
                            fastForward = fastForward,
                            stateCount = states.count { !it.isAutoSlot() },
                            onSave = {
                                val next = (states.filter { it.slot > 0 }.maxOfOrNull { it.slot } ?: 0) + 1
                                command("SAVE_STATE_SLOT $next", context.getString(R.string.game_toast_saved_slot, next)) {
                                    scope.launch { delay(600); refresh++ }
                                }
                            },
                            onLoad = { page = Page.States },
                            onFastForward = {
                                fastForward = !fastForward
                                onFastForward(fastForward)
                                toast = context.getString(if (fastForward) R.string.game_toast_fast_forward_on else R.string.game_toast_fast_forward_off)
                            },
                            onScreenshot = { command("SCREENSHOT", context.getString(R.string.game_toast_screenshot)) },
                            onScreens = onScreens?.let { next -> { toast = next() } },
                            onShader = { page = Page.Shader },
                            onAudio = {
                                SessionToggles.muted = !SessionToggles.muted
                                command("MUTE", context.getString(if (SessionToggles.muted) R.string.game_toast_sound_off else R.string.game_toast_sound_on))
                            },
                            onReset = { command("RESET") { onResume() } },
                            onQuit = onQuit.takeUnless { panel },
                        )
                        Page.States -> StatesPage(states, ::load)
                        Page.Shader -> ShaderPage(SessionToggles.shader ?: session.shader) { id ->
                            SessionToggles.shader = id
                            val preset = SHADERS.first { it.id == id }.preset
                            val arg = preset?.let { " " + File(session.shadersDir, it).absolutePath } ?: ""
                            command("SET_SHADER$arg", context.getString(R.string.game_toast_shader, SHADERS.first { it.id == id }.name(context)))
                        }
                    }
                }
                } }
                if (panel && page == Page.Main) QuitButton(Modifier.padding(top = 12.dp), onQuit)
                HintBar(listOf("A" to stringResource(R.string.game_choose), "B" to stringResource(R.string.game_back), "START" to stringResource(R.string.game_back_to_game)), Modifier.padding(top = 12.dp))
            }
        }
    }
}

private fun PauseSession.State.isAutoSlot() = slot < 0

@Composable
private fun SheetIconButton(path: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).gamepadFocus(corner = 22.dp).clip(CircleShape).background(Oe.Raised).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(path, Oe.Text, 18.dp, stroke = 2.4f) }
}

@Composable
private fun MainPage(
    landscape: Boolean,
    hardcore: Boolean,
    fastForward: Boolean,
    stateCount: Int,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onFastForward: () -> Unit,
    onScreenshot: () -> Unit,
    onShader: () -> Unit,
    onAudio: () -> Unit,
    onScreens: (() -> Unit)?,
    onReset: () -> Unit,
    /** Null on the tablet panel, which pins Quit at its foot. */
    onQuit: (() -> Unit)?,
) {
    val actions = @Composable { modifier: Modifier ->
        Column(modifier) {
            if (hardcore) {
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Oe.WarnBg).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Warn, Oe.WarnText, 18.dp, stroke = 2.2f)
                    Text(stringResource(R.string.game_hardcore_note), style = Type.Body.copy(color = Oe.WarnText, fontSize = 13.sp, lineHeight = 18.sp))
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.game_save_state), Icons.SaveState, Oe.Accent, Color.White, Modifier.weight(1f).restorableFocus("save", remember { mutableStateOf<String?>(null) }, initial = true), onSave)
                BigButton(stringResource(R.string.game_load), Icons.LoadState, Oe.Raised, Oe.Text, Modifier.weight(1f), onLoad, enabled = !hardcore)
            }
            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ActionTile(stringResource(R.string.game_fast_forward), Icons.FastForward, fastForward, filled = true, enabled = !hardcore, onClick = onFastForward)
                ActionTile(stringResource(R.string.game_screenshot), Icons.Camera, false, onClick = onScreenshot)
                ActionTile(stringResource(R.string.game_shader), Icons.Shader, false, onClick = onShader)
                if (onScreens != null) ActionTile(stringResource(R.string.game_screens), Icons.Screens, false, onClick = onScreens)
                ActionTile(stringResource(if (SessionToggles.muted) R.string.game_muted else R.string.game_audio), if (SessionToggles.muted) Icons.Mute else Icons.Audio, SessionToggles.muted, onClick = onAudio)
            }
        }
    }
    val rest = @Composable { modifier: Modifier ->
        Column(modifier) {
            Column(Modifier.padding(top = if (landscape) 16.dp else 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Oe.Raised)) {
                ListRow(stringResource(R.string.game_save_states), value = stateCount.toString(), onClick = onLoad, enabled = !hardcore)
                ListRow(stringResource(R.string.game_restart), divider = true, onClick = onReset)
            }
            if (onQuit != null) QuitButton(Modifier.padding(top = 12.dp), onQuit)
        }
    }
    if (landscape) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            actions(Modifier.weight(1f))
            rest(Modifier.weight(1f))
        }
    } else {
        Column {
            actions(Modifier)
            rest(Modifier)
        }
    }
}

@Composable
private fun QuitButton(modifier: Modifier, onQuit: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(50.dp).gamepadFocus(corner = 16.dp).clip(RoundedCornerShape(16.dp)).background(Oe.DangerBg)
            .clickable(role = Role.Button, onClick = onQuit),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.game_quit), style = Type.Button.copy(color = Oe.Danger, fontSize = 15.sp)) }
}

/** Left of the tablet panel, over the dimmed game: the "Paused" chip and the latest saved states, a tap loads one. */
@Composable
private fun PausedArea(states: List<PauseSession.State>, modifier: Modifier, onPick: (PauseSession.State) -> Unit) {
    Box(modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(28.dp)) {
        Text(
            stringResource(R.string.game_paused).uppercase(),
            style = Type.Tag.copy(color = Color.White),
            modifier = Modifier.align(Alignment.TopStart).clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (states.isNotEmpty()) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(stringResource(R.string.game_save_states), style = Type.Title.copy(color = Color.White, fontSize = 15.sp))
                Text(stringResource(R.string.game_tap_to_load), style = Type.Small.copy(color = Color(0xB3FFFFFF)), modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    states.forEach { st ->
                        StateCard(st, Modifier.weight(1f, fill = false).widthIn(max = 140.dp), Color(0xB3FFFFFF)) { onPick(st) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButton(text: String, icon: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier.height(56.dp).let { if (enabled) it.gamepadFocus(corner = 16.dp) else it.alpha(DISABLED_ALPHA) }
            .clip(RoundedCornerShape(16.dp)).background(bg).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, fg, 22.dp, filled = icon == Icons.SaveState || icon == Icons.LoadState)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.Button.copy(color = fg, fontSize = 15.sp))
    }
}

@Composable
private fun ActionTile(label: String, icon: String, on: Boolean, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.let { if (enabled) it.gamepadFocus(corner = 12.dp) else it.alpha(DISABLED_ALPHA) }
            .clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(18.dp))
                .background(if (on) Oe.AccentSoft else Oe.Surface2)
                .let { if (on) it.border(1.5.dp, Oe.AccentBright, RoundedCornerShape(18.dp)) else it },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, if (on) Oe.AccentOnSoft else Oe.Text, 22.dp, filled = filled) }
        Text(label, style = Type.Caption.copy(color = Oe.Text, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun StatesPage(states: List<PauseSession.State>, onPick: (PauseSession.State) -> Unit) {
    if (states.isEmpty()) {
        Text(stringResource(R.string.game_no_states), style = Type.Small, modifier = Modifier.padding(vertical = 28.dp))
        return
    }
    val focused = remember { mutableStateOf<String?>(null) }
    MarginFocusScroll { LazyVerticalGrid(
        GridCells.Adaptive(160.dp),
        Modifier.padding(top = 16.dp - FocusOutset).height(380.dp),
        contentPadding = PaddingValues(vertical = FocusOutset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(states) { st ->
            StateCard(st, Modifier.restorableFocus(st.file.path, focused, initial = st == states.first())) { onPick(st) }
        }
    } }
}

/** A saved state's thumbnail, its slot over it and its age under it; [ago] tints the age (white over the game). */
@Composable
private fun StateCard(st: PauseSession.State, modifier: Modifier, ago: Color? = null, onClick: () -> Unit) {
    Column(Modifier.gamepadFocus(corner = 10.dp).then(modifier).clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            val bmp = remember(st.thumb) { st.thumb?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(if (st.slot < 0) stringResource(R.string.game_state_auto) else stringResource(R.string.game_state_slot, st.slot), style = Type.Tag.copy(color = Color.White, fontSize = 10.sp))
            }
        }
        Text(formatAgo(st.modified), style = if (ago != null) Type.Caption.copy(color = ago) else Type.Caption, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
    }
}

@Composable
private fun ShaderPage(current: String, onPick: (String) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val focused = remember { mutableStateOf<String?>(null) }
        SHADERS.forEach { s ->
            Box(Modifier.restorableFocus(s.id, focused, initial = s.id == current)) {
                RadioCard(selected == s.id, s.name(), shaderBlurb(s.id), onClick = { selected = s.id; onPick(s.id) }, background = Oe.Raised)
            }
        }
    }
}

@Composable
fun shaderBlurb(id: String) = when (id) {
    "none" -> stringResource(R.string.game_shader_blurb_none)
    "dot" -> stringResource(R.string.game_shader_blurb_dot)
    "sharp" -> stringResource(R.string.game_shader_blurb_sharp)
    "zfast" -> stringResource(R.string.game_shader_blurb_zfast)
    "geom" -> stringResource(R.string.game_shader_blurb_geom)
    else -> ""
}
