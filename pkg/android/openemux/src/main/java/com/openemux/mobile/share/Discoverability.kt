package com.openemux.mobile.share

/** Something scheduled that can still be called off. */
fun interface Cancellable {
    fun cancel()
}

/** Time and delayed work, injected so the rules below run on a fake clock in tests. */
interface Scheduler {
    fun now(): Long
    fun schedule(delayMs: Long, block: () -> Unit): Cancellable
}

/**
 * When this device is discoverable (spec, assumptions of #591): from the first
 * share screen opened until [graceMs] after the last one closes, and never
 * stopped while a transfer runs (the grace counts again from its end).
 * [onStart] and [onStop] are called with the lock held; they hand the work
 * off rather than doing it there.
 */
class Discoverability(
    private val scheduler: Scheduler,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val graceMs: Long = GRACE_MS,
) {
    private var screens = 0
    private var transferring = false
    private var pendingStop: Cancellable? = null

    var running = false
        private set

    @Synchronized
    fun open() {
        screens++
        cancelStop()
        if (!running) {
            running = true
            onStart()
        }
    }

    @Synchronized
    fun close() {
        if (screens == 0) return
        screens--
        scheduleStop()
    }

    @Synchronized
    fun transferStarted() {
        transferring = true
        cancelStop()
    }

    @Synchronized
    fun transferEnded() {
        if (!transferring) return
        transferring = false
        scheduleStop()
    }

    /** The layer could not start: the next [open] tries again. */
    @Synchronized
    fun failed() {
        running = false
        cancelStop()
    }

    private fun cancelStop() {
        pendingStop?.cancel()
        pendingStop = null
    }

    private fun scheduleStop() {
        if (screens > 0 || transferring || !running) return
        cancelStop()
        pendingStop = scheduler.schedule(graceMs) { stopNow() }
    }

    @Synchronized
    private fun stopNow() {
        pendingStop = null
        if (screens > 0 || transferring || !running) return
        running = false
        onStop()
    }

    companion object {
        const val GRACE_MS = 30_000L
    }
}

/**
 * The "No Devices Found" rule (spec 04): [timeoutMs] without a compatible
 * peer since discovery started, or since the last compatible one left. Any
 * compatible peer resets it; an incompatible one does not count.
 */
class NothingFound(private val timeoutMs: Long = TIMEOUT_MS) {
    private var since: Long? = null

    /** Discovery started at [now] (with nobody found yet). */
    fun started(now: Long) {
        since = now
    }

    fun stopped() {
        since = null
    }

    fun peers(peers: List<Peer>, now: Long) {
        since = if (peers.any { it.compatible }) null else since ?: now
    }

    fun isNothingFound(now: Long): Boolean = since?.let { now - it >= timeoutMs } ?: false

    /** How long until [isNothingFound] turns true; null when it cannot (a peer is there, or nothing runs). */
    fun remaining(now: Long): Long? = since?.let { (it + timeoutMs - now).coerceAtLeast(0) }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
