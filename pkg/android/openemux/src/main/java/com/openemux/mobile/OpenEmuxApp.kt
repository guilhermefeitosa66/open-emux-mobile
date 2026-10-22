package com.openemux.mobile

import android.app.Application
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Importer
import com.openemux.mobile.data.Library
import com.openemux.mobile.launch.GameLauncher

class OpenEmuxApp : Application() {
    lateinit var library: Library
        private set
    lateinit var covers: Covers
        private set
    lateinit var launcher: GameLauncher
        private set
    lateinit var importer: Importer
        private set

    override fun onCreate() {
        super.onCreate()
        // The :game process only needs the RetroArch side.
        if (getProcessName().endsWith(":game")) return
        library = Library(this)
        covers = Covers(this)
        launcher = GameLauncher(this, library, covers)
        importer = Importer(this, library)
    }
}
