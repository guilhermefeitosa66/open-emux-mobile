package com.openemux.mobile.share

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    /** Running, with warning codes such as `multicast_lock_refused`. */
    data class Running(val warnings: List<String> = emptyList()) : DiscoveryState
    data class Failed(val code: String) : DiscoveryState
}

/**
 * Runs every discovery path under the multicast lock and merges what they
 * find into one [PeerList]. The lock is held exactly while discovery runs:
 * taken before the transports start, released by [stop] and by every failure
 * of [start]. A refused lock is a warning, not a failure: mDNS may still work.
 */
class Discovery(
    private val lock: MulticastLockHandle,
    private val transports: List<DiscoveryTransport>,
    val peers: PeerList,
    private val tickerFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "share-expiry").apply { isDaemon = true } }
    },
) {
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var started = emptyList<DiscoveryTransport>()
    private var ticker: ScheduledExecutorService? = null

    @Synchronized
    fun start(announcement: Announcement) {
        if (_state.value is DiscoveryState.Running) return
        val warnings = mutableListOf<String>()
        try {
            lock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock refused", e)
            warnings += WARNING_LOCK_REFUSED
        }
        val running = mutableListOf<DiscoveryTransport>()
        try {
            for (transport in transports) {
                transport.start(announcement, peers)
                running += transport
            }
            ticker = tickerFactory().also {
                it.scheduleWithFixedDelay({ peers.expire() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed to start", e)
            running.forEach(::stopQuietly)
            releaseLock()
            _state.value = DiscoveryState.Failed(ErrorCode.INTERNAL)
            return
        }
        started = running
        _state.value = DiscoveryState.Running(warnings)
    }

    /** Stops every path (the beacon says `bye`) and releases the lock. Safe to call twice. */
    @Synchronized
    fun stop() {
        ticker?.shutdownNow()
        ticker = null
        started.forEach(::stopQuietly)
        started = emptyList()
        releaseLock()
        peers.clearDiscovered()
        _state.value = DiscoveryState.Idle
    }

    private fun stopQuietly(transport: DiscoveryTransport) {
        try {
            transport.stop()
        } catch (e: Exception) {
            Log.w(TAG, "discovery: ${transport.path} failed to stop", e)
        }
    }

    private fun releaseLock() {
        try {
            lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock release failed", e)
        }
    }

    companion object {
        const val WARNING_LOCK_REFUSED = "multicast_lock_refused"
        private const val TICK_MS = 1_000L
    }
}
