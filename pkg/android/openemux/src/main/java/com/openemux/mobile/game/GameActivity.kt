package com.openemux.mobile.game

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import com.openemux.mobile.AppLocale
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.NDS_LAYOUTS
import com.openemux.mobile.data.NdsLayouts
import com.openemux.mobile.data.ScreenLayout
import com.openemux.mobile.launch.GameSkin
import com.openemux.mobile.launch.SkinSize
import com.openemux.mobile.ui.InputMode
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.ProvideWindowLayout
import com.openemux.mobile.ui.ThemePref
import com.retroarch.R
import com.retroarch.browser.retroactivity.RetroActivityFuture
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The RetroArch NativeActivity, with the in-game menu swapped for the
 * OpenEmux pause sheet. Runs in its own process (see the manifest).
 */
class GameActivity : RetroActivityFuture() {
    private var resumedAt = 0L
    var sessionStart = 0L
        private set
    private var dialog: ComponentDialog? = null
    private var sheetView: ComposeView? = null

    /** Read on the engine thread: whether the pause sheet takes the gamepad. */
    @Volatile private var sheetOpen = false

    /** Written on the engine thread (onToggleStates): whether fast forward is on, for the pause sheet. */
    @Volatile private var fastForwardOn = false
    /**
     * What the pause sheet's fast-forward tile asked for and the engine has not been sent yet; null = nothing pending.
     * A sheet opened before it is applied shows it and takes it over.
     */
    @Volatile private var fastForwardWanted: Boolean? = null
    /** Bumped each time the pause sheet opens, so a deferred fast-forward toggle knows a newer sheet took over. */
    @Volatile private var sheetCount = 0
    private var stickKey = 0

    // The NDS overlay last shown: its orientation, and the layout each orientation was left on (engine thread only).
    private var ndsOrientation = "portrait"
    private val ndsLayouts = mutableMapOf<String, String>()
    private var ndsRestoring: String? = null

    /** The overlay last shown, which a redrawn skin opens on (engine thread writes). */
    @Volatile private var currentOverlay: String? = null

    // The window the skin in use was drawn for, and the one a redraw is under way for (skin thread writes).
    @Volatile private var skinSize: SkinSize? = null
    private val skinWanted = AtomicReference<SkinSize?>(null)
    private var skinSeq = 0
    private var engineReady = false
    private val skinThread = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val skinCheck = Runnable { checkSkinSize() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStart = System.currentTimeMillis()
        if (intent.hasExtra(GameExtras.LOAD_SLOT)) loadSlotWhenRunning(intent.getIntExtra(GameExtras.LOAD_SLOT, 0))
        skinSize = SkinSize.parse(intent.getStringExtra(GameExtras.SKIN_SIZE))
        // The launcher drew for the largest window: a game opened in split screen or free form needs its own.
        window.decorView.post { scheduleSkinCheck() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleSkinCheck()
    }

    /** An unfocused engine stops polling commands: a redraw it missed is tried again once it has focus back. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleSkinCheck()
    }

    override fun onDestroy() {
        handler.removeCallbacks(skinCheck)
        skinThread.shutdownNow()
        super.onDestroy()
    }

    /** A free-form drag sends a burst of changes: look once it settles. */
    private fun scheduleSkinCheck() {
        if (Build.VERSION.SDK_INT < 30 || isFinishing || isDestroyed) return
        handler.removeCallbacks(skinCheck)
        handler.postDelayed(skinCheck, SKIN_DEBOUNCE_MS)
    }

    private fun checkSkinSize() {
        if (Build.VERSION.SDK_INT < 30 || isFinishing || isDestroyed || skinThread.isShutdown) return
        val size = SkinSize.of(windowManager.currentWindowMetrics)
        if (!size.differs(skinWanted.get() ?: skinSize)) return
        skinWanted.set(size)
        // One redraw at a time; a newer window supersedes one still waiting.
        skinThread.execute {
            // shutdownNow() in onDestroy interrupts a wait for the engine: an uncaught exception here would kill :game.
            try {
                if (skinWanted.get() === size) {
                    // Back to the window the shown skin was drawn for: nothing to redraw.
                    if (size.differs(skinSize)) redrawSkin(size) else skinWanted.compareAndSet(size, null)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Draws the skin for [size] into a fresh directory (the engine skips
     * reloading a path it already has) and swaps it in with OVERLAY_LOAD,
     * the core running on. A refused one leaves the old skin in place.
     */
    private fun redrawSkin(size: SkinSize) {
        val launchDir = intent.getStringExtra(GameExtras.LAUNCH_DIR)?.let(::File)
        val console = Console.byId(intent.getStringExtra(GameExtras.CONSOLE))
        val base = intent.getStringExtra(GameExtras.SKINS_BASE)?.let(::File)
        if (launchDir == null || console == null || base == null || !engineRunning()) {
            skinWanted.compareAndSet(size, null)
            return
        }
        val dir = File(launchDir, "skin-${++skinSeq}")
        val reply = runCatching {
            // Chosen as at launch: the console's package skin, else the drawn one.
            val skin = GameSkin.render(
                this, base, console, size, dir,
                density = resources.displayMetrics.density,
                hardcore = intent.getBooleanExtra(GameExtras.HARDCORE, false),
                coreId = intent.getStringExtra(GameExtras.CORE_ID),
                first = overlayToReopen(),
            ).file
            RaCommand.ask("OVERLAY_LOAD ${skin.absolutePath}", 2000)
        }.getOrNull()
        val ok = reply?.startsWith("OK") == true
        if (ok) {
            Log.i(TAG, "OVERLAY_LOAD skin for $size: ${dir.name}")
            skinSize = size
            launchDir.listFiles { f -> f.isDirectory && f.name.startsWith("skin") && f != dir }?.forEach { it.deleteRecursively() }
        } else {
            Log.w(TAG, "Skin for $size not loaded, keeping the one shown")
            // No answer is not a refusal: the engine may be loading this skin right now, so it stays on disk.
            if (reply != null) dir.deleteRecursively()
        }
        // Only this redraw's own request is cleared: a newer one set meanwhile still runs.
        skinWanted.compareAndSet(size, null)
    }

    /**
     * The overlay a redrawn skin opens on. An NDS layout picked from the pause
     * sheet leaves the hidden overlay behind: reopen on the layout the core shows.
     */
    private fun overlayToReopen(): String? = synchronized(ndsLayouts) {
        val shown = currentOverlay ?: return null
        if (!ndsScreens || '@' !in shown) return shown
        val orientation = shown.substringBefore('@')
        ndsLayouts[orientation]?.let { "$orientation@$it" } ?: shown
    }

    /** The engine answers commands once the content is up; a game opened in split screen gets here early. */
    private fun engineRunning(): Boolean {
        val deadline = System.currentTimeMillis() + 30_000
        while (!engineReady && System.currentTimeMillis() < deadline) {
            engineReady = RaCommand.isRunning(300)
            if (!engineReady) Thread.sleep(250)
        }
        return engineReady
    }

    /** Opening at a chosen state: wait for the content, then load it. */
    private fun loadSlotWhenRunning(slot: Int) {
        Thread {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (RaCommand.ask("GET_STATUS", 300)?.contains("PLAYING") == true) {
                    val reply = RaCommand.ask("LOAD_STATE_SLOT $slot", 3000)
                    if (reply?.contains("ERROR") == true) runOnUiThread {
                        android.widget.Toast.makeText(this, getString(R.string.game_toast_state_saved_other_core), android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                Thread.sleep(250)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        resumedAt = System.currentTimeMillis()
    }

    override fun onPause() {
        flushPlaytime()
        super.onPause()
    }

    /**
     * Called on the engine thread when fast forward or turbo goes on or off, whatever flipped it: the skin's strip,
     * the pause sheet or a gamepad hotkey. The strip draws its own "on" look (overlayN_descM_lit_by); the pause
     * sheet reads fast forward from here.
     */
    override fun onToggleStates(fastForward: Boolean, turbo: Boolean) {
        fastForwardOn = fastForward
    }

    /**
     * Called on the engine thread when the skin's overlay changes; a redrawn
     * skin opens on it. An NDS
     * overlay is named "<orientation>@<layout>": DeSmuME is set to that
     * screen layout, which is kept for the orientation.
     */
    override fun onOverlayChanged(name: String): Unit = synchronized(ndsLayouts) {
        currentOverlay = name
        if (!ndsScreens) return
        val orientation = name.substringBefore('@')
        val layout = NDS_LAYOUTS[orientation]?.firstOrNull { it.id == name.substringAfter('@', "") } ?: return
        loadNdsLayouts()
        if (orientation != ndsOrientation) ndsRestoring = ndsLayouts[orientation]
        ndsOrientation = orientation
        // Turning lands on the layout saved at launch: step on, one overlay at a time, to the one used last here.
        if (ndsRestoring != null && ndsRestoring != layout.id) {
            Thread { RaCommand.send("OVERLAY_NEXT") }.start()
            return
        }
        ndsRestoring = null
        ndsLayouts[orientation] = layout.id
        Thread {
            NdsLayouts.save(filesDir, orientation, layout)
            RaCommand.send("SET_CORE_OPTION desmume_screens_layout ${layout.value}")
        }.start()
    }

    /** NDS on DeSmuME: the skin and the pause sheet switch its screen layout. */
    private val ndsScreens by lazy {
        intent.getStringExtra(GameExtras.CONSOLE) == "NDS" && "desmume" in intent.getStringExtra("LIBRETRO").orEmpty()
    }

    private fun loadNdsLayouts() {
        if (ndsLayouts.isEmpty()) NdsLayouts.load(filesDir).let { (p, l) -> ndsLayouts["portrait"] = p.id; ndsLayouts["landscape"] = l.id }
    }

    /**
     * From the pause sheet, for a gamepad (the skin and its layout key are
     * hidden then): sets the core to the orientation's next layout. The
     * hidden overlay stays as it was; turning the phone puts it back in step.
     */
    private fun nextNdsLayout(): ScreenLayout = synchronized(ndsLayouts) {
        loadNdsLayouts()
        val orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val layout = NdsLayouts.next(orientation, ndsLayouts[orientation])
        ndsLayouts[orientation] = layout.id
        Thread {
            NdsLayouts.save(filesDir, orientation, layout)
            RaCommand.send("SET_CORE_OPTION desmume_screens_layout ${layout.value}")
        }.start()
        layout
    }

    /** Called on the engine thread when RetroArch would open its menu. */
    override fun onExternalMenuRequested(): Boolean {
        runOnUiThread {
            // Unfocusable, the sheet never sees BACK: the engine does, and asks again.
            if (dialog?.isShowing == true) dialog?.dismiss() else showPauseSheet()
        }
        return true
    }

    /**
     * Called on the engine thread with gamepad input while the content is
     * paused. The sheet's window cannot take focus (the engine must keep
     * it), so the sheet is driven from here.
     */
    override fun onExternalMenuInput(type: Int, keycode: Int, action: Int, x: Float, y: Float): Boolean {
        if (!sheetOpen) return false
        runOnUiThread { routeToSheet(type, keycode, action, x, y) }
        return true
    }

    private fun routeToSheet(type: Int, keycode: Int, action: Int, x: Float, y: Float) {
        val view = sheetView ?: return
        val d = dialog ?: return
        InputMode.gamepad = true
        if (type == INPUT_KEY) {
            when (keycode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                    if (action == KeyEvent.ACTION_UP) d.onBackPressedDispatcher.onBackPressed()
                KeyEvent.KEYCODE_BUTTON_START -> if (action == KeyEvent.ACTION_UP) d.dismiss()
                KeyEvent.KEYCODE_BUTTON_A -> view.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_DPAD_CENTER))
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    view.dispatchKeyEvent(KeyEvent(action, keycode))
                }
            }
            return
        }
        // Stick or hat: one step per push.
        val key = when {
            x < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            y < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            y > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> 0
        }
        if (key != 0 && key != stickKey) {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        stickKey = key
    }

    private fun flushPlaytime() {
        if (resumedAt == 0L) return
        val path = intent.getStringExtra(GameExtras.PLAYTIME) ?: return
        val delta = (System.currentTimeMillis() - resumedAt) / 1000
        resumedAt = 0L
        runCatching {
            val file = File(path)
            val total = (runCatching { file.readText().trim().toLong() }.getOrDefault(0L)) + delta
            file.parentFile?.mkdirs()
            file.writeText(total.toString())
        }
        if (!isFinishing) resumedAt = System.currentTimeMillis()
    }

    /**
     * The engine turns fast forward off on any toggle it gets while paused (runloop.c, "Don't allow fastmotion
     * while paused"), so the sheet's choice is sent only after the resume has taken: wait until the engine reports
     * it is playing, then toggle if its state still differs. Blocks: call from a background thread. Gives up, and
     * leaves the choice to the next sheet, when that sheet opens first.
     */
    private fun applyFastForward(sheet: Int) {
        repeat(20) {
            if (sheetCount != sheet) return
            if (RaCommand.isPlaying()) {
                val wanted = fastForwardWanted ?: return
                if (sheetCount != sheet) return
                if (fastForwardOn != wanted) RaCommand.send("FAST_FORWARD")
                fastForwardWanted = null
                return
            }
            Thread.sleep(50)
        }
        // Never seen running: drop the choice, so the next sheet shows the engine's own state.
        if (sheetCount == sheet) fastForwardWanted = null
    }

    private fun showPauseSheet() {
        if (dialog?.isShowing == true || isFinishing) return
        val sheet = ++sheetCount
        // A choice from the previous sheet not sent yet is what the tile shows, and this sheet's dismissal sends it.
        val session = PauseSession.fromIntent(intent, sessionStart, fastForwardWanted ?: fastForwardOn)
        // The sheet follows the app's theme; it may have changed since this process started.
        ThemePref.load(this)
        Oe.syncSystem(this)
        val d = object : ComponentDialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) InputMode.gamepad = false
                return super.dispatchTouchEvent(ev)
            }
        }
        val view = ComposeView(d.context).apply {
            setContent {
                ProvideWindowLayout {
                    PauseSheet(
                        session = session,
                        onResume = { d.dismiss() },
                        onQuit = {
                            sheetOpen = false
                            flushPlaytime()
                            d.setOnDismissListener(null)
                            d.dismiss()
                            Thread { RaCommand.send("QUIT") }.start()
                        },
                        onScreens = if (ndsScreens) ({ getString(nextNdsLayout().label) }) else null,
                        onFastForward = { fastForwardWanted = it },
                    )
                }
            }
        }
        d.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.setOnDismissListener {
            sheetOpen = false
            val pending = fastForwardWanted != null
            Thread {
                RaCommand.resume()
                if (pending) applyFastForward(sheet)
            }.start()
        }
        d.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Keep the engine's window focused: unfocused, it stops polling
            // the command interface the sheet talks through.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        dialog = d
        sheetView = view
        d.show()
        // Compose's own view takes key events only while it holds view focus.
        view.post {
            (view.getChildAt(0) ?: view).apply { isFocusableInTouchMode = true; requestFocus() }
        }
        sheetOpen = true
    }

    private companion object {
        /** AINPUT_EVENT_TYPE_KEY, as the engine passes it. */
        const val INPUT_KEY = 1
        const val SKIN_DEBOUNCE_MS = 300L
        const val TAG = "OpenEmuxSkin"
    }
}
