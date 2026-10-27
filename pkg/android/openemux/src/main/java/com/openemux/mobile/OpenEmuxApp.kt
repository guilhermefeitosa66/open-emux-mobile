package com.openemux.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.openemux.mobile.data.AchievementsStore
import com.openemux.mobile.data.BackupManager
import com.openemux.mobile.data.BackupRoots
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Importer
import com.openemux.mobile.data.Labels
import com.openemux.mobile.data.Library
import com.openemux.mobile.data.Paths
import com.openemux.mobile.data.Setup
import com.openemux.mobile.data.UpdateStore
import com.openemux.mobile.launch.GameLauncher
import android.content.Intent
import androidx.core.content.ContextCompat
import com.openemux.mobile.share.HandlerScheduler
import com.openemux.mobile.share.LibraryShareAdapter
import com.openemux.mobile.share.ServiceShareLayer
import com.openemux.mobile.share.ShareCoordinator
import com.openemux.mobile.share.ShareNotifications
import com.openemux.mobile.share.ShareService
import com.openemux.mobile.share.TransferService
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
    lateinit var updates: UpdateStore
        private set
    lateinit var backup: BackupManager
        private set

    /** Share nearby. Created on first use; nothing listens until a screen starts it. */
    val share: ShareService by lazy { ShareService.create(this) }

    lateinit var shareNotifications: ShareNotifications
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visible = false
    @Volatile private var coordinator: ShareCoordinator? = null

    /** The screens' side of Share nearby; created by the first screen or link that needs it. */
    val shareCoordinator: ShareCoordinator
        get() = coordinator ?: synchronized(this) {
            coordinator ?: ShareCoordinator(
                ServiceShareLayer(this, share, LibraryShareAdapter(library, covers)),
                HandlerScheduler(),
                shareNotifications,
                { ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java)) },
                appScope,
                // Start, stop and the network calls run one at a time, in order.
                Dispatchers.IO.limitedParallelism(1),
                // Hashing the manifest runs beside them, so it never holds up an offer.
                Dispatchers.IO,
            ).also {
                it.setForeground(visible)
                coordinator = it
            }
        }

    /** The main activity started or stopped: offers and results go to the screen or to notifications. */
    fun setForeground(foreground: Boolean) {
        visible = foreground
        coordinator?.setForeground(foreground)
    }

    override fun onCreate() {
        super.onCreate()
        // The :game process only needs the RetroArch side.
        if (getProcessName().endsWith(":game")) return
        // A restore the process died in is finished or undone before anything reads the data.
        Paths(this).let { BackupRoots(it.base, it.internal).recover() }
        library = Library(this)
        covers = Covers(this, library.paths)
        labels = Labels(library.paths)
        achievements = AchievementsStore(this, File(library.paths.launch, "current/retroarch.cfg"))
        launcher = GameLauncher(this, library, covers, labels, achievements)
        importer = Importer(this, library)
        setup = Setup(library.paths)
        backup = BackupManager(this, library)
        shareNotifications = ShareNotifications(this).also { it.createChannels() }
        updates = UpdateStore.create(this)
        updates.checkOnce(appScope)
        appScope.launch {
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
