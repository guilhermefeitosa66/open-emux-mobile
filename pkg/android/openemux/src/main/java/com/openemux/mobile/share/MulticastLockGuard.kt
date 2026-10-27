package com.openemux.mobile.share

import android.net.wifi.WifiManager
import android.util.Log

/** The Wi-Fi multicast lock, behind an interface so the tests can make it refuse. */
interface MulticastLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Without this lock the Wi-Fi driver filters multicast out, and the beacon and
 * mDNS hear nothing. One non-reference-counted lock: however many times it is
 * acquired, one release frees it. Acquire and release are logged at info level
 * (`adb logcat -s OpenEmuxShare`).
 */
class AndroidMulticastLock(wifi: WifiManager) : MulticastLockHandle {
    private val lock = wifi.createMulticastLock("openemux-share").apply { setReferenceCounted(false) }

    override val isHeld: Boolean get() = lock.isHeld

    override fun acquire() {
        lock.acquire()
        Log.i(TAG, "multicast lock acquired")
    }

    override fun release() {
        if (!lock.isHeld) return
        lock.release()
        Log.i(TAG, "multicast lock released")
    }
}
