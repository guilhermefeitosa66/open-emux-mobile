package com.openemux.mobile.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.BackupError
import com.openemux.mobile.data.BackupGroup
import com.openemux.mobile.data.BackupSize
import com.openemux.mobile.data.BackupState
import com.retroarch.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → Back up and restore (issue #545): one archive out, one archive back in; [embedded] in the settings pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(embedded: Boolean = false) {
    // In the settings pane the screen around it owns the buttons and Back.
    if (!embedded) {
        GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.settings_hint_back)))
        ReturnToSplit(LocalWindowLayout.current.splitSettings, Routes.SETTINGS, SettingsItem.Backup.key)
    }
    val a = LocalActions.current
    val backup = a.app.backup
    val context = LocalContext.current
    val state by backup.state.collectAsState()
    val sizes by backup.sizes.collectAsState()
    val roms by a.app.library.roms.collectAsState()
    var includeRoms by rememberSaveable { mutableStateOf(true) }
    val running = state as? BackupState.Running
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    fun sizeOf(group: BackupGroup) = sizes?.let { size(it[group]?.bytes ?: 0) } ?: "…"

    LaunchedEffect(Unit) {
        backup.dismiss()
        backup.measure()
    }
    // A long export must not stop because the screen went to sleep.
    val view = LocalView.current
    DisposableEffect(running != null) {
        view.keepScreenOn = running != null
        onDispose { view.keepScreenOn = false }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backup.export(uri, includeRoms)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backup.inspect(uri)
    }

    MarginFocusScroll(trailing = FocusMargin + hintBarInset) { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
            if (!embedded) BackLink(stringResource(R.string.settings_title)) { a.nav.popBackStack() }
            FitText(stringResource(R.string.settings_backup), Type.Display, Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.backup_intro),
                style = Type.Small.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionHeader(stringResource(R.string.backup_section_export))
        if (running != null) {
            Card(Modifier.padding(horizontal = 20.dp)) {
                Column(Modifier.padding(14.dp)) {
                    val fraction by animateFloatAsState(if (running.total > 0) running.done.toFloat() / running.total else 0f, label = "backup")
                    Text(stringResource(if (running.export) R.string.backup_exporting else R.string.backup_importing), style = Type.Body)
                    Text(
                        listOf(running.folder, stringResource(R.string.backup_progress, size(running.done), size(running.total))).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = Type.Small, modifier = Modifier.padding(top = 2.dp),
                    )
                    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Oe.Surface2)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(Oe.AccentBright))
                    }
                }
            }
            SecondaryButton(stringResource(R.string.backup_cancel), { backup.cancel() }, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth())
        } else {
            Card(Modifier.padding(horizontal = 20.dp)) {
                ListRow(stringResource(R.string.backup_saves), subtitle = stringResource(R.string.backup_saves_desc), value = sizeOf(BackupGroup.SAVES), minHeight = 60.dp)
                ListRow(stringResource(R.string.backup_settings), subtitle = stringResource(R.string.backup_settings_desc), value = sizeOf(BackupGroup.SETTINGS), minHeight = 60.dp, divider = true)
                SwitchRow(
                    stringResource(R.string.backup_include_roms), countString(R.plurals.backup_roms_count, roms.size, roms.size, sizeOf(BackupGroup.ROMS)),
                    includeRoms, { includeRoms = it }, divider = true,
                )
            }
            val total = sizes?.let { s -> size(s.filterKeys { includeRoms || it != BackupGroup.ROMS }.values.fold(BackupSize()) { x, y -> x + y }.bytes) } ?: "…"
            PrimaryButton(
                stringResource(R.string.backup_export, total),
                {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    create.launch(context.getString(R.string.backup_file_name, day) + ".zip")
                },
                Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth(), icon = Icons.Download, height = 54.dp, enabled = sizes != null,
            )
        }
        (state as? BackupState.Exported)?.let { Notice(stringResource(R.string.backup_exported, it.name, size(it.bytes)), NoticeKind.OK) }
        (state as? BackupState.Failed)?.takeIf { it.export }?.let { Notice(it.error.message(true, ::size), it.error.kind()) }
        Note(stringResource(if (includeRoms) R.string.backup_export_note else R.string.backup_export_note_no_roms))

        SectionHeader(stringResource(R.string.backup_section_import))
        SecondaryButton(
            stringResource(R.string.backup_import), { if (running == null) open.launch(arrayOf("application/zip", "application/octet-stream")) },
            Modifier.padding(horizontal = 20.dp).fillMaxWidth(), icon = Icons.Load, height = 54.dp,
        )
        (state as? BackupState.Restored)?.let { Notice(countString(R.plurals.backup_restored, it.games, it.games, it.saves), NoticeKind.OK) }
        (state as? BackupState.Failed)?.takeIf { !it.export }?.let { Notice(it.error.message(false, ::size), it.error.kind()) }
        Note(stringResource(R.string.backup_import_note))
        Note(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Oe.Text)) { append(stringResource(R.string.backup_keep_data_lead)) }
                append(" ")
                append(stringResource(R.string.backup_keep_data))
            },
        )
        Spacer(Modifier.height(28.dp))
        Spacer(Modifier.height(hintBarInset))
    } }

    (state as? BackupState.Confirm)?.let { confirm ->
        val m = confirm.manifest
        ModalBottomSheet({ backup.dismiss() }, containerColor = Oe.Sheet) {
            SheetBarIcons()
            MarginFocusScroll { Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(stringResource(R.string.backup_confirm_title), style = Type.Title.copy(fontSize = 22.sp))
                val day = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(m.created))
                Text(
                    stringResource(R.string.backup_confirm_from, m.app, day, size(confirm.bytes)),
                    style = Type.Small, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                Card {
                    var first = true
                    m.groups[BackupGroup.ROMS]?.let {
                        ListRow(countString(R.plurals.backup_games, m.roms, m.roms), value = size(it.bytes))
                        first = false
                    }
                    m.groups[BackupGroup.SAVES]?.let {
                        ListRow(stringResource(R.string.backup_saves), value = size(it.bytes), divider = !first)
                        first = false
                    }
                    m.groups[BackupGroup.SETTINGS]?.let {
                        ListRow(stringResource(R.string.backup_settings), value = size(it.bytes), divider = !first)
                    }
                }
                Text(stringResource(R.string.backup_confirm_note), style = Type.Small, modifier = Modifier.padding(top = 14.dp, bottom = 16.dp))
                PrimaryButton(stringResource(R.string.backup_confirm), { backup.restore(confirm) }, Modifier.fillMaxWidth(), height = 54.dp)
                SecondaryButton(stringResource(R.string.backup_cancel), { backup.dismiss() }, Modifier.padding(top = 10.dp).fillMaxWidth(), height = 54.dp)
            } }
        }
    }
}

private enum class NoticeKind { OK, WARN, DANGER }

private fun BackupError.kind() = if (this is BackupError.NoSpace) NoticeKind.WARN else NoticeKind.DANGER

@Composable
private fun BackupError.message(export: Boolean, size: (Long) -> String): String = when (this) {
    is BackupError.NotABackup -> stringResource(R.string.backup_err_invalid)
    is BackupError.TooNew -> stringResource(R.string.backup_err_newer)
    is BackupError.NoSpace -> stringResource(R.string.backup_err_space, size(needed), size(free))
    else -> stringResource(if (export) R.string.backup_err_write else R.string.backup_err_read, message.orEmpty())
}

@Composable
private fun Notice(text: String, kind: NoticeKind) {
    val (bg, fg: Color) = when (kind) {
        NoticeKind.OK -> Oe.OkBg to Oe.OkText
        NoticeKind.WARN -> Oe.WarnBg to Oe.WarnText
        NoticeKind.DANGER -> Oe.DangerBg to Oe.Danger
    }
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(bg).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = if (kind == NoticeKind.OK) LiveRegionMode.Polite else LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (kind == NoticeKind.OK) Icons.Check else Icons.Alert, fg, 20.dp, stroke = 2.4f)
        Text(text, style = Type.Body.copy(fontSize = 14.sp, color = fg))
    }
}

@Composable
private fun Note(text: String) = Note(buildAnnotatedString { append(text) })

@Composable
private fun Note(text: androidx.compose.ui.text.AnnotatedString) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Info, Oe.Muted, 18.dp)
        Text(text, style = Type.Small)
    }
}
