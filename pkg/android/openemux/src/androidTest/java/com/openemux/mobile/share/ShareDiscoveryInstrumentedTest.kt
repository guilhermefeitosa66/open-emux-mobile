package com.openemux.mobile.share

import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * On a device: the identity in the real Keystore, and discovery holding the
 * multicast lock exactly while it runs. `adb logcat -s OpenEmuxShare` shows
 * "multicast lock acquired" then "multicast lock released".
 */
@RunWith(AndroidJUnit4::class)
class ShareDiscoveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun identityAndDiscoveryOnTheDevice() {
        val dir = File(context.cacheDir, "share-test").apply { deleteRecursively() }
        val backend = AndroidKeyStoreBackend()
        val identity = IdentityStore(dir, backend) { DeviceInfo.read(context) }.load()
        try {
            val sha = MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).joinToString("") { "%02x".format(it) }
            assertEquals(sha, identity.fingerprint)
            assertEquals(identity.id, IdentityStore.cnId(identity.certificate))

            val server = ShareServer(identity, preferredPort = 0)
            val port = server.start()
            val lock = AndroidMulticastLock(context.getSystemService(WifiManager::class.java))
            val discovery = Discovery(
                lock,
                listOf(NsdTransport(context.getSystemService(NsdManager::class.java)), BeaconTransport()),
                PeerList(identity.id),
            )
            discovery.start(identity.announcement(port))
            try {
                assertTrue(discovery.state.value is DiscoveryState.Running)
                assertTrue(lock.isHeld)
                Thread.sleep(3_000)
                // The device's own server answers through the pinned client.
                assertEquals(identity.id, ShareClient("127.0.0.1", port, identity.fingerprint).info().id)
            } finally {
                discovery.stop()
                server.stop()
            }
            assertFalse(lock.isHeld)
            assertEquals(DiscoveryState.Idle, discovery.state.value)
        } finally {
            backend.delete(IdentityStore.ALIAS)
            dir.deleteRecursively()
        }
    }
}
