package com.openemux.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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


/**
 * The focus highlight from the design: a 2.5dp ring 3dp outside the item
 * with a soft halo, and an optional scale-up. [inset] draws it inside instead,
 * with a lifted background, for rows in a card. Only shown in gamepad mode.
 */
fun Modifier.gamepadFocus(corner: Dp = 14.dp, scale: Float = 1f, inset: Boolean = false): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val shown = focused && InputMode.gamepad
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(120), label = "focus")
    val grow by animateFloatAsState(if (shown) scale else 1f, tween(120), label = "grow")
    // An item that leaves while focused takes its count with it.
    DisposableEffect(Unit) { onDispose { if (focused) InputMode.focused -= 1 } }
    this
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
                val ring = 2.5.dp.toPx()
                val off = if (inset) -ring / 2f else 3.dp.toPx()
                val r = corner.toPx() + off
                if (!inset) {
                    val halo = 7.dp.toPx()
                    drawRoundRect(
                        Oe.AccentBright.copy(alpha = 0.28f * alpha),
                        topLeft = Offset(-off - halo / 2f, -off - halo / 2f),
                        size = Size(size.width + (off + halo / 2f) * 2, size.height + (off + halo / 2f) * 2),
                        cornerRadius = CornerRadius(r + halo / 2f),
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
fun HintBar(hints: List<Pair<String, String>>, modifier: Modifier = Modifier, tall: Boolean = false) {
    AnimatedVisibility(InputMode.gamepad, modifier, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        Column(Modifier.fillMaxWidth().background(Oe.Bg.copy(alpha = 0.94f))) {
            HorizontalDivider(color = Oe.Surface2, thickness = 1.dp)
            Row(
                Modifier.fillMaxWidth().height(if (tall) 40.dp else 36.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            ) {
                hints.forEach { (badge, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).background(
                                if (badge == "A") Oe.AccentBright else Oe.Raised, RoundedCornerShape(11.dp),
                            ).padding(horizontal = 6.dp),
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
