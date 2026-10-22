package com.openemux.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.CART_COLORS
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.SHADERS
import com.openemux.mobile.data.SaveState
import com.openemux.mobile.data.shaderById

private enum class Sheet { None, Core, Shader, States }

@Composable
fun DetailScreen(key: String) {
    val a = LocalActions.current
    val lib = a.app.library
    val roms by lib.roms.collectAsState()
    val meta by lib.meta.collectAsState()
    val revision by lib.revision.collectAsState()
    val rom = roms.firstOrNull { it.key == key } ?: return
    val romMeta = meta.roms[key]
    val states = remember(rom, revision) { lib.states(rom) }
    val auto = states.firstOrNull { it.isAuto }
    val playtime = remember(rom, revision) { lib.playtimeSeconds(rom) }
    val core = lib.effectiveCore(rom)
    val shader = lib.effectiveShader(rom)
    val color = lib.effectiveColor(rom)
    var sheet by remember { mutableStateOf(Sheet.None) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // The violet glow behind the cartridge.
        Box(
            Modifier.fillMaxWidth().height(340.dp).offset(y = 40.dp).drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x617C3AED), Color(0x007C3AED)), center = Offset(size.width / 2, size.height / 2), radius = size.width * 0.6f),
                    radius = size.width * 0.6f,
                )
            }
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Back, "Voltar", { a.nav.popBackStack() }, background = Color(0xCC282832))
                Spacer(Modifier.weight(1f))
                RoundIconButton(
                    Icons.Star, if (romMeta?.favorite == true) "Remover dos favoritos" else "Adicionar aos favoritos",
                    { lib.updateRom(key) { it.copy(favorite = !it.favorite) } },
                    tint = if (romMeta?.favorite == true) Oe.Star else Oe.Muted, background = Color(0xCC282832),
                    filled = romMeta?.favorite == true,
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    RoundIconButton(Icons.Dots, "Mais opções", { menu = true }, background = Color(0xCC282832), filled = true)
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Oe.Surface2) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Remover da biblioteca", style = Type.Body.copy(color = Oe.Danger)) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cartridge(rom.console, a.app.covers.urlFor(rom), rom.title, color, Modifier.width(264.dp))
                Box(Modifier.padding(top = 6.dp).size(220.dp, 10.dp).blur(6.dp).clip(CircleShape).background(Color(0x8C000000)))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rom.title, style = Type.Title, textAlign = TextAlign.Center)
                Text(
                    "${rom.console.displayName} · ${core.name} · ${formatPlaytime(playtime)}",
                    style = Type.Small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (auto != null) {
                    PrimaryButton("Continuar", { a.play(rom, true, null) }, Modifier.weight(1f), icon = Icons.Play)
                    SecondaryButton("Reiniciar", { a.play(rom, false, null) }, Modifier.width(120.dp))
                } else {
                    PrimaryButton("Jogar", { a.play(rom, false, null) }, Modifier.weight(1f), icon = Icons.Play)
                }
            }

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Estados salvos", style = Type.Section, modifier = Modifier.weight(1f))
                    if (states.isNotEmpty()) {
                        Text(
                            "Ver todos (${states.size})", style = Type.Small.copy(color = Oe.AccentText),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { sheet = Sheet.States }.padding(4.dp),
                        )
                    }
                }
                if (states.isEmpty()) {
                    Text("Nenhum ainda. Salve pelo menu durante o jogo.", style = Type.Small, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        states.take(3).forEach { st ->
                            StateThumb(st, Modifier.weight(1f)) { a.play(rom, st.isAuto, st.slot.takeIf { !st.isAuto }) }
                        }
                        repeat(3 - states.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cor do cartucho", style = Type.Section, modifier = Modifier.weight(1f))
                    Text(
                        CART_COLORS.first { it.id == color }.name + if (romMeta?.color == null) " · padrão" else "",
                        style = Type.Small,
                    )
                }
                ColorSwatches(color, Modifier.padding(top = 10.dp)) { id -> lib.updateRom(key) { it.copy(color = id) } }
            }

            Card(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                ListRow("Core", value = core.name, onClick = { sheet = Sheet.Core },
                    trailing = { if (romMeta?.core == null) Tag("PADRÃO", Oe.Surface2, Color(0xFFB4B4C0)) })
                ListRow("Shader", value = shader.name, divider = true, onClick = { sheet = Sheet.Shader },
                    trailing = { if (romMeta?.shader == null) Tag("PADRÃO", Oe.Surface2, Color(0xFFB4B4C0)) })
                ListRow("Skin do controle", value = "OpenEmux ${rom.console.short}", divider = true)
                ListRow("Trapaças", value = "Em breve", divider = true)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    when (sheet) {
        Sheet.Core -> CoreSheet(rom) { sheet = Sheet.None }
        Sheet.Shader -> ShaderSheet(rom) { sheet = Sheet.None }
        Sheet.States -> StatesSheet(rom, states) { sheet = Sheet.None }
        Sheet.None -> {}
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Oe.Sheet,
            title = { Text("Remover ${rom.title}?", style = Type.Title.copy(fontSize = 20.sp)) },
            text = { Text("O arquivo da ROM sai do aparelho. Saves e estados ficam guardados, caso você importe de novo.", style = Type.Small) },
            confirmButton = {
                TextButton({ confirmDelete = false; lib.delete(rom); a.nav.popBackStack() }) { Text("Remover", style = Type.Section.copy(color = Oe.Danger)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar", style = Type.Section) } },
        )
    }
}

@Composable
private fun StateThumb(st: SaveState, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            val bmp = remember(st.thumbnail) { st.thumbnail?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(if (st.isAuto) "AUTO" else "SLOT ${st.slot}", style = Type.Tag.copy(color = Color.White, fontSize = 10.sp))
            }
        }
        Text(formatAgo(st.modified), style = Type.Caption, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
    }
}

@Composable
fun ColorSwatches(selected: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CART_COLORS.forEach { c ->
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .border(2.dp, if (c.id == selected) Oe.AccentText else Color.Transparent, CircleShape)
                    .clickable(onClickLabel = c.name, role = Role.RadioButton) { onPick(c.id) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(c.swatch).border(1.dp, Color(0x2EFFFFFF), CircleShape))
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
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Core desta ROM", style = Type.Title.copy(fontSize = 22.sp))
            Text(rom.title, style = Type.Small, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            RadioCard(
                choice == null, "Padrão do console",
                "Hoje é o ${defaultCore.name}. Se o padrão do ${rom.console.short} mudar, esta ROM muda junto.",
                onClick = { lib.updateRom(rom.key) { it.copy(core = null) } },
                background = Color(0xFF22222A),
            )
            Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("OU FIXAR SÓ NESTA ROM", style = Type.Tag.copy(color = Oe.Faint, fontSize = 12.sp, letterSpacing = 1.sp))
                HorizontalDivider(Modifier.padding(start = 10.dp).weight(1f), color = Oe.Divider)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rom.console.cores.forEach { c ->
                    RadioCard(
                        choice == c.id, c.name,
                        if (c.id == defaultCore.id) "Fica no ${c.name} mesmo se o padrão mudar." else c.description,
                        onClick = { lib.updateRom(rom.key) { it.copy(core = c.id) } },
                        background = Color(0xFF22222A),
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
                        "Estados salvos só abrem no core em que foram criados. Os ${states.size} estados desta ROM são do ${lib.effectiveCore(rom).name}. O save do jogo (.srm) continua valendo.",
                        style = Type.Small.copy(color = Oe.WarnText),
                    )
                }
            }
            PrimaryButton("Concluir", onClose, Modifier.padding(top = 18.dp).fillMaxWidth(), height = 54.dp)
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
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Shader desta ROM", style = Type.Title.copy(fontSize = 22.sp))
            Text(rom.title, style = Type.Small, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            RadioCard(
                choice == null, "Padrão do console", "Hoje é ${default.name}. Acompanha o padrão do ${rom.console.short}.",
                onClick = { lib.updateRom(rom.key) { it.copy(shader = null) } }, background = Color(0xFF22222A),
            )
            Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("OU FIXAR SÓ NESTA ROM", style = Type.Tag.copy(color = Oe.Faint, fontSize = 12.sp, letterSpacing = 1.sp))
                HorizontalDivider(Modifier.padding(start = 10.dp).weight(1f), color = Oe.Divider)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SHADERS.forEach { s ->
                    ShaderTile(s.id, s.name, choice == s.id) { lib.updateRom(rom.key) { it.copy(shader = s.id) } }
                }
            }
            PrimaryButton("Concluir", onClose, Modifier.padding(top = 18.dp).fillMaxWidth(), height = 54.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatesSheet(rom: Rom, states: List<SaveState>, onClose: () -> Unit) {
    val a = LocalActions.current
    ModalBottomSheet(onClose, containerColor = Oe.Sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Estados salvos", style = Type.Title.copy(fontSize = 22.sp))
            Text("Toque para abrir o jogo nesse ponto", style = Type.Small, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            LazyVerticalGrid(
                GridCells.Fixed(2), Modifier.height(420.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(states) { st ->
                    Column(Modifier.clickable(role = Role.Button) { onClose(); a.play(rom, st.isAuto, st.slot.takeIf { !st.isAuto }) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                            val bmp = remember(st.thumbnail) { st.thumbnail?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
                            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(if (st.isAuto) "AUTO" else "SLOT ${st.slot}", style = Type.Tag.copy(color = Color.White, fontSize = 10.sp))
                            }
                        }
                        Text(formatAgo(st.modified), style = Type.Caption, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
                    }
                }
            }
        }
    }
}

/** A frame of a game with the shader's look painted over it. */
@Composable
fun ShaderTile(id: String, name: String, selected: Boolean, onClick: () -> Unit) {
    val crt = id == "zfast" || id == "geom"
    Column(Modifier.width(108.dp).clickable(onClickLabel = name, role = Role.RadioButton, onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(108.dp, 76.dp).clip(RoundedCornerShape(if (crt) 16.dp else 10.dp))
                .border(if (selected) 2.5.dp else 1.dp, if (selected) Oe.AccentBright else Oe.Border, RoundedCornerShape(if (crt) 16.dp else 10.dp))
                .background(Color.Black),
        ) {
            Image(
                androidx.compose.ui.res.painterResource(com.retroarch.R.drawable.sample_gameplay), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alpha = if (id == "sharp") 1f else 0.95f,
            )
            Box(Modifier.fillMaxSize().drawBehind { drawShaderLook(id) })
        }
        Text(name, style = Type.Caption.copy(color = Color(0xFFE6E6EC)), modifier = Modifier.alpha(if (selected) 1f else 0.9f))
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
