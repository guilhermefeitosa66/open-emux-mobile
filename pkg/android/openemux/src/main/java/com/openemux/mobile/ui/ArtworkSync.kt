package com.openemux.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.SyncPause
import com.openemux.mobile.data.SyncProgress
import com.retroarch.R
import kotlinx.coroutines.delay

private val PausedBg get() = Oe.WarnBg
private val PausedBorder get() = if (Oe.dark) Color(0xFF3D3424) else Color(0xFFF0DDB8)
private val PausedIcon get() = Oe.Warn
private val PausedText get() = Oe.WarnText
private val Track get() = Oe.Raised

/** True while the ROM's label is still on its way (not while the sync is paused). */
@Composable
fun labelPending(rom: Rom): Boolean {
    val labels = LocalActions.current.app.labels
    val pending by labels.pending.collectAsState()
    val progress by labels.progress.collectAsState()
    return progress.running && rom.key in pending
}

private fun pauseMessage(pause: SyncPause) = when (pause) {
    SyncPause.Offline -> R.string.library_sync_paused_offline
    SyncPause.Quota -> R.string.library_sync_paused_quota
}

/** The non-blocking pill shown on the shelf and library while artwork is pending. */
@Composable
fun ArtworkSyncPill(modifier: Modifier = Modifier) {
    val app = LocalActions.current.app
    val covers by app.covers.progress.collectAsState()
    val labels by app.labels.progress.collectAsState()
    var sheet by remember { mutableStateOf(false) }

    // Covers first: they are quick, labels take about a second each.
    // First: true for labels, false for covers.
    val shown: Pair<Boolean, SyncProgress>? = when {
        covers.running -> false to covers
        labels.running -> true to labels
        labels.paused != null -> true to labels
        covers.paused != null -> false to covers
        else -> null
    }
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown

    AnimatedVisibility(shown != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val (isLabels, p) = last ?: return@AnimatedVisibility
        val paused = p.paused
        val status = stringResource(
            when {
                paused != null && isLabels -> R.string.library_sync_paused_labels
                paused != null -> R.string.library_sync_paused_covers
                isLabels -> R.string.library_sync_fetching_labels
                else -> R.string.library_sync_fetching_covers
            },
        )
        val count = stringResource(R.string.library_sync_progress, p.done, p.total)
        Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .gamepadFocus(corner = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (paused != null) PausedBg else Oe.Surface)
                .border(1.dp, if (paused != null) PausedBorder else Oe.Border, RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { sheet = true },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 14.dp, end = 12.dp, top = if (paused != null) 8.dp else 0.dp, bottom = if (paused != null) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (paused != null) PauseBars() else SyncSpinner()
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Oe.Text)) {
                                append(status)
                            }
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Oe.Muted)) { append(" · $count") }
                        },
                        style = Type.Body.copy(fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (paused != null) {
                        Text(stringResource(pauseMessage(paused)), style = Type.Small.copy(fontSize = 12.sp, lineHeight = 16.sp, color = PausedText))
                    }
                }
                Icon(Icons.Chevron, Oe.Chevron, 16.dp)
            }
            if (paused == null) {
                val fraction by animateFloatAsState(if (p.total > 0) p.done.toFloat() / p.total else 0f, label = "sync")
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Oe.Text.copy(alpha = 0.06f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Oe.AccentBright))
                }
            }
        }
    }
    if (sheet) ArtworkSheet { sheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtworkSheet(onClose: () -> Unit) {
    val app = LocalActions.current.app
    val covers by app.covers.progress.collectAsState()
    val labels by app.labels.progress.collectAsState()
    ModalBottomSheet(onClose, containerColor = Oe.Sheet, scrimColor = Color.Black.copy(alpha = 0.55f)) {
        ImmersiveWindow()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 34.dp)) {
            Text(stringResource(R.string.library_sync_sheet_title), style = Type.Title.copy(fontSize = 22.sp))
            Text(stringResource(R.string.library_sync_sheet_subtitle), style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Oe.Raised)) {
                SyncRow(stringResource(R.string.library_sync_covers), "openemux-artwork", Icons.Grid, Oe.Surface2, Oe.Muted, covers, eta = false)
                HorizontalDivider(color = Track, thickness = 1.dp)
                SyncRow(stringResource(R.string.library_sync_labels), "ScreenScraper", Icons.Cart, Oe.AccentSoft, Oe.AccentOnSoft, labels, eta = true)
            }
            Text(
                stringResource(R.string.library_sync_labels_note),
                style = Type.Small, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp),
            )
            SecondaryButton(stringResource(R.string.library_sync_close), onClose, Modifier.fillMaxWidth(), height = 50.dp)
        }
    }
}

@Composable
private fun SyncRow(title: String, source: String, icon: String, tile: Color, tint: Color, p: SyncProgress, eta: Boolean) {
    val paused = p.paused
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tile), contentAlignment = Alignment.Center) {
                Icon(icon, tint, 18.dp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = Type.BodyMedium)
                Text(source, style = Type.Small)
            }
            val complete = !p.running && paused == null && p.total > 0
            Text(
                stringResource(R.string.library_sync_progress, p.done, p.total),
                style = Type.Body.copy(fontSize = 14.sp, fontWeight = if (complete) FontWeight.Medium else FontWeight.SemiBold, color = if (complete) Oe.Muted else Oe.Text),
            )
            Spacer(Modifier.width(8.dp))
            when {
                paused != null -> PauseBars()
                p.running -> SyncSpinner()
                else -> Icon(Icons.Check, Oe.AccentText, 18.dp)
            }
        }
        if (p.running || paused != null) {
            val fraction by animateFloatAsState(if (p.total > 0) p.done.toFloat() / p.total else 0f, label = "row")
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Track)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(if (paused != null) PausedIcon else Oe.AccentBright))
            }
            Row(Modifier.padding(top = 8.dp)) {
                if (paused != null) {
                    Text(stringResource(pauseMessage(paused)), style = Type.Small.copy(color = PausedText))
                } else {
                    Text(stringResource(R.string.library_sync_now, p.current ?: "..."), style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (eta) {
                        // About two requests a second apart per ROM: the lookup and the image.
                        val minutes = ((p.total - p.done) * 2 + 59) / 60
                        Text(stringResource(R.string.library_sync_eta_minutes, minutes.coerceAtLeast(1)), style = Type.Small, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** "Labels ready" for 3 s when a label run ends. */
@Composable
fun ArtworkDoneToast(modifier: Modifier = Modifier) {
    val labels by LocalActions.current.app.labels.progress.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(labels) }
    LaunchedEffect(labels.finishedAt) {
        if (labels.finishedAt == 0L || System.currentTimeMillis() - labels.finishedAt > 5000) return@LaunchedEffect
        shown = labels
        visible = true
        delay(3000)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .height(48.dp)
                .shadow(24.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(RoundedCornerShape(14.dp))
                .background(Oe.Divider)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Check, Oe.AccentText, 18.dp)
            Spacer(Modifier.width(10.dp))
            val title = stringResource(R.string.library_sync_labels_done)
            val withArt = shown.done - shown.missing
            val detail = if (shown.missing > 0) {
                stringResource(R.string.library_sync_labels_done_missing, withArt, shown.missing)
            } else {
                stringResource(R.string.library_sync_labels_done_all, withArt)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Oe.Text)) { append(title) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Oe.Muted)) { append(" · $detail") }
                },
                style = Type.Body.copy(fontSize = 14.sp),
            )
        }
    }
}

@Composable
fun SyncSpinner(size: Int = 16) {
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spin",
    )
    Canvas(Modifier.size(size.dp)) {
        val stroke = Stroke(3.dp.toPx() * size / 16f, cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(Track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
        drawArc(Oe.AccentText, rotation, 90f, false, Offset(inset, inset), arcSize, style = stroke)
    }
}

@Composable
private fun PauseBars() {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width * 0.22f
        val h = size.height * 0.7f
        val top = (size.height - h) / 2
        drawRoundRect(PausedIcon, Offset(size.width * 0.2f, top), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2))
        drawRoundRect(PausedIcon, Offset(size.width * 0.58f, top), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2))
    }
}

/** A label still loading: a sweep of light, distinct from the blank no-art label. */
@Composable
fun LabelShimmer() {
    val shift by rememberInfiniteTransition(label = "shimmer").animateFloat(
        1f, -1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "shimmer",
    )
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
        val w = constraints.maxWidth.toFloat()
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF2A2A33), Color(0xFF3A3A46), Color(0xFF2A2A33)),
                    startX = w * shift, endX = w * shift + w * 2,
                )
            )
        )
    }
}
