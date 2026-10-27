package com.openemux.mobile.share

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.openemux.mobile.OpenEmuxApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a transfer alive while it runs (spec 07): a `dataSync` foreground
 * service with the progress notification, plus a partial wake lock and a
 * Wi-Fi lock so the screen going off stops neither the CPU nor the radio.
 * Started only from a visible screen's Send or Accept; stops itself when the
 * transfer ends. Not sticky: a killed transfer is not resumed (the receiver
 * rolls its staged files back).
 */
class TransferService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val throttle = UpdateThrottle()

    private val coordinator get() = (application as OpenEmuxApp).shareCoordinator
    private val notifications get() = (application as OpenEmuxApp).shareNotifications

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            coordinator.cancel()
            if (watching == null) stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, ShareNotifications.ID_PROGRESS, notifications.progress(coordinator.transfer.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        acquireLocks()
        if (watching == null) watching = scope.launch {
            var phase: Any? = null
            coordinator.transfer.collect { state ->
                if (!state.busy) {
                    stopSelf()
                    return@collect
                }
                val changed = state::class != phase
                phase = state::class
                if (throttle.allow(SystemClock.elapsedRealtime(), force = changed)) {
                    try {
                        NotificationManagerCompat.from(this@TransferService).notify(ShareNotifications.ID_PROGRESS, notifications.progress(state))
                    } catch (_: SecurityException) {
                        // No notification permission: the service still runs, its notification is not shown.
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                .apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT_MS) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            // Low latency only holds while the screen is on; high-perf keeps the radio up with it off, where it still works.
            val mode = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(mode, WAKE_TAG)
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.openemux.mobile.share.CANCEL"
        private const val WAKE_TAG = "OpenEmux:share"
        /** A safety net: no library transfer takes this long. */
        private const val WAKE_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }
}
