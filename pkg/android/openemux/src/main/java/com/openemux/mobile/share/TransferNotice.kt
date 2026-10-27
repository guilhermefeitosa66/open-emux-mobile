package com.openemux.mobile.share

/**
 * What the transfer notification shows for a [TransferState] (spec 07),
 * without the strings: [ShareNotifications] turns it into a notification.
 * Null when there is no transfer to show.
 */
data class TransferNotice(
    val outgoing: Boolean,
    val peer: String,
    /** Before the first byte: the sender waits for the answer. */
    val waiting: Boolean,
    val games: Int,
    val done: Long,
    val total: Long,
) {
    /** 0..100; null while the size is not known yet (an indeterminate bar). */
    val percent: Int? get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null

    companion object {
        fun of(state: TransferState): TransferNotice? = when (state) {
            is TransferState.Waiting -> TransferNotice(true, state.peer, true, 0, 0, 0)
            is TransferState.Running -> TransferNotice(
                state.outgoing, state.peer, false, state.games,
                state.progress?.doneBytes ?: 0, state.progress?.totalBytes ?: 0,
            )
            else -> null
        }
    }
}

/** At most one update per [intervalMs]; a forced one (the first, a change of phase) always goes. */
class UpdateThrottle(private val intervalMs: Long = INTERVAL_MS) {
    private var last: Long? = null

    fun allow(now: Long, force: Boolean = false): Boolean {
        val previous = last
        if (!force && previous != null && now - previous < intervalMs) return false
        last = now
        return true
    }

    companion object {
        const val INTERVAL_MS = 500L
    }
}
