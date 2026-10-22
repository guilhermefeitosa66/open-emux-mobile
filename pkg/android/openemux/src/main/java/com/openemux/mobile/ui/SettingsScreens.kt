package com.openemux.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.CART_COLORS
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.ImportResult
import com.openemux.mobile.data.KNOWN_BIOS
import com.openemux.mobile.data.SHADERS
import com.openemux.mobile.data.shaderById
import kotlinx.coroutines.launch

@Composable
private fun SectionHeader(text: String, trailing: String? = null) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text.uppercase(), style = Type.SectionCaps, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Type.Caption.copy(color = Oe.Faint))
    }
}

@Composable
fun SettingsScreen() {
    val a = LocalActions.current
    val lib = a.app.library
    // Recomposes the console rows when a default changes.
    @Suppress("UNUSED_VARIABLE") val meta by lib.meta.collectAsState()
    val roms by lib.roms.collectAsState()
    val context = LocalContext.current
    val missingOptional = KNOWN_BIOS.count { !a.app.importer.biosPresent(it) }
    val missingRequired = KNOWN_BIOS.count { it.required && !a.app.importer.biosPresent(it) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text("Ajustes", style = Type.Display, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))

        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Oe.Surface)
                .border(1.dp, Oe.Border, RoundedCornerShape(20.dp)).clickable(role = Role.Button) { a.nav.navigate(Routes.BIOS) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val ok = missingOptional == 0
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (ok) Oe.OkBg else Color(0xFF3A2A12)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Chip, if (ok) Oe.Ok else Oe.Warn, 24.dp) }
            Column(Modifier.weight(1f)) {
                Text("BIOS", style = Type.Section.copy(fontSize = 16.sp))
                Text(
                    when {
                        missingRequired > 0 -> "$missingRequired ${if (missingRequired == 1) "console precisa" else "consoles precisam"} de arquivos"
                        missingOptional > 0 -> "Opcional para o GBA: deixa o boot igual ao original"
                        else -> "Tudo pronto"
                    },
                    style = Type.Small, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Oe.Accent).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("Importar", style = Type.Button.copy(fontSize = 13.sp))
            }
        }

        SectionHeader("Consoles", "core · shader")
        Card(Modifier.padding(horizontal = 20.dp)) {
            Console.entries.forEachIndexed { i, c ->
                val s = lib.consoleSettings(c)
                ListRow(
                    c.displayName,
                    subtitle = "${c.core(s.core).name} · ${shaderById(s.shader).name}",
                    divider = i > 0, minHeight = 60.dp,
                    leading = { ConsoleChip(c) },
                    onClick = { a.nav.navigate(Routes.console(c.id)) },
                )
            }
        }

        SectionHeader("Geral")
        Card(Modifier.padding(horizontal = 20.dp)) {
            ListRow("Pasta de ROMs", subtitle = "Android/data/${context.packageName}/files/roms", value = "${roms.size}")
            ListRow("Controles", value = "Toque + controle físico", divider = true)
            ListRow("Sobre", value = "OpenEmux 0.1 · RetroArch", divider = true)
        }
        Spacer(BottomSpace)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSettingsScreen(id: String) {
    val a = LocalActions.current
    val lib = a.app.library
    val console = Console.byId(id) ?: return
    val meta by lib.meta.collectAsState()
    val roms by lib.roms.collectAsState()
    val settings = lib.consoleSettings(console)
    val own = remember(meta, roms) { lib.romsWithOwnCore(console) }
    val count = roms.count { it.console == console }
    var colorSheet by remember { mutableStateOf(false) }
    var ownSheet by remember { mutableStateOf(false) }
    val sample = roms.firstOrNull { it.console == console }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
            BackLink("Ajustes") { a.nav.popBackStack() }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PADRÕES DO CONSOLE", style = Type.Eyebrow)
                    Text(console.displayName, style = Type.Display.copy(fontSize = 28.sp, lineHeight = 30.sp), modifier = Modifier.padding(top = 4.dp))
                }
                Box(Modifier.width(92.dp).rotate(6f)) {
                    Cartridge(
                        console, sample?.let { labelFor(it) }, sample?.title ?: console.displayName,
                        settings.color, Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Text("Core padrão", style = Type.Section, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            console.cores.forEach { c ->
                RadioCard(
                    settings.core == c.id, c.name, c.description,
                    onClick = { lib.updateConsole(console) { it.copy(core = c.id) } },
                    badge = if (c.recommended) "RECOMENDADO" else null,
                )
            }
        }

        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("Shader padrão", style = Type.Section, modifier = Modifier.weight(1f))
            Text(shaderById(settings.shader).name, style = Type.Small)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SHADERS.forEach { s ->
                ShaderTile(s.id, s.name, settings.shader == s.id) { lib.updateConsole(console) { it.copy(shader = s.id) } }
            }
        }

        Card(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
            val bios = KNOWN_BIOS.firstOrNull { it.console == console }
            if (bios != null) {
                val present = a.app.importer.biosPresent(bios)
                ListRow(
                    "BIOS", subtitle = "${bios.fileName} · ${if (bios.required) "obrigatória" else "opcional"}",
                    onClick = { a.nav.navigate(Routes.BIOS) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(if (present) Icons.Check else Icons.Alert, if (present) Oe.Ok else Oe.Muted, 16.dp, stroke = 2.4f)
                            Text(if (present) "Encontrada" else "Faltando", style = Type.Section.copy(fontSize = 13.sp, color = if (present) Oe.Ok else Oe.Muted))
                        }
                    },
                )
            } else {
                ListRow("BIOS", subtitle = "Este console não precisa", value = "—")
            }
            ListRow("Skin padrão", value = "OpenEmux ${console.short}", divider = true)
            ListRow(
                "Cor dos cartuchos", value = CART_COLORS.first { it.id == settings.color }.name, divider = true,
                onClick = { colorSheet = true },
                trailing = { Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(CART_COLORS.first { it.id == settings.color }.swatch)) },
            )
        }

        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Info, Oe.Muted, 18.dp)
            val text = if (own.isEmpty()) "Vale para todos os $count jogos do ${console.short}."
            else "Vale para todos os $count jogos, menos ${own.size} que ${if (own.size == 1) "tem" else "têm"} core próprio."
            Column {
                Text(text, style = Type.Small)
                if (own.isNotEmpty()) {
                    Text(
                        "Ver quais", style = Type.Small.copy(color = Oe.AccentText),
                        modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { ownSheet = true },
                    )
                }
            }
        }
    }

    if (colorSheet) {
        ModalBottomSheet({ colorSheet = false }, containerColor = Oe.Sheet) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Cor dos cartuchos", style = Type.Title.copy(fontSize = 22.sp))
                Text("Vale para os jogos do ${console.short} que não têm cor própria.", style = Type.Small, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                if (sample != null) {
                    Cartridge(console, labelFor(sample), sample.title, settings.color, Modifier.align(Alignment.CenterHorizontally).width(220.dp))
                    Spacer(Modifier.height(16.dp))
                }
                ColorSwatches(settings.color) { c -> lib.updateConsole(console) { it.copy(color = c) } }
            }
        }
    }
    if (ownSheet) {
        ModalBottomSheet({ ownSheet = false }, containerColor = Oe.Sheet) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Com core próprio", style = Type.Title.copy(fontSize = 22.sp))
                Spacer(Modifier.height(12.dp))
                own.forEach { rom ->
                    ListRow(rom.title, value = lib.effectiveCore(rom).name, onClick = { ownSheet = false; a.nav.navigate(Routes.rom(rom.key)) })
                }
            }
        }
    }
}

@Composable
fun BiosScreen() {
    val a = LocalActions.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var working by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            working = true
            scope.launch {
                result = runCatching { a.app.importer.importBiosTree(uri) }.getOrDefault(ImportResult(0, 0))
                working = false
                refresh++
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
            BackLink("Ajustes") { a.nav.popBackStack() }
            Text("BIOS", style = Type.Display, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Alguns consoles só ligam com o arquivo de sistema original. Use os que você extraiu do seu próprio aparelho.",
                style = Type.Small.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(top = 8.dp),
            )
        }
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Oe.Surface)
                .border(1.dp, Oe.Border, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (working) {
                Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Oe.AccentText, strokeWidth = 2.dp)
                    Text("  Conferindo os arquivos...", style = Type.Body)
                }
            } else {
                PrimaryButton("Importar do aparelho", { picker.launch(null) }, Modifier.fillMaxWidth(), icon = Icons.Folder, height = 54.dp)
            }
            Text(
                "Escolha a pasta onde estão os arquivos (Downloads, cartão SD...). O OpenEmux confere cada um pelo hash e copia para o console certo. Nome do arquivo não importa.",
                style = Type.Small,
            )
        }
        result?.let { r ->
            val ok = r.imported > 0
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (ok) Oe.OkBg else Oe.WarnBg).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (ok) Icons.Check else Icons.Alert, if (ok) Oe.OkText else Oe.WarnText, 20.dp, stroke = 2.4f)
                Text(
                    if (ok) "${r.imported} ${if (r.imported == 1) "arquivo reconhecido e instalado" else "arquivos reconhecidos e instalados"}." + if (r.skipped > 0) " ${r.skipped} ${if (r.skipped == 1) "ignorado" else "ignorados"}." else ""
                    else "Nenhuma BIOS conhecida nessa pasta. ${r.skipped} arquivos conferidos.",
                    style = Type.Body.copy(fontSize = 14.sp, color = if (ok) Oe.OkText else Oe.WarnText),
                )
            }
        }

        val rows = remember(refresh) {
            Console.entries.map { c ->
                val def = KNOWN_BIOS.firstOrNull { it.console == c }
                Triple(c, def, def?.let { a.app.importer.biosPresent(it) })
            }
        }
        SectionHeader("Seus consoles", "${rows.count { it.second == null || it.third == true }} de ${rows.size} prontos")
        Card(Modifier.padding(horizontal = 20.dp)) {
            rows.forEachIndexed { i, (c, def, present) ->
                ListRow(
                    c.displayName,
                    subtitle = def?.fileName ?: "Não precisa de BIOS",
                    divider = i > 0, minHeight = 64.dp,
                    trailing = {
                        if (def != null) Tag(if (def.required) "Obrigatória" else "Opcional", Color(0xFF26262E), Color(0xFFB4B4C0))
                        val ok = def == null || present == true
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(if (ok) Icons.Check else Icons.Alert, if (ok) Oe.Ok else Oe.Muted, 16.dp, stroke = 2.4f)
                            Text(if (ok) "OK" else "Faltando", style = Type.Section.copy(fontSize = 13.sp, color = if (ok) Oe.Ok else Oe.Muted))
                        }
                    },
                )
            }
        }
        Text(
            "Só aparecem os consoles desta versão (GBA e Super Nintendo).",
            style = Type.Small.copy(color = Oe.Faint), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        )
    }
}

@Composable
fun ImportScreen() {
    val a = LocalActions.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var working by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun run(block: suspend () -> ImportResult) {
        working = true
        scope.launch {
            result = runCatching { block() }.getOrDefault(ImportResult(0, 0))
            a.app.library.scan()
            working = false
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run { a.app.importer.importRoms(uris) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run { a.app.importer.importRomTree(uri) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text("Importar", style = Type.Display, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
        Text(
            "Traga as ROMs dos jogos que você tem. O OpenEmux reconhece o console pela extensão e abre arquivos .zip sozinho.",
            style = Type.Small.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (working) {
                Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Oe.AccentText, strokeWidth = 2.dp)
                    Text("  Copiando...", style = Type.Body)
                }
            } else {
                PrimaryButton("Escolher arquivos", { files.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), icon = Icons.File, height = 54.dp)
                SecondaryButton("Escolher uma pasta inteira", { folder.launch(null) }, Modifier.fillMaxWidth(), icon = Icons.Folder, height = 54.dp)
            }
        }
        result?.let { r ->
            val ok = r.imported > 0
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (ok) Oe.OkBg else Oe.WarnBg).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (ok) Icons.Check else Icons.Alert, if (ok) Oe.OkText else Oe.WarnText, 20.dp, stroke = 2.4f)
                Text(
                    if (ok) "${r.imported} ${if (r.imported == 1) "jogo importado" else "jogos importados"}." + if (r.skipped > 0) " ${r.skipped} ${if (r.skipped == 1) "arquivo ignorado" else "arquivos ignorados"}." else ""
                    else "Nenhuma ROM de GBA ou SNES encontrada.",
                    style = Type.Body.copy(fontSize = 14.sp, color = if (ok) Oe.OkText else Oe.WarnText),
                    modifier = Modifier.weight(1f),
                )
                if (ok) Text(
                    "Ver", style = Type.Section.copy(color = Oe.OkText),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { a.nav.navigate(Routes.SHELF) { popUpTo(Routes.SHELF) { inclusive = true } } }.padding(6.dp),
                )
            }
        }

        SectionHeader("Formatos desta versão")
        Card(Modifier.padding(horizontal = 20.dp)) {
            Console.entries.forEachIndexed { i, c ->
                ListRow(
                    c.displayName, subtitle = c.extensions.joinToString("  ") { ".$it" } + "  .zip",
                    divider = i > 0, minHeight = 60.dp, leading = { ConsoleChip(c) },
                )
            }
        }
        SectionHeader("Onde ficam")
        Text(
            "Android/data/${context.packageName}/files/roms. Você também pode copiar direto para lá pelo computador.",
            style = Type.Small.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(BottomSpace)
    }
}
