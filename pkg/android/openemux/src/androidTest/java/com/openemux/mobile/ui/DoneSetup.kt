package com.openemux.mobile.ui

import android.os.Build
import com.openemux.mobile.OpenEmuxApp
import com.openemux.mobile.data.Setup
import java.io.File

/**
 * Lets a UI test past the setup screen without downloading the cores: the
 * stamp [Setup] checks is written as a finished setup writes it, and the app
 * gets a [Setup] that reads it. Nothing launches a game in these tests.
 */
object DoneSetup {
    fun install(app: OpenEmuxApp) {
        if (app.setup.state.value is Setup.State.Done) return
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: return
        File(app.library.paths.internal, "setup.done").writeText("1/$abi")
        // The app's setter is private; the test swaps the instance the app made at start for one that sees the stamp.
        OpenEmuxApp::class.java.getDeclaredField("setup").apply { isAccessible = true }.set(app, Setup(app.library.paths))
    }
}
