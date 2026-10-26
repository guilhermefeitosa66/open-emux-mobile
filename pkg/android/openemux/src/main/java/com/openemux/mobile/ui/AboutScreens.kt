package com.openemux.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.UpdateState
import com.retroarch.BuildConfig
import com.retroarch.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SITE = "https://guilhermefeitosa66.github.io/OpenEmux/"
private const val GITHUB = "https://github.com/guilhermefeitosa66/OpenEmux"
private const val REDDIT = "https://www.reddit.com/r/OpenEmux/"

/** Where this fork's source is published, as the GPLv3 asks of the APK. */
private val FORK_SOURCE: String? = "https://github.com/guilhermefeitosa66/open-emux-mobile"

@Composable
private fun openUrl(): (String) -> Unit {
    val context = LocalContext.current
    return { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
}

@Composable
fun AboutScreen() {
    GamepadButtons(hints = listOf("A" to stringResource(R.string.about_hint_open), "B" to stringResource(R.string.settings_hint_back)))
    val a = LocalActions.current
    val open = openUrl()
    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            BackLink(stringResource(R.string.settings_title)) { a.nav.popBackStack() }
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.oe_logo), contentDescription = null, modifier = Modifier.size(88.dp))
            Text("OpenEmux", style = Type.Display, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = Type.Small, modifier = Modifier.padding(top = 4.dp))
            UpdateStatus(open)
            Text(stringResource(R.string.about_comments), style = Type.Body.copy(color = Oe.Muted), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.library_powered_by) + " RetroArch", style = Type.Caption.copy(color = Oe.Faint))
                Image(painterResource(R.drawable.ic_retroarch), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(Oe.Faint))
            }
        }

        SectionHeader(stringResource(R.string.about_section_project))
        Card(Modifier.padding(horizontal = 20.dp)) {
            ListRow(stringResource(R.string.about_site), subtitle = "guilhermefeitosa66.github.io/OpenEmux", onClick = { open(SITE) })
            ListRow("GitHub", subtitle = "guilhermefeitosa66/OpenEmux", divider = true, onClick = { open(GITHUB) })
            ListRow("Reddit", subtitle = "r/OpenEmux", divider = true, onClick = { open(REDDIT) })
            ListRow(stringResource(R.string.about_releases), divider = true, onClick = { open("$GITHUB/releases") })
            ListRow(stringResource(R.string.about_report_issue), divider = true, onClick = { open("$GITHUB/issues") })
            ListRow("RetroArch", subtitle = "retroarch.com", divider = true, onClick = { open("https://www.retroarch.com/") })
        }

        SectionHeader(stringResource(R.string.about_section_license))
        Card(Modifier.padding(horizontal = 20.dp)) {
            ListRow(
                stringResource(R.string.about_license_title), subtitle = stringResource(R.string.about_license_body), subtitleLines = 6,
                onClick = { a.nav.navigate(Routes.license("gpl")) },
            )
            FORK_SOURCE?.let { url -> ListRow(stringResource(R.string.about_source), subtitle = url, divider = true, onClick = { open(url) }) }
            ListRow(stringResource(R.string.about_licenses), divider = true, onClick = { a.nav.navigate(Routes.LICENSES) })
        }
        Text(
            stringResource(R.string.about_authors), style = Type.Caption.copy(color = Oe.Faint), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
        Spacer(Modifier.height(hintBarInset))
    } }
}

/** "Up to date" or the newer version after a check; nothing while it is off, running or failed. */
@Composable
private fun UpdateStatus(open: (String) -> Unit) {
    val state by LocalActions.current.app.updates.state.collectAsState()
    val pill = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50))
    when (val s = state) {
        UpdateState.UpToDate -> Text(
            stringResource(R.string.about_up_to_date), style = Type.Caption.copy(color = Oe.OkText, fontWeight = FontWeight.SemiBold),
            modifier = pill.background(Oe.OkBg).padding(horizontal = 12.dp, vertical = 5.dp),
        )
        is UpdateState.Available -> Text(
            stringResource(R.string.update_available_title, s.release.version), style = Type.Caption.copy(color = Oe.AccentOnSoft, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 8.dp).gamepadFocus(corner = 50.dp).clip(RoundedCornerShape(50)).background(Oe.AccentSoft)
                .clickable(role = Role.Button) { open(s.release.url) }.padding(horizontal = 12.dp, vertical = 5.dp),
        )
        UpdateState.Unknown -> Unit
    }
}

/** One shipped or downloaded component and its license, in its original English. */
private class Notice(val name: String, val license: String, val source: String?, val text: String? = null)

@Composable
fun LicensesScreen() {
    GamepadButtons(hints = listOf("A" to stringResource(R.string.about_hint_open), "B" to stringResource(R.string.settings_hint_back)))
    val a = LocalActions.current
    val open = openUrl()
    val infoDir = a.app.library.paths.info
    // The cores come from Console.kt and their licenses from the .info files the setup installed.
    val cores by produceState(emptyList<Notice>()) {
        value = withContext(Dispatchers.IO) {
            Console.entries.flatMap { it.cores }.distinctBy { it.id }.map { core ->
                val license = runCatching {
                    File(infoDir, "${core.id}_libretro.info").readLines()
                        .firstOrNull { it.startsWith("license") }?.substringAfter('=')?.trim()?.trim('"')
                }.getOrNull()
                Notice(core.name, license ?: "—", "https://docs.libretro.com/development/licenses/")
            }.sortedBy { it.name.lowercase() }
        }
    }
    val app = listOf(
        Notice("OpenEmux Mobile", "GPL-3.0 (APK) · MIT (Kotlin code)", FORK_SOURCE, "gpl"),
        Notice("RetroArch", "GPL-3.0", "https://github.com/libretro/RetroArch", "gpl"),
        Notice("libretro API (libretro-common)", "MIT", "https://github.com/libretro/libretro-common"),
        Notice("libretro glsl-shaders", "GPL and others, per shader", "https://github.com/libretro/glsl-shaders"),
        Notice("Kotlin, AndroidX, Jetpack Compose", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
        Notice("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        Notice("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
        Notice("OpenEmu (console icons)", "BSD-3-Clause · © 2009-2023 OpenEmu Team", "https://github.com/OpenEmu/OpenEmu", "openemu"),
        Notice("retroarch-assets (save/load state icons)", "CC-BY-4.0 · © libretro", "https://github.com/libretro/retroarch-assets"),
        Notice("Inter", "SIL OFL 1.1", "https://github.com/rsms/inter"),
        Notice("Space Grotesk", "SIL OFL 1.1", "https://github.com/floriankarsten/space-grotesk"),
        Notice("openemux-artwork (box art)", "Community box art, mirrored from libretro-thumbnails", "https://github.com/guilhermefeitosa66/openemux-artwork"),
        Notice("ScreenScraper (cartridge labels)", "Data and media © ScreenScraper.fr and contributors", "https://www.screenscraper.fr/"),
    )
    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            BackLink(stringResource(R.string.settings_about)) { a.nav.popBackStack() }
            Text(stringResource(R.string.about_licenses), style = Type.Display, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.about_licenses_note), style = Type.Small, modifier = Modifier.padding(top = 6.dp))
        }
        SectionHeader(stringResource(R.string.about_section_app))
        Card(Modifier.padding(horizontal = 20.dp)) {
            app.forEachIndexed { i, n -> NoticeRow(n, i > 0) { if (n.text != null) a.nav.navigate(Routes.license(n.text)) else n.source?.let(open) } }
        }
        SectionHeader(stringResource(R.string.about_section_cores))
        Card(Modifier.padding(horizontal = 20.dp)) {
            cores.forEachIndexed { i, n -> NoticeRow(n, i > 0) { n.source?.let(open) } }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.height(hintBarInset))
    } }
}

@Composable
private fun NoticeRow(notice: Notice, divider: Boolean, onClick: () -> Unit) {
    ListRow(notice.name, subtitle = notice.license, divider = divider, subtitleLines = 2, onClick = onClick)
}

/** A full license text bundled in assets/licenses. */
@Composable
fun LicenseTextScreen(id: String) {
    GamepadButtons(hints = listOf("B" to stringResource(R.string.settings_hint_back)))
    val a = LocalActions.current
    val context = LocalContext.current
    val file = when (id) {
        "gpl" -> "licenses/GPL-3.0.txt"
        "openemu" -> "licenses/OpenEmu-BSD.txt"
        else -> return
    }
    val text by produceState("") {
        value = withContext(Dispatchers.IO) { context.assets.open(file).bufferedReader().use { it.readText() } }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
        BackLink(stringResource(R.string.about_licenses)) { a.nav.popBackStack() }
        Text(
            text, style = Type.Small.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Oe.Text),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
    }
}
