package com.openemux.mobile.game

import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import com.retroarch.browser.retroactivity.RetroActivityFuture
import java.io.File

/**
 * The RetroArch NativeActivity, with the in-game menu swapped for the
 * OpenEmux pause sheet. Runs in its own process (see the manifest).
 */
class GameActivity : RetroActivityFuture() {
    private var resumedAt = 0L
    var sessionStart = 0L
        private set
    private var dialog: ComponentDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStart = System.currentTimeMillis()
        if (intent.hasExtra(GameExtras.LOAD_SLOT)) loadSlotWhenRunning(intent.getIntExtra(GameExtras.LOAD_SLOT, 0))
    }

    /** Opening at a chosen state: wait for the content, then load it. */
    private fun loadSlotWhenRunning(slot: Int) {
        Thread {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (RaCommand.ask("GET_STATUS", 300)?.contains("PLAYING") == true) {
                    val reply = RaCommand.ask("LOAD_STATE_SLOT $slot", 3000)
                    if (reply?.contains("ERROR") == true) runOnUiThread {
                        android.widget.Toast.makeText(this, "Este estado foi salvo com outro core", android.widget.Toast.LENGTH_LONG).show()
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

    /** Called on the engine thread when RetroArch would open its menu. */
    override fun onExternalMenuRequested(): Boolean {
        runOnUiThread {
            // Unfocusable, the sheet never sees BACK: the engine does, and asks again.
            if (dialog?.isShowing == true) dialog?.dismiss() else showPauseSheet()
        }
        return true
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

    private fun showPauseSheet() {
        if (dialog?.isShowing == true || isFinishing) return
        val session = PauseSession.fromIntent(intent, sessionStart)
        val d = ComponentDialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val view = ComposeView(d.context).apply {
            setContent {
                PauseSheet(
                    session = session,
                    onResume = { d.dismiss() },
                    onQuit = {
                        flushPlaytime()
                        d.setOnDismissListener(null)
                        d.dismiss()
                        Thread { RaCommand.send("QUIT") }.start()
                    },
                )
            }
        }
        d.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.setOnDismissListener { Thread { RaCommand.resume() }.start() }
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
        d.show()
    }
}
