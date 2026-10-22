package com.openemux.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.Rom
import com.openemux.mobile.launch.CartArt
import java.io.File

/** Stroke icons from the design, as SVG path data on a 24-unit grid. */
object Icons {
    const val Back = "M15 18l-6-6 6-6"
    const val Chevron = "M9 6l6 6-6 6"
    const val Search = "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14M20 20l-3.5-3.5"
    const val Sort = "M4 7h16M7 12h10M10 17h4"
    const val Cart = "M4 5h16v11l-2 3H6l-2-3zM8 8h8v5H8z"
    const val Grid = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
    const val List = "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01"
    const val Clock = "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16M12 8v4l3 2"
    const val Download = "M12 4v11M7 10l5 5 5-5M5 20h14"
    const val Sliders = "M4 7h10M18 7h2M4 17h4M12 17h8M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4M10 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4"
    const val Play = "M7 4.5v15l13-7.5z"
    const val Star = "M12 3l2.8 5.9 6.2.8-4.6 4.3 1.2 6.3L12 17.3 6.4 20.3l1.2-6.3L3 9.7l6.2-.8z"
    const val Close = "M6 6l12 12M18 6L6 18"
    const val Check = "M5 12.5l4.5 4.5L19 7.5"
    const val Info = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 11v5M12 8h.01"
    const val Warn = "M12 3l9.5 17h-19zM12 10v4.5M12 17.5h.01"
    const val Alert = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 7.5v5.5M12 16.5h.01"
    const val Folder = "M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2.5h8.5A1.5 1.5 0 0 1 21 9v9.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5zM12 11v6M9 14l3 3 3-3"
    const val File = "M6 3h8l4 4v14H6zM14 3v4h4M12 11v6M9 14l3 3 3-3"
    const val Chip = "M5 5h14v14H5zM9 9h6v6H9zM9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"
    const val Save = "M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6"
    const val Load = "M4 12a8 8 0 1 0 2.3-5.6M4 4v5h5"
    const val FastForward = "M3 6v12l9-6zM12 6v12l9-6z"
    const val Camera = "M4 8h3l2-3h6l2 3h3v11H4zM12 9.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7"
    const val Shader = "M3 5h18v14H3zM3 10h18M3 14h18M9 5v14M15 5v14"
    const val Audio = "M4 9v6h4l5 4V5L8 9zM16.5 8.5a5 5 0 0 1 0 7"
    const val Mute = "M4 9v6h4l5 4V5L8 9zM17 9l4 6M21 9l-4 6"
    const val Reset = "M20 12a8 8 0 1 1-2.3-5.6M20 4v5h-5"
    const val Trash = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"
    const val Dots = "M5 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2M19 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2"
}

@Composable
fun Icon(path: String, color: Color, size: Dp = 20.dp, stroke: Float = 2f, filled: Boolean = false, modifier: Modifier = Modifier) {
    val vector = ImageVector.Builder(defaultWidth = size, defaultHeight = size, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = if (filled) SolidColor(color) else null,
            stroke = if (filled) null else SolidColor(color),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
    Image(vector, contentDescription = null, modifier = modifier.size(size))
}

/** The ROM's cartridge label, once [com.openemux.mobile.data.Labels] has it. */
@Composable
fun labelFor(rom: Rom): File? {
    val found by LocalActions.current.app.labels.found.collectAsState()
    return found[rom.key]
}

/** The cartridge: label art cropped into the label window, frame art on top. */
@Composable
fun Cartridge(
    console: Console,
    label: File?,
    title: String,
    color: String,
    modifier: Modifier = Modifier,
    shadow: Boolean = true,
    pending: Boolean = false,
) {
    val context = LocalContext.current
    val frame = CartArt.frameRes(context, console, color)
    BoxWithConstraints(modifier.aspectRatio(console.frameAspect)) {
        val w = maxWidth
        val h = maxHeight
        val l = console.label
        Box(
            Modifier
                .offset(w * l.left, h * l.top)
                .size(w * l.width, h * l.height)
                .background(Color(0xFFDEDBD4))
        ) {
            if (label != null) {
                SubcomposeAsyncImage(
                    model = label,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    error = { BlankLabel(title) },
                    loading = { Box(Modifier.fillMaxSize().background(Color(0xFFDEDBD4))) },
                )
            } else if (pending) {
                LabelShimmer()
            } else {
                BlankLabel(title)
            }
        }
        Image(
            painterResource(frame),
            contentDescription = title,
            modifier = Modifier.fillMaxSize().let { if (shadow) it else it },
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
private fun BlankLabel(title: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFFDEDBD4)).padding(6.dp), contentAlignment = Alignment.Center) {
        Text(
            title,
            style = Type.Caption.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold, color = Color(0xFF28282E), fontSize = 11.sp),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RoundIconButton(path: String, label: String, onClick: () -> Unit, tint: Color = Oe.Text, background: Color = Color(0xFF1E1E25), filled: Boolean = false) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(path, tint, 20.dp, filled = filled) }
}

@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Back, Oe.Muted, 20.dp)
        Text(label, style = Type.BodyMedium.copy(color = Oe.Muted))
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, height: Dp = 52.dp, enabled: Boolean = true) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Oe.Accent else Oe.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, Color.White, 18.dp, filled = icon == Icons.Play)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.Button)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 52.dp, icon: String? = null) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Oe.Surface2)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, Oe.Text, 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.Button.copy(color = Oe.Text, fontSize = 15.sp))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Oe.Surface)) { content() }
}

@Composable
fun ListRow(
    label: String,
    value: String? = null,
    subtitle: String? = null,
    divider: Boolean = false,
    minHeight: Dp = 50.dp,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    if (divider) HorizontalDivider(color = Oe.Divider, thickness = 1.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.Body)
            if (subtitle != null) Text(subtitle, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
        if (value != null) Text(value, style = Type.Body.copy(color = Oe.Muted, fontSize = 14.sp))
        if (onClick != null) Icon(Icons.Chevron, Oe.Chevron, 16.dp, stroke = 2.2f)
    }
}

@Composable
fun Tag(text: String, background: Color, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(background).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, style = Type.Tag.copy(color = color))
    }
}

@Composable
fun RadioCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    badge: String? = null,
    background: Color = Oe.Surface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Oe.AccentTint else background)
            .border(1.5.dp, if (selected) Oe.AccentBright else Oe.Border, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) Oe.AccentBright else Color(0xFF55555F), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Oe.AccentBright))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = Type.Section)
                if (badge != null) Tag(badge, Color(0xFF2A2340), Color(0xFFC9B6FF))
            }
            Text(description, style = Type.Small)
        }
    }
}

@Composable
fun ConsoleChip(console: Console, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(console.shelfColor),
        contentAlignment = Alignment.Center,
    ) { Text(console.short, style = Type.Tag.copy(color = Color.White, fontSize = 11.sp)) }
}

@Composable
fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Oe.Surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Cart, Oe.AccentText, 30.dp)
        }
        Text(title, style = Type.Title.copy(fontSize = 20.sp), textAlign = TextAlign.Center)
        Text(body, style = Type.Small, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            PrimaryButton(action, onAction, Modifier.fillMaxWidth(), icon = Icons.Download)
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)

fun formatPlaytime(seconds: Long): String = when {
    seconds <= 0 -> "nunca jogado"
    seconds < 60 -> "menos de 1 min jogado"
    seconds < 3600 -> "${seconds / 60} min jogados"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min jogadas"
}

fun formatAgo(time: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - time) / 1000
    return when {
        d < 60 -> "agora"
        d < 3600 -> "há ${d / 60} min"
        d < 86400 -> "há ${d / 3600} h"
        d < 2 * 86400 -> "ontem"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale("pt", "BR")).format(java.util.Date(time))
    }
}
