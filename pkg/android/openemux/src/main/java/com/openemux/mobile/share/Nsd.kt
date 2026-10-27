package com.openemux.mobile.share

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

/** The pure half of mDNS / DNS-SD (spec §4), shared with the tests. */
object NsdTxt {
    /** `<name> (<id[0:6]>)`, cut to 63 bytes of UTF-8 without splitting a character. */
    fun instanceName(name: String, id: String): String = cutUtf8("$name (${id.take(6)})", 63)

    fun attributes(announcement: Announcement): Map<String, String> = mapOf(
        "id" to announcement.id,
        // Android refuses a TXT entry of 255 bytes or more (key, `=` and value): 64 four-byte characters would be.
        "name" to cutUtf8(announcement.name, MAX_TXT_ENTRY_BYTES - "name=".length),
        "pf" to announcement.platform,
        "v" to announcement.versions.max.toString(),
        "vmin" to announcement.versions.min.toString(),
        "fp" to announcement.fp,
    )

    /** A resolved record, or null when it lacks a valid `id`, `fp` or port. Unknown keys are ignored. */
    fun parse(attributes: Map<String, ByteArray?>, port: Int): Announcement? {
        val txt = attributes.mapValues { (_, value) -> value?.let(::utf8OrNull) }
        val id = txt["id"]
        val fp = txt["fp"]
        val checkedPort = toPort(port) ?: return null
        if (!isDeviceId(id) || !isFingerprint(fp)) return null
        return Announcement.of(id!!, txt["name"], txt["pf"], txt["v"], txt["vmin"], checkedPort, fp!!)
    }

    private const val MAX_TXT_ENTRY_BYTES = 254

    internal fun cutUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    private fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}

/**
 * Registration and browsing of `_openemux._tcp` through [NsdManager]. Records
 * are resolved with `registerServiceInfoCallback` on Android 14+, and through a
 * serial queue around `resolveService` below it (two at once fail with
 * `FAILURE_ALREADY_ACTIVE`). A lost service drops the mDNS path of its peer.
 */
class NsdTransport(private val nsd: NsdManager) : DiscoveryTransport {
    override val path = PeerPath.MDNS

    private var peers: PeerList? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    /** Service name to the peer it resolved to, so a loss can name the peer. */
    private val resolved = mutableMapOf<String, Pair<String, String>>()
    private val callbacks = mutableMapOf<String, Any>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val executor by lazy { Executors.newSingleThreadExecutor { Thread(it, "share-nsd").apply { isDaemon = true } } }

    @Synchronized
    override fun start(announcement: Announcement, peers: PeerList) {
        if (registration != null) return
        this.peers = peers
        val info = NsdServiceInfo().apply {
            serviceName = NsdTxt.instanceName(announcement.name, announcement.id)
            serviceType = ShareProtocol.SERVICE_TYPE
            port = announcement.port
            NsdTxt.attributes(announcement).forEach { (key, value) -> setAttribute(key, value) }
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "nsd: registered ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "nsd: registration failed ($error)") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "nsd: unregistration failed ($error)") }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "nsd: discovery failed ($error)") }
            override fun onStopDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "nsd: stop discovery failed ($error)") }
            override fun onServiceFound(info: NsdServiceInfo) = found(info)
            override fun onServiceLost(info: NsdServiceInfo) = lost(info.serviceName)
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        this.registration = registration
        nsd.discoverServices(ShareProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        this.discovery = discovery
    }

    @Synchronized
    override fun stop() {
        registration?.let { safely { nsd.unregisterService(it) } }
        discovery?.let { safely { nsd.stopServiceDiscovery(it) } }
        if (Build.VERSION.SDK_INT >= 34) callbacks.values.forEach { safely { unregisterCallback(it) } }
        registration = null
        discovery = null
        callbacks.clear()
        resolved.clear()
        queue.clear()
        resolving = false
        peers = null
    }

    @Synchronized
    private fun found(info: NsdServiceInfo) {
        if (peers == null) return
        if (Build.VERSION.SDK_INT >= 34) {
            if (info.serviceName !in callbacks) callbacks[info.serviceName] = registerCallback(info)
        } else {
            queue.addLast(info)
            resolveNext()
        }
    }

    @Synchronized
    private fun lost(serviceName: String) {
        if (Build.VERSION.SDK_INT >= 34) callbacks.remove(serviceName)?.let { safely { unregisterCallback(it) } }
        val (id, fp) = resolved.remove(serviceName) ?: return
        peers?.gone(PeerPath.MDNS, id, fp)
    }

    @Synchronized
    private fun update(serviceName: String, attributes: Map<String, ByteArray?>, port: Int, addresses: List<String>) {
        val peers = peers ?: return
        val announcement = NsdTxt.parse(attributes, port) ?: return
        val previous = resolved.put(serviceName, announcement.id to announcement.fp)
        if (previous != null && previous != (announcement.id to announcement.fp)) peers.gone(PeerPath.MDNS, previous.first, previous.second)
        if (addresses.isEmpty()) peers.seen(PeerPath.MDNS, announcement)
        addresses.forEach { peers.seen(PeerPath.MDNS, announcement, it) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                update(info.serviceName, info.attributes, info.port, listOfNotNull(info.host?.hostAddress))
                done()
            }

            private fun done() = synchronized(this@NsdTransport) {
                resolving = false
                resolveNext()
            }
        })
    }

    @RequiresApi(34)
    private fun registerCallback(info: NsdServiceInfo): Any {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(error: Int) { Log.w(TAG, "nsd: resolve failed ($error)") }
            override fun onServiceUpdated(info: NsdServiceInfo) =
                update(info.serviceName, info.attributes, info.port, info.hostAddresses.mapNotNull { it.hostAddress })
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(info, executor, callback)
        return callback
    }

    @RequiresApi(34)
    private fun unregisterCallback(callback: Any) = nsd.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback)

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            // A listener NsdManager no longer knows (a failed start): nothing to undo.
            Log.d(TAG, "nsd: ${e.message}")
        }
    }
}
