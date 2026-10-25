package com.openemux.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/** Whether the app is being driven by a gamepad: the focus highlight shows only then. */
object InputMode {
    var gamepad by mutableStateOf(false)

    /** How many highlighted items hold focus now (0 or 1 in practice). */
    var focused = 0
}

/** The buttons a screen gives a meaning to, beyond moving focus, A and B. */
class GamepadActions(
    val l1: (() -> Unit)? = null,
    val r1: (() -> Unit)? = null,
    val x: (() -> Unit)? = null,
    val y: (() -> Unit)? = null,
    val select: (() -> Unit)? = null,
    val start: (() -> Unit)? = null,
    val hints: List<Pair<String, String>> = emptyList(),
)

/** The screens' actions, the one on top answering; the activity routes the buttons here. */
object GamepadBus {
    private val stack = mutableListOf<GamepadActions>()

    /** The hint bar of the screen on top. */
    var hints by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    fun push(actions: GamepadActions) { stack += actions; hints = stack.last().hints }
    fun pop(actions: GamepadActions) { stack.remove(actions); hints = stack.lastOrNull()?.hints.orEmpty() }

    /** Runs the top screen's handler for [pick], falling back to the ones below it. */
    fun dispatch(pick: (GamepadActions) -> (() -> Unit)?): Boolean {
        for (actions in stack.asReversed()) {
            pick(actions)?.let { it(); return true }
        }
        return false
    }
}

/** Registers this screen's buttons while it is on screen. */
@Composable
fun GamepadButtons(
    hints: List<Pair<String, String>> = emptyList(),
    l1: (() -> Unit)? = null,
    r1: (() -> Unit)? = null,
    x: (() -> Unit)? = null,
    y: (() -> Unit)? = null,
    select: (() -> Unit)? = null,
    start: (() -> Unit)? = null,
) {
    val latest = rememberUpdatedState(listOf(l1, r1, x, y, select, start))
    DisposableEffect(Unit) {
        // Which buttons a screen uses is fixed; what they do follows recomposition.
        fun slot(i: Int, used: Boolean): (() -> Unit)? = if (used) ({ latest.value[i]?.invoke() }) else null
        val actions = GamepadActions(
            l1 = slot(0, l1 != null), r1 = slot(1, r1 != null), x = slot(2, x != null),
            y = slot(3, y != null), select = slot(4, select != null), start = slot(5, start != null),
            hints = hints,
        )
        GamepadBus.push(actions)
        onDispose { GamepadBus.pop(actions) }
    }
}


/** The focus ring's stroke, its gap outside the item and the soft halo around it. */
private val FocusRing = 3.dp
private val FocusGap = 3.dp
private val FocusHalo = 6.dp

/**
 * How far the highlight reaches outside its item (gap, ring and halo). A
 * scroll container leaves at least this, plus its items' scale-up, as
 * padding on its scroll axis, or it cuts the ring.
 */
val FocusOutset = 12.dp

/** The room a focused item keeps from the edges of a vertical list. */
val FocusMargin = 24.dp

/** The hint bar's height, its divider included. */
val HintBarHeight = 40.dp

/** What the hint bar covers at the bottom of the screen: it shows in gamepad mode, so nothing outside it. */
val hintBarInset: Dp
    @Composable get() = if (InputMode.gamepad) HintBarHeight else 0.dp

/**
 * The focus highlight from the design: a 3dp ring 3dp outside the item with
 * a soft halo, an optional scale-up, and the item drawn over its neighbours.
 * [inset] draws it inside instead, with a lifted background, for rows in a
 * card. [onFocus] tells the item when it shows the highlight. Only shown in
 * gamepad mode.
 */
fun Modifier.gamepadFocus(
    corner: Dp = 14.dp,
    scale: Float = 1f,
    inset: Boolean = false,
    onFocus: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val shown = focused && InputMode.gamepad
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(120), label = "focus")
    val grow by animateFloatAsState(if (shown) scale else 1f, tween(120), label = "grow")
    if (onFocus != null) {
        val report = rememberUpdatedState<(Boolean) -> Unit>(onFocus)
        LaunchedEffect(shown) { report.value(shown) }
    }
    // An item that leaves while focused takes its count with it.
    DisposableEffect(Unit) { onDispose { if (focused) InputMode.focused -= 1 } }
    this
        .zIndex(if (shown) 1f else 0f)
        .onFocusChanged {
            val now = it.hasFocus || it.isFocused
            if (now != focused) InputMode.focused += if (now) 1 else -1
            focused = now
        }
        .graphicsLayer { scaleX = grow; scaleY = grow }
        .drawWithContent {
            if (inset && alpha > 0f) {
                drawRoundRect(Oe.Raised.copy(alpha = alpha), cornerRadius = CornerRadius(corner.toPx()))
            }
            drawContent()
            if (alpha > 0f) {
                val ring = (if (inset) 2.5.dp else FocusRing).toPx()
                val off = if (inset) -ring / 2f else FocusGap.toPx() + ring / 2f
                val r = corner.toPx() + off
                if (!inset) {
                    val halo = FocusHalo.toPx()
                    val out = off + ring / 2f + halo / 2f
                    drawRoundRect(
                        Oe.AccentBright.copy(alpha = 0.28f * alpha),
                        topLeft = Offset(-out, -out),
                        size = Size(size.width + out * 2, size.height + out * 2),
                        cornerRadius = CornerRadius(corner.toPx() + out),
                        style = Stroke(halo),
                    )
                }
                drawRoundRect(
                    Oe.AccentText.copy(alpha = alpha),
                    topLeft = Offset(-off, -off),
                    size = Size(size.width + off * 2, size.height + off * 2),
                    cornerRadius = CornerRadius(r.coerceAtLeast(0f)),
                    style = Stroke(ring),
                )
            }
        }
}

/** Focus scrolling that answers with [pick] in gamepad mode and leaves touch to the platform's [fallback]. */
@OptIn(ExperimentalFoundationApi::class)
private class FocusScrollSpec(
    private val fallback: BringIntoViewSpec,
    private val pick: (offset: Float, size: Float, containerSize: Float) -> Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (InputMode.gamepad) pick(offset, size, containerSize)
        else fallback.calculateScrollDistance(offset, size, containerSize)
}

/**
 * The scroll containers in [content] keep the gamepad's focused item in the
 * middle; the ends of the list still rest against its padding. For the
 * sideways rows of games. A scrollable nested inside provides its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CenteredFocusScroll(content: @Composable () -> Unit) {
    val outer = LocalBringIntoViewSpec.current
    val spec = remember(outer) { FocusScrollSpec(outer, FocusGeometry::centered) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * The scroll containers in [content] keep the gamepad's focused item
 * [leading] from their start and [trailing] from their end, scrolling only
 * when it leaves that band. A vertical list adds what covers its bottom (the
 * tab bar, [hintBarInset]) to [trailing]; a row nested inside a list provides
 * its own, or it inherits the list's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarginFocusScroll(leading: Dp = FocusMargin, trailing: Dp = FocusMargin, content: @Composable () -> Unit) {
    val outer = LocalBringIntoViewSpec.current
    val density = LocalDensity.current
    val start = with(density) { leading.toPx() }
    val end = with(density) { trailing.toPx() }
    val spec = remember(outer, start, end) {
        FocusScrollSpec(outer) { offset, size, container -> FocusGeometry.margin(offset, size, container, start, end) }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Keeps gamepad focus in this list when it is pushed past the last item in
 * [direction]. The margin leaves room between the last item and what lies
 * beyond it (the tab bar, the header), and the focus search would otherwise
 * jump there; the last item holds focus instead, as when it touched the edge.
 */
fun Modifier.holdFocusAtEnd(direction: FocusDirection): Modifier =
    focusProperties {
        onExit = { if (InputMode.gamepad && requestedFocusDirection == direction) cancelFocusChange() }
    }.focusGroup()

/**
 * A row that scrolls sideways under the gamepad without cutting the first
 * and last items' ring: it reaches [bleed] into its parent's side padding
 * and pads its content by as much inside the scroll, so the content lines up
 * with the rest of the screen while the ring has room at both ends.
 */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    bleed: Dp = 20.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    MarginFocusScroll(leading = bleed, trailing = bleed) {
        Row(
            modifier.bleed(bleed).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = bleed),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

/** Widens the layout by [x] on each side, past its parent's padding. */
private fun Modifier.bleed(x: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedWidth) (x * 2).roundToPx() else 0
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * Remembers this item as the screen's focused one in [saved] and, when the
 * screen comes back in gamepad mode, focuses it again; [initial] marks the
 * item that takes focus when nothing was saved.
 */
fun Modifier.restorableFocus(key: String, saved: MutableState<String?>, initial: Boolean = false): Modifier = composed {
    val requester = remember { FocusRequester() }
    // Also when the gamepad wakes up with nothing focused, so focus is never missing.
    LaunchedEffect(InputMode.gamepad) {
        if (!InputMode.gamepad) return@LaunchedEffect
        if (saved.value == key) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        } else if (saved.value == null && initial) {
            // Let a screen or page transition hand its focus over first.
            delay(150)
            if (InputMode.focused == 0) runCatching { requester.requestFocus() }
        }
    }
    this.focusRequester(requester).onFocusChanged { if (it.isFocused || it.hasFocus) saved.value = key }
}

/** The gamepad hint bar: badge + label pairs, only in gamepad mode. */
@Composable
fun HintBar(hints: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    AnimatedVisibility(InputMode.gamepad, modifier, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        Column(Modifier.fillMaxWidth().background(Oe.Bg.copy(alpha = 0.94f))) {
            HorizontalDivider(color = Oe.Surface2, thickness = 1.dp)
            Row(
                Modifier.fillMaxWidth().height(HintBarHeight - 1.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            ) {
                hints.forEach { (badge, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.height(22.dp).widthIn(min = 22.dp).background(
                                if (badge == "A") Oe.AccentBright else Oe.Raised, RoundedCornerShape(11.dp),
                            ).padding(horizontal = if (badge.length > 1) 7.dp else 0.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(badge, style = Type.Tag.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (badge == "A") Color.White else Oe.Text))
                        }
                        Text(label, style = Type.Caption.copy(fontSize = 12.sp, color = Oe.Muted))
                    }
                }
            }
        }
    }
}
