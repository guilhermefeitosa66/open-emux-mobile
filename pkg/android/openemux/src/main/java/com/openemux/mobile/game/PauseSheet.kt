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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.SHADERS
import com.openemux.mobile.ui.Icon
import com.openemux.mobile.ui.Icons
import com.openemux.mobile.ui.ListRow
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.OpenEmuxTheme
import com.openemux.mobile.ui.RadioCard
import com.openemux.mobile.ui.Type
import com.openemux.mobile.ui.formatAgo
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
        fun fromIntent(intent: Intent, startedAt: Long) = PauseSession(
            title = intent.getStringExtra(GameExtras.TITLE) ?: "",
            cartPath = intent.getStringExtra(GameExtras.CART),
            statesDir = File(intent.getStringExtra(GameExtras.STATES_DIR) ?: "/"),
            romBase = intent.getStringExtra(GameExtras.ROM_BASE) ?: "",
            shadersDir = File(intent.getStringExtra(GameExtras.SHADERS_DIR) ?: "/"),
            shader = intent.getStringExtra(GameExtras.SHADER) ?: "none",
            coreName = intent.getStringExtra(GameExtras.CORE_NAME) ?: "",
            startedAt = startedAt,
        )
    }
}

/** Survives the dialog: toggles the engine keeps for the whole session. */
private object SessionToggles {
    var fastForward = false
    var muted = false
    var shader: String? = null
}

private enum class Page { Main, States, Shader }

@Composable
fun PauseSheet(session: PauseSession, onResume: () -> Unit, onQuit: () -> Unit) {
    OpenEmuxTheme {
        var page by remember { mutableStateOf(Page.Main) }
        var toast by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val states = remember(refresh) { session.states() }
        val cart = remember { session.cartPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }

        fun command(cmd: String, message: String? = null, after: () -> Unit = {}) {
            scope.launch {
                withContext(Dispatchers.IO) { RaCommand.send(cmd) }
                if (message != null) toast = message
                after()
            }
        }

        LaunchedEffect(toast) {
            if (toast != null) { delay(1800); toast = null }
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color(0x8C08080A))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onResume)
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Oe.Sheet)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(Color(0xFF3A3A45)))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page != Page.Main) {
                        SheetIconButton(Icons.Back, "Voltar") { page = Page.Main }
                    } else if (cart != null) {
                        Image(cart, null, Modifier.width(92.dp), contentScale = ContentScale.FillWidth)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (page) { Page.Main -> session.title; Page.States -> "Estados salvos"; Page.Shader -> "Shader" },
                            style = Type.Title.copy(fontSize = 17.sp, lineHeight = 21.sp),
                            maxLines = 2,
                        )
                        val minutes = ((System.currentTimeMillis() - session.startedAt) / 60000).coerceAtLeast(0)
                        Text(
                            when (page) {
                                Page.Main -> if (minutes < 1) "Jogando agora · ${session.coreName}" else "Jogando há $minutes min · ${session.coreName}"
                                Page.States -> "Toque para carregar"
                                Page.Shader -> "Vale só para esta partida"
                            },
                            style = Type.Small, modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    SheetIconButton(Icons.Close, "Voltar ao jogo", onResume)
                }

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
                            session = session,
                            stateCount = states.count { !it.isAutoSlot() },
                            onSave = {
                                val next = (states.filter { it.slot > 0 }.maxOfOrNull { it.slot } ?: 0) + 1
                                command("SAVE_STATE_SLOT $next", "Estado salvo no slot $next") {
                                    scope.launch { delay(600); refresh++ }
                                }
                            },
                            onLoad = { page = Page.States },
                            onFastForward = {
                                SessionToggles.fastForward = !SessionToggles.fastForward
                                command("FAST_FORWARD", if (SessionToggles.fastForward) "Acelerado ao voltar" else "Velocidade normal")
                            },
                            onScreenshot = { command("SCREENSHOT", "Captura salva") },
                            onShader = { page = Page.Shader },
                            onAudio = {
                                SessionToggles.muted = !SessionToggles.muted
                                command("MUTE", if (SessionToggles.muted) "Som desligado" else "Som ligado")
                            },
                            onReset = { command("RESET") { onResume() } },
                            onQuit = onQuit,
                        )
                        Page.States -> StatesPage(states) { st ->
                            scope.launch {
                                val reply = withContext(Dispatchers.IO) {
                                    val slot = if (st.slot < 0) session.stageAuto(st) else st.slot
                                    RaCommand.ask("LOAD_STATE_SLOT $slot", 3000)
                                }
                                if (reply?.contains("ERROR") == true) toast = "Este estado é de outro core"
                                else onResume()
                            }
                        }
                        Page.Shader -> ShaderPage(SessionToggles.shader ?: session.shader) { id ->
                            SessionToggles.shader = id
                            val preset = SHADERS.first { it.id == id }.preset
                            val arg = preset?.let { " " + File(session.shadersDir, it).absolutePath } ?: ""
                            command("SET_SHADER$arg", "Shader: ${SHADERS.first { it.id == id }.name}")
                        }
                    }
                }
            }
        }
    }
}

private fun PauseSession.State.isAutoSlot() = slot < 0

@Composable
private fun SheetIconButton(path: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2A2A33)).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(path, Oe.Text, 18.dp, stroke = 2.4f) }
}

@Composable
private fun MainPage(
    session: PauseSession,
    stateCount: Int,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onFastForward: () -> Unit,
    onScreenshot: () -> Unit,
    onShader: () -> Unit,
    onAudio: () -> Unit,
    onReset: () -> Unit,
    onQuit: () -> Unit,
) {
    Column {
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Salvar estado", Icons.Save, Oe.Accent, Color.White, Modifier.weight(1f), onSave)
            BigButton("Carregar", Icons.Load, Color(0xFF2A2A33), Oe.Text, Modifier.weight(1f), onLoad)
        }
        Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionTile("Acelerar", Icons.FastForward, SessionToggles.fastForward, filled = true, onClick = onFastForward)
            ActionTile("Captura", Icons.Camera, false, onClick = onScreenshot)
            ActionTile("Shader", Icons.Shader, false, onClick = onShader)
            ActionTile(if (SessionToggles.muted) "Sem som" else "Áudio", if (SessionToggles.muted) Icons.Mute else Icons.Audio, SessionToggles.muted, onClick = onAudio)
        }
        Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF22222A))) {
            ListRow("Estados salvos", value = stateCount.toString(), onClick = onLoad)
            ListRow("Reiniciar jogo", divider = true, onClick = onReset)
        }
        Box(
            Modifier.padding(top = 12.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(Oe.DangerBg)
                .clickable(role = Role.Button, onClick = onQuit),
            contentAlignment = Alignment.Center,
        ) { Text("Sair do jogo", style = Type.Button.copy(color = Oe.Danger, fontSize = 15.sp)) }
    }
}

@Composable
private fun BigButton(text: String, icon: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, fg, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.Button.copy(color = fg, fontSize = 15.sp))
    }
}

@Composable
private fun ActionTile(label: String, icon: String, on: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(18.dp))
                .background(if (on) Color(0xFF3B2A6B) else Color(0xFF26262E))
                .let { if (on) it.border(1.5.dp, Oe.AccentBright, RoundedCornerShape(18.dp)) else it },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, if (on) Color(0xFFD4C4FF) else Color(0xFFE6E6EC), 22.dp, filled = filled) }
        Text(label, style = Type.Caption.copy(color = Color(0xFFE6E6EC), fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun StatesPage(states: List<PauseSession.State>, onPick: (PauseSession.State) -> Unit) {
    if (states.isEmpty()) {
        Text("Nenhum estado salvo ainda.", style = Type.Small, modifier = Modifier.padding(vertical = 28.dp))
        return
    }
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.padding(top = 16.dp).height(380.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(states) { st ->
            Column(Modifier.clickable(role = Role.Button) { onPick(st) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                    val bmp = remember(st.thumb) { st.thumb?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(if (st.slot < 0) "AUTO" else "SLOT ${st.slot}", style = Type.Tag.copy(color = Color.White, fontSize = 10.sp))
                    }
                }
                Text(formatAgo(st.modified), style = Type.Caption, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
            }
        }
    }
}

@Composable
private fun ShaderPage(current: String, onPick: (String) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SHADERS.forEach { s ->
            RadioCard(selected == s.id, s.name, shaderBlurb(s.id), onClick = { selected = s.id; onPick(s.id) }, background = Color(0xFF22222A))
        }
    }
}

fun shaderBlurb(id: String) = when (id) {
    "none" -> "Pixels puros, sem efeito."
    "dot" -> "Matriz de pontos de tela portátil."
    "sharp" -> "Pixels nítidos sem serrilhado."
    "zfast" -> "Linhas de varredura leves de CRT."
    "geom" -> "CRT curvo, com brilho e máscara."
    else -> ""
}
