package com.openemux.mobile

import android.content.Context
import android.content.Intent
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
        super.onCreate(savedInstanceState)
        // The library keeps the system bars, as the splash before it shows them: hiding them would only make them
        // flash in during the launch transition, which the app cannot reach. Only the game runs full screen.
        val style = barStyle(Oe.dark)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        setContent {
            val dark = Oe.dark
            LaunchedEffect(dark) { barIcons(dark) }
            ProvideWindowLayout { OpenEmuxRoot() }
        }
        // A recreated activity, or one relaunched from Recents, carries the link it was opened with: it was handled then.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleShareLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareLink(intent)
    }

    /**
     * An `openemux-share://` link only fills Connect by Address: it never
     * connects or sends on its own. The incoming-offer notification needs
     * nothing here: bringing the activity up shows the sheet from state.
     */
    private fun handleShareLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "openemux-share") return
        (application as OpenEmuxApp).shareCoordinator.link.value = uri.toString()
    }

    /** Status and navigation bar icons follow the theme, not the system. */
    private fun barStyle(dark: Boolean): SystemBarStyle {
        val transparent = android.graphics.Color.TRANSPARENT
        return if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
    }

    /** A theme change only turns the icons over: the window itself was set up once, in onCreate. */
    private fun barIcons(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onStart() {
        super.onStart()
        (application as OpenEmuxApp).retryArtwork()
        (application as OpenEmuxApp).setForeground(true)
    }

    override fun onStop() {
        (application as OpenEmuxApp).setForeground(false)
        super.onStop()
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
