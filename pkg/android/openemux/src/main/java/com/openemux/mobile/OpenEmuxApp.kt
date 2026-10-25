package com.openemux.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.openemux.mobile.data.AchievementsStore
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Importer
import com.openemux.mobile.data.Labels
import com.openemux.mobile.data.Library
import com.openemux.mobile.data.Setup
import com.openemux.mobile.launch.GameLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class OpenEmuxApp : Application() {
    lateinit var library: Library
        private set
    lateinit var covers: Covers
        private set
    lateinit var labels: Labels
        private set
    lateinit var launcher: GameLauncher
        private set
    lateinit var importer: Importer
        private set
    lateinit var setup: Setup
        private set
    lateinit var achievements: AchievementsStore
        private set

    override fun onCreate() {
        super.onCreate()
        // The :game process only needs the RetroArch side.
        if (getProcessName().endsWith(":game")) return
        library = Library(this)
        covers = Covers(this)
        labels = Labels(library.paths)
        achievements = AchievementsStore(this, File(library.paths.launch, "current/retroarch.cfg"))
        launcher = GameLauncher(this, library, covers, labels, achievements)
        importer = Importer(this, library)
        setup = Setup(library.paths)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(library.roms, artworkRetry) { roms, _ -> roms }.collectLatest { roms ->
                coroutineScope {
                    launch { covers.prefetch(roms) }
                    launch { labels.sync(roms) }
                }
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = retryArtwork()
        })
    }

    private val artworkRetry = MutableStateFlow(0)

    /** Resumes a paused artwork sync (the app came back to the foreground). */
    fun retryArtwork() {
        if (!::labels.isInitialized) return
        if (labels.progress.value.paused != null || covers.progress.value.paused != null) artworkRetry.value++
    }
}
