package com.openemux.mobile.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.navigation.NavBackStackEntry
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.AppLocale
import com.openemux.mobile.data.AchievementsState
import com.openemux.mobile.data.CART_COLORS
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.ImportResult
import com.openemux.mobile.data.KNOWN_BIOS
import com.openemux.mobile.data.LoginResult
import com.openemux.mobile.data.RaLogin
import com.openemux.mobile.data.SHADERS
import com.openemux.mobile.data.shaderById
import com.retroarch.BuildConfig
import com.retroarch.R
import kotlinx.coroutines.launch

@Composable
internal fun SectionHeader(text: String, trailing: String? = null) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text.uppercase(), style = Type.SectionCaps, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Type.Caption.copy(color = Oe.Faint))
    }
}

private val THEMES = listOf(
    Triple(ThemePref.SYSTEM, R.string.settings_theme_system, R.string.settings_theme_system_desc),
    Triple(ThemePref.LIGHT, R.string.settings_theme_light, R.string.settings_theme_light_desc),
    Triple(ThemePref.DARK, R.string.settings_theme_dark, R.string.settings_theme_dark_desc),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(entry: NavBackStackEntry? = null) {
    // Saveable: a density change recreates the activity, and an open sheet must still be there.
    var themeSheet by rememberSaveable { mutableStateOf(false) }
    var languageSheet by rememberSaveable { mutableStateOf(false) }
    GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_open), "B" to stringResource(R.string.settings_hint_back), "L1 R1" to stringResource(R.string.settings_hint_tabs)))
    val a = LocalActions.current
    val layout = LocalWindowLayout.current
    val split = layout.splitSettings
    var pickedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val picked = SettingsItem.of(pickedKey)
    // On a phone each item is a screen of its own, or a sheet.
    fun openAlone(item: SettingsItem) {
        when (item) {
            SettingsItem.Theme -> themeSheet = true
            SettingsItem.Language -> languageSheet = true
            else -> item.route?.let { a.nav.navigate(it) }
        }
    }
    // A screen of its own that a resize turned back into a pane hands its item over.
    LaunchedEffect(entry) { entry?.savedStateHandle?.remove<String>(PICKED)?.let { pickedKey = it } }
    OnSplitChange(split) { change ->
        if (change == SplitChange.Parted) picked?.let {
            pickedKey = null
            // A license text sits on About on a phone too, so Back steps there first as it does in the pane.
            if (it is SettingsItem.License) openAlone(SettingsItem.About)
            openAlone(it)
        } else sheetToPane(themeSheet, languageSheet)?.let {
            // The phone's sheet becomes the pane's item; the screens of their own hand theirs back by themselves.
            themeSheet = false
            languageSheet = false
            pickedKey = it.key
        }
    }

    if (split) {
        ListDetailScaffold(
            listWidth = layout.settingsListDp.dp, detailWidth = null,
            // A license text steps back to About first, as its back link does.
            selected = picked != null, onClear = { pickedKey = if (picked is SettingsItem.License) SettingsItem.About.key else null },
            list = { SettingsList(picked) { pickedKey = it.key } },
            detail = { SettingsPane(picked) { pickedKey = it.key } },
        )
    } else {
        SettingsList(null, ::openAlone)
    }

    if (themeSheet) {
        ModalBottomSheet({ themeSheet = false }, containerColor = Oe.Sheet) {
            ImmersiveWindow()
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_theme), style = Type.Title.copy(fontSize = 22.sp), modifier = Modifier.padding(bottom = 6.dp))
                ThemeOptions()
            }
        }
    }
    if (languageSheet) {
        ModalBottomSheet({ languageSheet = false }, containerColor = Oe.Sheet) {
            ImmersiveWindow()
            MarginFocusScroll { Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.settings_language), style = Type.Title.copy(fontSize = 22.sp), modifier = Modifier.padding(bottom = 6.dp))
                LanguageOptions { languageSheet = false }
            } }
        }
    }
}

/** The settings themselves; [picked] is the item shown beside the list, null on a phone. */
@Composable
private fun SettingsList(picked: SettingsItem?, onPick: (SettingsItem) -> Unit) {
    val a = LocalActions.current
    val lib = a.app.library
    val split = LocalWindowLayout.current.splitSettings
    // Recomposes the console rows when a default changes.
    val meta by lib.meta.collectAsState()
    val roms by lib.roms.collectAsState()
    val context = LocalContext.current
    // Only the consoles in the library: the others do not need their BIOS yet.
    val owned = roms.map { it.console }.toSet()
    val missing = KNOWN_BIOS.filter { it.console in owned && !a.app.importer.biosPresent(it) }
    val missingOptional = missing.count { !it.required }
    val missingRequired = missing.filter { it.required }.map { it.console }.distinct().size

    MarginFocusScroll(trailing = FocusMargin + TabBarHeight + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().holdFocusAtEnd(FocusDirection.Down).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.settings_title), style = Type.Display, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))

        val biosPicked = picked == SettingsItem.Bios
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().paneOpener(biosPicked).gamepadFocus(corner = 20.dp).clip(RoundedCornerShape(20.dp))
                .background(if (biosPicked) Oe.AccentTint else Oe.Surface)
                .border(1.dp, Oe.Border, RoundedCornerShape(20.dp)).semantics { selected = biosPicked }
                .clickable(role = Role.Button) { onPick(SettingsItem.Bios) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val ok = missingOptional == 0
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (ok) Oe.OkBg else Oe.WarnBg),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Chip, if (ok) Oe.Ok else Oe.Warn, 24.dp) }
            Column(Modifier.weight(1f)) {
                Text("BIOS", style = Type.Section.copy(fontSize = 16.sp))
                Text(
                    when {
                        missingRequired > 0 -> countString(R.plurals.settings_bios_consoles_missing, missingRequired, missingRequired)
                        missingOptional > 0 -> countString(R.plurals.settings_bios_optional_missing, missingOptional, missingOptional)
                        else -> stringResource(R.string.settings_bios_all_ready)
                    },
                    style = Type.Small, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Oe.Accent).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.settings_import), style = Type.Button.copy(fontSize = 13.sp))
            }
        }

        SectionHeader(stringResource(R.string.settings_section_appearance))
        Card(Modifier.padding(horizontal = 20.dp)) {
            ListRow(
                stringResource(R.string.settings_theme), value = stringResource(THEMES.first { it.first == ThemePref.value }.second),
                selected = picked == SettingsItem.Theme, onClick = { onPick(SettingsItem.Theme) },
            )
            val language = AppLocale.current(context)
            ListRow(
                stringResource(R.string.settings_language), divider = true, selected = picked == SettingsItem.Language, onClick = { onPick(SettingsItem.Language) },
                value = AppLocale.LANGUAGES.firstOrNull { it.first == language }?.let { "${it.third} ${it.second}" } ?: stringResource(R.string.settings_theme_system),
            )
        }

        SectionHeader(stringResource(R.string.settings_section_achievements))
        Card(Modifier.padding(horizontal = 20.dp)) {
            val ra by a.app.achievements.state.collectAsState()
            ListRow(
                stringResource(R.string.ra_title),
                subtitle = when {
                    !ra.signedIn -> stringResource(R.string.ra_not_signed_in)
                    ra.enabled -> stringResource(R.string.ra_signed_in_as, ra.username)
                    else -> stringResource(R.string.ra_signed_in_tracking_off, ra.username)
                },
                minHeight = 60.dp,
                leading = {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Oe.AccentTint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Trophy, Oe.AccentText, 22.dp)
                    }
                },
                selected = picked == SettingsItem.Achievements, onClick = { onPick(SettingsItem.Achievements) },
            )
        }

        SectionHeader(stringResource(R.string.settings_section_consoles), stringResource(R.string.settings_section_consoles_trailing))
        Card(Modifier.padding(horizontal = 20.dp)) {
            lib.orderedConsoles(meta.consoleOrder).forEachIndexed { i, c ->
                val s = lib.consoleSettings(c)
                ListRow(
                    c.fullName,
                    subtitle = "${c.core(s.core).name} · ${shaderById(s.shader).name()}",
                    divider = i > 0, minHeight = 60.dp,
                    leading = { ConsoleIcon(c) },
                    selected = picked == SettingsItem.Console(c.id), onClick = { onPick(SettingsItem.Console(c.id)) },
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_section_general))
        Card(Modifier.padding(horizontal = 20.dp)) {
            ListRow(stringResource(R.string.settings_rom_folder), subtitle = "Android/data/${context.packageName}/files/roms", value = "${roms.size}")
            ListRow(stringResource(R.string.settings_controls), value = stringResource(R.string.settings_controls_value), divider = true)
            ListRow(
                stringResource(R.string.settings_backup), subtitle = stringResource(R.string.settings_backup_desc), divider = true, minHeight = 60.dp,
                selected = picked == SettingsItem.Backup, onClick = { onPick(SettingsItem.Backup) },
            )
            val updates = a.app.updates
            val checkUpdates by updates.enabled.collectAsState()
            SwitchRow(stringResource(R.string.settings_update_check), stringResource(R.string.settings_update_check_desc), checkUpdates, { updates.setEnabled(it) }, divider = true)
            ListRow(
                stringResource(if (split) R.string.settings_about_licenses else R.string.settings_about), value = "OpenEmux ${BuildConfig.VERSION_NAME}", divider = true,
                selected = picked == SettingsItem.About || picked is SettingsItem.License, onClick = { onPick(SettingsItem.About) },
            )
        }
        Spacer(BottomSpace)
    } }
}

/** The picked item beside the settings list, or a hint while nothing is picked. */
@Composable
private fun SettingsPane(picked: SettingsItem?, onPick: (SettingsItem) -> Unit) {
    if (picked == null) {
        PanePlaceholder(Icons.Sliders, stringResource(R.string.settings_pick_hint), Modifier.padding(bottom = TabBarHeight))
        return
    }
    // Each item starts at its top, with a scroll of its own.
    key(picked.key) {
        // Down past the item's last row stays there instead of falling onto the tab bar, as in the list.
        Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = TabBarHeight).holdFocusAtEnd(FocusDirection.Down)) {
            Box(Modifier.widthIn(max = 680.dp)) {
                when (picked) {
                    is SettingsItem.Console -> ConsoleSettingsScreen(picked.id, embedded = true, onOpenBios = { onPick(SettingsItem.Bios) })
                    SettingsItem.Bios -> BiosScreen(embedded = true)
                    SettingsItem.Theme -> ThemePane()
                    SettingsItem.Language -> LanguagePane()
                    SettingsItem.Achievements -> AchievementsScreen(embedded = true)
                    SettingsItem.Backup -> BackupScreen(embedded = true)
                    SettingsItem.About -> AboutScreen(embedded = true, onOpenLicense = { onPick(SettingsItem.License(it)) })
                    is SettingsItem.License -> LicenseTextScreen(picked.id, embedded = true, onBack = { onPick(SettingsItem.About) })
                }
            }
        }
    }
}

/** A pane's title and the line that explains it. */
@Composable
private fun PaneTitle(title: String, lead: String) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 18.dp)) {
        Text(title, style = Type.Display.copy(fontSize = 30.sp, lineHeight = 34.sp))
        Text(lead, style = Type.Small.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(top = 8.dp))
    }
}

/** The theme choices as the sheet lists them. */
@Composable
private fun ThemeOptions() {
    val context = LocalContext.current
    THEMES.forEach { (id, name, blurb) ->
        RadioCard(ThemePref.value == id, stringResource(name), stringResource(blurb), onClick = { ThemePref.set(context, id) }, background = Oe.Raised)
    }
}

/** The language choices as the sheet lists them; [onPicked] runs before a new language restarts the screen. */
@Composable
private fun LanguageOptions(background: androidx.compose.ui.graphics.Color = Oe.Raised, onPicked: () -> Unit) {
    val context = LocalContext.current
    val language = AppLocale.current(context)
    (listOf(Triple("", stringResource(R.string.settings_theme_system), "")) + AppLocale.LANGUAGES).forEach { (tag, name, flag) ->
        RadioCard(
            language == tag, name, if (tag.isEmpty()) stringResource(R.string.settings_language_system_desc) else "",
            onClick = { onPicked(); if (tag != language) AppLocale.set(context as Activity, tag) },
            background = background,
            leading = {
                // The system choice has no flag: a globe of the same size keeps the column aligned.
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (flag.isEmpty()) Icon(Icons.Globe, Oe.Muted, 22.dp, stroke = 1.6f)
                    else Text(flag, fontSize = 22.sp)
                }
            },
        )
    }
}

/** Theme in the settings pane: three cards side by side, applied at once. */
@Composable
private fun ThemePane() {
    val context = LocalContext.current
    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PaneTitle(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_lead))
        Row(Modifier.padding(horizontal = 20.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            THEMES.forEach { (id, name, blurb) ->
                ThemeCard(id, stringResource(name), stringResource(blurb), ThemePref.value == id, Modifier.weight(1f).fillMaxHeight()) { ThemePref.set(context, id) }
            }
        }
        Spacer(Modifier.height(hintBarInset))
    } }
}

/** One theme: a small picture of it, the radio dot, its name and what it does. */
@Composable
private fun ThemeCard(id: String, name: String, blurb: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.gamepadFocus(corner = 16.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Oe.AccentTint else Oe.Surface)
            .border(1.5.dp, if (selected) Oe.AccentBright else Oe.Border, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val dark = androidx.compose.ui.graphics.Color(0xFF121216)
        val light = androidx.compose.ui.graphics.Color(0xFFF4F3F8)
        Row(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Oe.Border, RoundedCornerShape(10.dp))) {
            val halves = when (id) { ThemePref.LIGHT -> listOf(light); ThemePref.DARK -> listOf(dark); else -> listOf(light, dark) }
            halves.forEach { back ->
                Box(Modifier.weight(1f).fillMaxHeight().background(back).padding(10.dp)) {
                    Box(Modifier.size(width = 34.dp, height = 22.dp).clip(RoundedCornerShape(5.dp)).background(Oe.Accent))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) Oe.AccentBright else Oe.Chevron, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(Oe.AccentBright)) }
            Text(name, style = Type.Section)
        }
        Text(blurb, style = Type.Small)
    }
}

/** Language in the settings pane: the sheet's list in a card. */
@Composable
private fun LanguagePane() {
    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PaneTitle(stringResource(R.string.settings_language), stringResource(R.string.settings_language_lead))
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LanguageOptions(background = Oe.Surface) {}
        }
        Spacer(Modifier.height(hintBarInset))
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSettingsScreen(id: String, embedded: Boolean = false, onOpenBios: (() -> Unit)? = null) {
    // In the settings pane the screen around it owns the buttons and Back.
    if (!embedded) {
        GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.settings_hint_back)))
        ReturnToSplit(LocalWindowLayout.current.splitSettings, Routes.SETTINGS, SettingsItem.Console(id).key)
    }
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

    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
            if (!embedded) BackLink(stringResource(R.string.settings_title)) { a.nav.popBackStack() }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_console_defaults), style = Type.Eyebrow)
                    Text(console.displayName, style = Type.Display.copy(fontSize = 28.sp, lineHeight = 30.sp), modifier = Modifier.padding(top = 4.dp))
                    if (embedded) Text(stringResource(R.string.settings_console_defaults_lead), style = Type.Small.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(top = 8.dp))
                }
                Box(Modifier.width(92.dp).rotate(6f)) {
                    Cartridge(
                        console, sample?.let { labelFor(it) }, sample?.title ?: console.displayName,
                        settings.color, Modifier.fillMaxWidth(), cover = sample?.let { a.app.covers.urlFor(it) },
                    )
                }
            }
        }

        Text(stringResource(R.string.settings_default_core), style = Type.Section, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            console.cores.forEach { c ->
                RadioCard(
                    settings.core == c.id, c.name, stringResource(c.description),
                    onClick = { lib.updateConsole(console) { it.copy(core = c.id) } },
                    badge = if (c.recommended) stringResource(R.string.settings_recommended) else null,
                )
            }
        }

        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.settings_default_shader), style = Type.Section, modifier = Modifier.weight(1f))
            Text(shaderById(settings.shader).name(), style = Type.Small)
        }
        FocusRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SHADERS.forEach { s ->
                ShaderTile(s.id, s.name(), settings.shader == s.id) { lib.updateConsole(console) { it.copy(shader = s.id) } }
            }
        }

        Card(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
            val bios = KNOWN_BIOS.filter { it.console == console }.sortedByDescending { it.required }.firstOrNull()
            if (bios != null) {
                val present = a.app.importer.biosPresent(bios)
                ListRow(
                    "BIOS", subtitle = "${bios.fileName()} · ${stringResource(if (bios.required) R.string.settings_bios_required else R.string.settings_bios_optional)}",
                    onClick = { onOpenBios?.invoke() ?: a.nav.navigate(Routes.BIOS) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(if (present) Icons.Check else Icons.Alert, if (present) Oe.Ok else Oe.Muted, 16.dp, stroke = 2.4f)
                            Text(stringResource(if (present) R.string.settings_bios_found else R.string.settings_bios_missing), style = Type.Section.copy(fontSize = 13.sp, color = if (present) Oe.Ok else Oe.Muted))
                        }
                    },
                )
            } else {
                ListRow("BIOS", subtitle = stringResource(R.string.settings_bios_not_needed), value = "—")
            }
            ListRow(stringResource(R.string.settings_default_skin), value = "OpenEmux ${console.short}", divider = true)
            if (console.frame != null) ListRow(
                stringResource(R.string.settings_cart_color), value = stringResource(CART_COLORS.first { it.id == settings.color }.label), divider = true,
                onClick = { colorSheet = true },
                trailing = { Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(CART_COLORS.first { it.id == settings.color }.swatch)) },
            )
        }

        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Info, Oe.Muted, 18.dp)
            val text = if (own.isEmpty()) countString(R.plurals.settings_applies_all, count, count, console.short)
            else countString(R.plurals.settings_applies_except, own.size, count, own.size)
            Column {
                Text(text, style = Type.Small)
                if (own.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_see_which), style = Type.Small.copy(color = Oe.AccentText),
                        modifier = Modifier.padding(top = 2.dp).gamepadFocus(corner = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { ownSheet = true },
                    )
                }
            }
        }
        Spacer(Modifier.height(hintBarInset))
    } }

    if (colorSheet) {
        ModalBottomSheet({ colorSheet = false }, containerColor = Oe.Sheet) {
            ImmersiveWindow()
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(stringResource(R.string.settings_cart_color), style = Type.Title.copy(fontSize = 22.sp))
                Text(stringResource(R.string.settings_cart_color_desc, console.short), style = Type.Small, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                if (sample != null) {
                    Cartridge(console, labelFor(sample), sample.title, settings.color, Modifier.align(Alignment.CenterHorizontally).width(220.dp), cover = a.app.covers.urlFor(sample))
                    Spacer(Modifier.height(16.dp))
                }
                ColorSwatches(settings.color) { c -> lib.updateConsole(console) { it.copy(color = c) } }
            }
        }
    }
    if (ownSheet) {
        ModalBottomSheet({ ownSheet = false }, containerColor = Oe.Sheet) {
            ImmersiveWindow()
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(stringResource(R.string.settings_own_core_title), style = Type.Title.copy(fontSize = 22.sp))
                Spacer(Modifier.height(12.dp))
                own.forEach { rom ->
                    ListRow(rom.title, value = lib.effectiveCore(rom).name, onClick = { ownSheet = false; a.nav.navigate(Routes.rom(rom.key)) })
                }
            }
        }
    }
}

@Composable
fun BiosScreen(embedded: Boolean = false) {
    if (!embedded) {
        GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_import), "B" to stringResource(R.string.settings_hint_back)))
        ReturnToSplit(LocalWindowLayout.current.splitSettings, Routes.SETTINGS, SettingsItem.Bios.key)
    }
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

    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
            if (!embedded) BackLink(stringResource(R.string.settings_title)) { a.nav.popBackStack() }
            Text("BIOS", style = Type.Display, modifier = Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.settings_bios_intro),
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
                    Text("  " + stringResource(R.string.settings_bios_checking), style = Type.Body)
                }
            } else {
                PrimaryButton(stringResource(R.string.settings_bios_import_from_device), { picker.launch(null) }, Modifier.fillMaxWidth(), icon = Icons.Folder, height = 54.dp)
            }
            Text(
                stringResource(R.string.settings_bios_pick_help),
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
                    if (ok) countString(R.plurals.settings_bios_imported, r.imported, r.imported) +
                        if (r.skipped > 0) " " + countString(R.plurals.settings_bios_skipped, r.skipped, r.skipped) else ""
                    else countString(R.plurals.settings_bios_none_found, r.skipped, r.skipped),
                    style = Type.Body.copy(fontSize = 14.sp, color = if (ok) Oe.OkText else Oe.WarnText),
                )
            }
        }

        // Like the desktop's BIOS page: every console the catalog names, each file with its state.
        val consoles = remember(refresh) {
            Console.entries.mapNotNull { c ->
                val defs = KNOWN_BIOS.filter { it.console == c }.sortedByDescending { it.required }
                if (defs.isEmpty()) null else c to defs.map { it to a.app.importer.biosPresent(it) }
            }
        }
        val ready = consoles.count { (_, defs) -> defs.none { (d, present) -> d.required && !present } }
        SectionHeader(stringResource(R.string.settings_bios_consoles), stringResource(R.string.settings_bios_ready_count, ready, consoles.size))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            consoles.forEach { (c, defs) ->
                Card {
                    ListRow(
                        c.fullName, minHeight = 60.dp, leading = { ConsoleIcon(c) },
                        value = stringResource(R.string.settings_count_of, defs.count { it.second }, defs.size),
                    )
                    defs.forEach { (def, present) ->
                        ListRow(
                            if (def.files.size > 1) stringResource(R.string.settings_bios_one_of) else def.files.first(),
                            subtitle = if (def.files.size > 1) def.files.joinToString(" · ") else null,
                            divider = true, minHeight = 52.dp, subtitleLines = 4,
                            leading = {
                                Icon(if (present) Icons.Check else Icons.Alert, if (present) Oe.Ok else if (def.required) Oe.Warn else Oe.Muted, 18.dp, stroke = 2.4f)
                            },
                            trailing = {
                                if (def.required) Tag(stringResource(R.string.settings_bios_tag_required), Oe.WarnBg, Oe.WarnText)
                                else Tag(stringResource(R.string.settings_bios_tag_optional), Oe.Surface2, Oe.Muted)
                            },
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_bios_footer),
            style = Type.Small.copy(color = Oe.Faint), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        )
        Spacer(Modifier.height(hintBarInset))
    } }
}

@Composable
fun AchievementsScreen(embedded: Boolean = false) {
    if (!embedded) {
        GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.settings_hint_back)))
        ReturnToSplit(LocalWindowLayout.current.splitSettings, Routes.SETTINGS, SettingsItem.Achievements.key)
    }
    val a = LocalActions.current
    val store = a.app.achievements
    val state by store.state.collectAsState()

    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
            if (!embedded) BackLink(stringResource(R.string.settings_title)) { a.nav.popBackStack() }
            FitText(stringResource(R.string.ra_title), Type.Display, Modifier.padding(top = 4.dp))
        }
        if (state.signedIn) AchievementsAccount(state) else AchievementsSignIn(state.lastUsername)
        Spacer(Modifier.height(28.dp))
        Spacer(Modifier.height(hintBarInset))
    } }
}

@Composable
private fun AchievementsSignIn(lastUsername: String) {
    val a = LocalActions.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(lastUsername) }
    // Not saveable: the password must not reach the saved-instance bundle.
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        if (busy) return
        val attempt = password
        busy = true
        error = null
        scope.launch {
            when (val r = RaLogin.login(username, attempt)) {
                is LoginResult.Ok -> if (!a.app.achievements.setAccount(r.username, r.token)) error = context.getString(R.string.ra_error_unreachable)
                is LoginResult.Refused -> error = r.message ?: context.getString(R.string.ra_error_unreachable)
                LoginResult.Unreachable -> error = context.getString(R.string.ra_error_unreachable)
                LoginResult.Missing -> error = context.getString(R.string.ra_error_missing)
            }
            password = ""
            busy = false
        }
    }

    Text(
        stringResource(R.string.ra_intro),
        style = Type.Small.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OeTextField(
            stringResource(R.string.ra_username), username, { username = it },
            isError = error != null, readOnly = busy, contentType = ContentType.Username,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        )
        OeTextField(
            stringResource(R.string.ra_password), password, { password = it },
            isError = error != null, readOnly = busy, password = true, revealed = revealed,
            onReveal = { revealed = !revealed }, revealLabel = stringResource(if (revealed) R.string.ra_hide_password else R.string.ra_show_password),
            contentType = ContentType.Password,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { signIn() }),
        )
        error?.let { message ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Oe.DangerBg).padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Alert, Oe.Danger, 20.dp, stroke = 2.4f)
                Text(message, style = Type.Body.copy(fontSize = 14.sp, color = Oe.Danger))
            }
        }
        PrimaryButton(
            stringResource(if (busy) R.string.ra_signing_in else R.string.ra_sign_in), { signIn() },
            Modifier.fillMaxWidth(), height = 54.dp, enabled = !busy,
        )
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
        Text(stringResource(R.string.ra_no_account), style = Type.Small)
        Text(
            stringResource(R.string.ra_create_account), style = Type.Small.copy(color = Oe.AccentText),
            modifier = Modifier.padding(top = 2.dp).gamepadFocus(corner = 6.dp).clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button) { uri.openUri("https://retroachievements.org/createaccount.php") },
        )
    }
}

@Composable
private fun AchievementsAccount(state: AchievementsState) {
    val store = LocalActions.current.app.achievements
    val uri = LocalUriHandler.current
    Card(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Oe.AccentSoft), contentAlignment = Alignment.Center) {
                Text(state.username.take(1).uppercase(), style = Type.Section.copy(color = Oe.AccentOnSoft, fontSize = 18.sp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ra_signed_in), style = Type.Small)
                Text(state.username, style = Type.Section.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.height(36.dp).gamepadFocus(corner = 18.dp).clip(RoundedCornerShape(18.dp)).background(Oe.Raised)
                    .clickable(role = Role.Button) { store.signOut() }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.ra_sign_out), style = Type.Button.copy(color = Oe.Text, fontSize = 14.sp)) }
        }
    }

    SectionHeader(stringResource(R.string.ra_while_playing))
    Card(Modifier.padding(horizontal = 20.dp)) {
        SwitchRow(stringResource(R.string.ra_track), stringResource(R.string.ra_track_desc), state.enabled, { store.setEnabled(it) })
        SwitchRow(
            stringResource(R.string.ra_hardcore), stringResource(R.string.ra_hardcore_desc), state.hardcore, { store.setHardcore(it) },
            enabled = state.enabled, divider = true,
        )
    }

    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Info, Oe.Muted, 18.dp)
        Column {
            Text(stringResource(R.string.ra_apply_note), style = Type.Small)
            Text(
                stringResource(R.string.ra_see_progress), style = Type.Small.copy(color = Oe.AccentText),
                modifier = Modifier.padding(top = 6.dp).gamepadFocus(corner = 6.dp).clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { uri.openUri("https://retroachievements.org/user/" + Uri.encode(state.username)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen() {
    GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.settings_hint_back), "L1 R1" to stringResource(R.string.settings_hint_tabs)))
    val a = LocalActions.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var working by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Extensions several consoles take, still to be answered, and the import waiting on them.
    var asking by remember { mutableStateOf<List<Pair<String, List<Console>>>>(emptyList()) }
    var choices by remember { mutableStateOf<Map<String, Console>>(emptyMap()) }
    var pendingImport by remember { mutableStateOf<(suspend (Map<String, Console>) -> ImportResult)?>(null) }

    fun finish(block: suspend (Map<String, Console>) -> ImportResult, picked: Map<String, Console>) {
        scope.launch {
            result = runCatching { block(picked) }.getOrDefault(ImportResult(0, 0))
            a.app.library.scan()
            working = false
        }
    }
    fun run(ambiguities: suspend () -> Map<String, List<Console>>, block: suspend (Map<String, Console>) -> ImportResult) {
        working = true
        scope.launch {
            val open = runCatching { ambiguities() }.getOrDefault(emptyMap())
            if (open.isEmpty()) finish(block, emptyMap())
            else { choices = emptyMap(); pendingImport = block; asking = open.toList() }
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run({ a.app.importer.ambiguities(uris) }) { a.app.importer.importRoms(uris, it) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run({ a.app.importer.ambiguitiesInTree(uri) }) { a.app.importer.importRomTree(uri, it) }
    }

    asking.firstOrNull()?.let { (ext, candidates) ->
        ModalBottomSheet({ asking = emptyList(); pendingImport = null; working = false }, containerColor = Oe.Sheet) {
            ImmersiveWindow()
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(stringResource(R.string.settings_import_ext_title, ext), style = Type.Title.copy(fontSize = 22.sp))
                Text(
                    stringResource(R.string.settings_import_ext_question, ext),
                    style = Type.Small, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Card {
                    candidates.forEachIndexed { i, c ->
                        ListRow(
                            c.fullName, divider = i > 0, minHeight = 56.dp, leading = { ConsoleIcon(c) },
                            onClick = {
                                choices = choices + (ext to c)
                                asking = asking.drop(1)
                                if (asking.isEmpty()) pendingImport?.let { finish(it, choices) }
                            },
                        )
                    }
                }
            }
        }
    }

    MarginFocusScroll(trailing = FocusMargin + TabBarHeight + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().holdFocusAtEnd(FocusDirection.Down).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.settings_import), style = Type.Display, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
        Text(
            stringResource(R.string.settings_import_intro),
            style = Type.Small.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (working) {
                Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Oe.AccentText, strokeWidth = 2.dp)
                    Text("  " + stringResource(R.string.settings_import_copying), style = Type.Body)
                }
            } else {
                PrimaryButton(stringResource(R.string.settings_import_pick_files), { files.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), icon = Icons.File, height = 54.dp)
                SecondaryButton(stringResource(R.string.settings_import_pick_folder), { folder.launch(null) }, Modifier.fillMaxWidth(), icon = Icons.Folder, height = 54.dp)
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
                    if (ok) countString(R.plurals.settings_import_games, r.imported, r.imported) +
                        if (r.skipped > 0) " " + countString(R.plurals.settings_import_skipped, r.skipped, r.skipped) else ""
                    else stringResource(R.string.settings_import_none),
                    style = Type.Body.copy(fontSize = 14.sp, color = if (ok) Oe.OkText else Oe.WarnText),
                    modifier = Modifier.weight(1f),
                )
                if (ok) Text(
                    stringResource(R.string.settings_import_view), style = Type.Section.copy(color = Oe.OkText),
                    modifier = Modifier.gamepadFocus(corner = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { a.nav.navigate(Routes.SHELF) { popUpTo(Routes.SHELF) { inclusive = true } } }.padding(6.dp),
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_import_formats))
        Card(Modifier.padding(horizontal = 20.dp)) {
            Console.entries.forEachIndexed { i, c ->
                ListRow(
                    c.fullName, subtitle = c.extensions.joinToString("  ") { ".$it" } + "  .zip",
                    divider = i > 0, minHeight = 60.dp, leading = { ConsoleIcon(c) },
                )
            }
        }
        SectionHeader(stringResource(R.string.settings_import_where))
        Text(
            stringResource(R.string.settings_import_where_desc, context.packageName),
            style = Type.Small.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(BottomSpace)
    } }
}
