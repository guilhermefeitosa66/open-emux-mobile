package com.openemux.mobile

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openemux.mobile.ui.GamepadActions
import com.openemux.mobile.ui.GamepadBus
import com.openemux.mobile.ui.InputMode
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.OpenEmuxRoot
import com.openemux.mobile.ui.ProvideWindowLayout
import com.openemux.mobile.ui.ThemePref

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.load(this)
        Oe.syncSystem(this)
        systemBars(Oe.dark)
        super.onCreate(savedInstanceState)
        setContent {
            val dark = Oe.dark
            LaunchedEffect(dark) { systemBars(dark) }
            ProvideWindowLayout { OpenEmuxRoot() }
        }
    }

    /** Status and navigation bar icons follow the theme, not the system. */
    private fun systemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        immersive()
    }

    /** Full screen, like the game: the bars come back over the app on a swipe and leave on their own. */
    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Back from the game, a dialog or the shade, the bars may be showing again.
        if (hasFocus) immersive()
    }

    override fun onStart() {
        super.onStart()
        (application as OpenEmuxApp).retryArtwork()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) InputMode.gamepad = false
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val pad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_DPAD) ||
            KeyEvent.isGamepadButton(code) || code in DPAD
        if (!pad) return super.dispatchKeyEvent(event)
        if (!InputMode.gamepad) {
            // The first press only wakes the highlight on the item that already has focus;
            // with none, it goes through and focus lands on the screen's first item.
            InputMode.gamepad = true
            if (code in DPAD && InputMode.focused > 0) return true
        }
        when (code) {
            // A confirms like the D-pad center does.
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(KeyEvent(event).let {
                KeyEvent(it.downTime, it.eventTime, it.action, KeyEvent.KEYCODE_DPAD_CENTER, it.repeatCount, it.metaState,
                    it.deviceId, it.scanCode, it.flags, InputDevice.SOURCE_DPAD)
            })
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        val pick: ((GamepadActions) -> (() -> Unit)?)? = when (code) {
            KeyEvent.KEYCODE_BUTTON_L1 -> { a -> a.l1 }
            KeyEvent.KEYCODE_BUTTON_R1 -> { a -> a.r1 }
            KeyEvent.KEYCODE_BUTTON_X -> { a -> a.x }
            KeyEvent.KEYCODE_BUTTON_Y -> { a -> a.y }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { a -> a.select }
            KeyEvent.KEYCODE_BUTTON_START -> { a -> a.start }
            else -> null
        }
        if (pick != null) {
            if (event.action == KeyEvent.ACTION_UP) GamepadBus.dispatch(pick)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var stickKey = 0
    private var stickNext = 0L

    /** The left stick and the hat move focus like the D-pad, with a key-repeat feel. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!ev.isFromSource(InputDevice.SOURCE_JOYSTICK) || ev.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(ev)
        }
        val x = ev.getAxisValue(MotionEvent.AXIS_X).takeIf { kotlin.math.abs(it) > 0.5f } ?: ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = ev.getAxisValue(MotionEvent.AXIS_Y).takeIf { kotlin.math.abs(it) > 0.5f } ?: ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val key = when {
            x < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            y < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            y > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> 0
        }
        val now = SystemClock.uptimeMillis()
        if (key == 0) { stickKey = 0; return true }
        if (key != stickKey || now >= stickNext) {
            stickNext = now + if (key != stickKey) 380 else 90
            stickKey = key
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, 0, ev.deviceId, 0, 0, InputDevice.SOURCE_DPAD)
            dispatchKeyEvent(down)
            dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        }
        return true
    }

    private companion object {
        val DPAD = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        )
    }
}
