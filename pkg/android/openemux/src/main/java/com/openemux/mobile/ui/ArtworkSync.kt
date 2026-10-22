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
import kotlinx.coroutines.delay

private val PausedBg = Color(0xFF24201A)
private val PausedBorder = Color(0xFF3D3424)
private val PausedIcon = Color(0xFFF5B544)
private val PausedText = Color(0xFFC9B892)
private val Track = Color(0xFF2E2E38)

/** True while the ROM's label is still on its way (not while the sync is paused). */
@Composable
fun labelPending(rom: Rom): Boolean {
    val labels = LocalActions.current.app.labels
    val pending by labels.pending.collectAsState()
    val progress by labels.progress.collectAsState()
    return progress.running && rom.key in pending
}

private fun pauseMessage(pause: SyncPause) = when (pause) {
    SyncPause.Offline -> "Sem conexão. Retoma quando a rede voltar."
    SyncPause.Quota -> "Cota diária do ScreenScraper esgotada. Tenta de novo amanhã."
}

/** The non-blocking pill shown on the shelf and library while artwork is pending. */
@Composable
fun ArtworkSyncPill(modifier: Modifier = Modifier) {
    val app = LocalActions.current.app
    val covers by app.covers.progress.collectAsState()
    val labels by app.labels.progress.collectAsState()
    var sheet by remember { mutableStateOf(false) }

    // Covers first: they are quick, labels take about a second each.
    val shown: Pair<String, SyncProgress>? = when {
        covers.running -> "capas" to covers
        labels.running -> "rótulos" to labels
        labels.paused != null -> "rótulos" to labels
        covers.paused != null -> "capas" to covers
        else -> null
    }
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown

    AnimatedVisibility(shown != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val (kind, p) = last ?: return@AnimatedVisibility
        val paused = p.paused
        Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
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
                                append(if (paused != null) "${kind.replaceFirstChar { it.uppercase() }} pausados" else "Buscando $kind")
                            }
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Oe.Muted)) { append(" · ${p.done} de ${p.total}") }
                        },
                        style = Type.Body.copy(fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (paused != null) {
                        Text(pauseMessage(paused), style = Type.Small.copy(fontSize = 12.sp, lineHeight = 16.sp, color = PausedText))
                    }
                }
                Icon(Icons.Chevron, Oe.Chevron, 16.dp)
            }
            if (paused == null) {
                val fraction by animateFloatAsState(if (p.total > 0) p.done.toFloat() / p.total else 0f, label = "sync")
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.06f))) {
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
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 34.dp)) {
            Text("Artes dos jogos", style = Type.Title.copy(fontSize = 22.sp))
            Text("Segue em segundo plano. Dá para jogar enquanto isso.", style = Type.Small.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF22222A))) {
                SyncRow("Capas", "openemux-artwork", Icons.Grid, Color(0xFF26262E), Oe.Muted, covers, eta = false)
                HorizontalDivider(color = Track, thickness = 1.dp)
                SyncRow("Rótulos dos cartuchos", "ScreenScraper", Icons.Cart, Color(0xFF3B2A6B), Color(0xFFD4C4FF), labels, eta = true)
            }
            Text(
                "Os rótulos vêm do ScreenScraper, que reconhece a ROM pelo hash. Um por segundo, por limite do serviço.",
                style = Type.Small, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp),
            )
            SecondaryButton("Fechar", onClose, Modifier.fillMaxWidth(), height = 50.dp)
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
                "${p.done} de ${p.total}",
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
                    Text(pauseMessage(paused), style = Type.Small.copy(color = PausedText))
                } else {
                    Text("Agora: ${p.current ?: "..."}", style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (eta) {
                        // About two requests a second apart per ROM: the lookup and the image.
                        val minutes = ((p.total - p.done) * 2 + 59) / 60
                        Text(if (minutes <= 1) "~1 min" else "~$minutes min", style = Type.Small, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** "Rótulos prontos" for 3 s when a label run ends. */
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
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Oe.Text)) { append("Rótulos prontos") }
                    val withArt = shown.done - shown.missing
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Oe.Muted)) {
                        append(if (shown.missing > 0) " · $withArt com arte, ${shown.missing} sem" else " · $withArt com arte")
                    }
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
