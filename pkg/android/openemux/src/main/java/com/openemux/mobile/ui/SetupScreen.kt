package com.openemux.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.Setup
import com.retroarch.R
import java.util.Locale

/** The first-run download; the app opens only after it. */
@Composable
fun SetupScreen(state: Setup.State, onStart: () -> Unit) {
    GamepadButtons(hints = listOf("A" to stringResource(if (state is Setup.State.Failed) R.string.settings_setup_retry else R.string.settings_setup_start)))
    val layout = LocalWindowLayout.current
    if (layout.nav != NavKind.TabletRail) {
        Column(
            Modifier.fillMaxSize().background(Oe.Bg).statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            SetupIntro(wide = false)
            SetupProgress(state, onStart)
        }
        return
    }
    // A tablet: the explanation beside the progress when wide, otherwise one capped column; centred on the screen either way.
    BoxWithConstraints(Modifier.fillMaxSize().background(Oe.Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 40.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (setupColumns(layout) == 2) {
                Row(Modifier.widthIn(max = 1040.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SetupIntro(wide = true) }
                    Column(Modifier.weight(1f)) { SetupProgress(state, onStart) }
                }
            } else {
                Column(Modifier.readingWidth()) {
                    SetupIntro(wide = false)
                    SetupProgress(state, onStart)
                }
            }
        }
    }
}

/** The logo, the title and what is about to happen. */
@Composable
private fun SetupIntro(wide: Boolean) {
    Image(painterResource(R.drawable.oe_logo), contentDescription = null, modifier = Modifier.height(56.dp))
    Text(
        stringResource(R.string.settings_setup_title),
        style = if (wide) Type.Display.copy(fontSize = 40.sp, lineHeight = 44.sp) else Type.Display.copy(fontSize = 28.sp, lineHeight = 32.sp),
        modifier = Modifier.padding(top = 24.dp),
    )
    Text(
        stringResource(R.string.settings_setup_intro),
        style = if (wide) Type.Small.copy(fontSize = 17.sp, lineHeight = 24.sp) else Type.Small.copy(fontSize = 15.sp, lineHeight = 21.sp),
        modifier = Modifier.padding(top = 10.dp).then(if (wide) Modifier.widthIn(max = 440.dp) else Modifier),
    )
}

/** The steps, the progress, what went wrong and the button. */
@Composable
private fun SetupProgress(state: Setup.State, onStart: () -> Unit) {
    val running = state as? Setup.State.Running
    val failed = state as? Setup.State.Failed
    val done = running?.done ?: failed?.done ?: 0
    val total = running?.total ?: failed?.total ?: 0

    Card(Modifier.padding(top = 28.dp)) {
        Setup.Step.entries.forEachIndexed { i, step ->
            val current = running?.step ?: failed?.step
            val status = if (current == null) 1 else step.ordinal.compareTo(current.ordinal)
            StepRow(stringResource(step.label), status, failed = failed != null && status == 0, divider = i > 0)
        }
    }

    if (running != null || failed != null) {
        val fraction by animateFloatAsState(if (total > 0) done.toFloat() / total else 0f, label = "setup")
        Row(Modifier.padding(top = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_setup_percent, (fraction * 100).toInt()), style = Type.Section, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.settings_count_of, done, total), style = Type.Caption)
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Oe.Raised)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(if (failed != null) Oe.Warn else Oe.AccentBright))
        }
        if (running != null) {
            Text(
                stringResource(R.string.settings_setup_downloading, running.itemName()) + if (running.bytes > 0) " · ${megabytes(running.bytes)}" else "",
                style = Type.Caption, modifier = Modifier.padding(top = 8.dp),
            )
            Text(stringResource(R.string.settings_setup_dont_close), style = Type.Caption.copy(color = Oe.Faint), modifier = Modifier.padding(top = 2.dp))
        }
    }

    if (failed != null) {
        Row(
            Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Oe.WarnBg).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Alert, Oe.Warn, 22.dp)
            Column {
                Text(failed.message(), style = Type.BodyMedium.copy(color = Oe.WarnText))
                Text(stringResource(R.string.settings_setup_resume_note), style = Type.Small, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_setup_source), style = Type.Caption.copy(color = Oe.Faint), modifier = Modifier.padding(top = 20.dp))
    if (running == null) {
        PrimaryButton(
            stringResource(if (failed != null) R.string.settings_setup_retry else R.string.settings_setup_start), onStart,
            Modifier.fillMaxWidth().padding(vertical = 24.dp).gamepadFocus(corner = 26.dp)
                .restorableFocus("start", androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }, initial = true),
            icon = Icons.Download,
        )
    } else {
        Spacer(Modifier.height(100.dp))
    }
}

/** [status]: below 0 done, 0 running, above 0 waiting. */
@Composable
private fun StepRow(label: String, status: Int, failed: Boolean, divider: Boolean) {
    if (divider) androidx.compose.material3.HorizontalDivider(color = Oe.Divider, thickness = 1.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                status < 0 -> Box(Modifier.size(24.dp).clip(CircleShape).background(Oe.OkBg), contentAlignment = Alignment.Center) { Icon(Icons.Check, Oe.Ok, 14.dp, stroke = 2.6f) }
                failed -> Icon(Icons.Alert, Oe.Warn, 22.dp)
                status == 0 -> CircularProgressIndicator(Modifier.size(20.dp), color = Oe.AccentBright, trackColor = Oe.Raised, strokeWidth = 2.5.dp)
                else -> Box(Modifier.size(20.dp).clip(CircleShape).background(Oe.Raised))
            }
        }
        Text(label, style = Type.Body.copy(color = if (status > 0) Oe.Muted else Oe.Text), textAlign = TextAlign.Start)
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
