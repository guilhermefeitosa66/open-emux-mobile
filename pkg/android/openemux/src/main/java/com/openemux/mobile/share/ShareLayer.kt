package com.openemux.mobile.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.openemux.mobile.data.Rom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * What the screens' [ShareCoordinator] uses of the share layer: [ShareService]
 * plus the library and the network around it, behind one port so the
 * coordinator runs in JVM tests. Every call but the flows may block.
 */
interface ShareLayer {
    val peers: StateFlow<List<Peer>>
    val incoming: StateFlow<IncomingOffer?>
    /** On a Wi-Fi or Ethernet network, where the other devices can be. */
    val lan: StateFlow<Boolean>

    fun availability(): ShareAvailability
    /** Listens and searches; the discovery warnings (such as a refused multicast lock). */
    fun start(): List<String>
    fun stop()

    fun deviceName(): String?
    fun defaultName(): String
    fun setName(name: String): String
    /** This device's address while it listens: what the QR code and Connect by Address show. */
    fun ownAddress(): ShareAddress?

    fun buildManifest(roms: List<Rom>, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean): BuiltManifest
    /** Offers [built] to [peer]; the returned call cancels it. */
    fun send(peer: Peer, built: BuiltManifest, onCode: (String) -> Unit, onProgress: (TransferProgress) -> Unit, onDone: (SendResult) -> Unit): () -> Unit
    fun plan(offer: IncomingOffer, overrides: Map<String, Policy>): ReceivePlan
    /** Accepts [offerId] with [plan] and receives; the returned call cancels it. */
    fun accept(offerId: String, plan: ReceivePlan, onProgress: (TransferProgress) -> Unit, onDone: (ReceiveResult) -> Unit): () -> Unit
    fun decline(offerId: String)
    fun addManualPeer(address: ShareAddress): Peer
}

/** [Scheduler] on the main thread's looper, timed by the uptime clock that does not jump. */
class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {
    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

/** [ShareLayer] over the app's [ShareService] and library. */
class ServiceShareLayer(
    private val context: Context,
    private val service: ShareService,
    private val library: ShareLibrary,
) : ShareLayer {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _lan = MutableStateFlow(onLan(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _lan.value = onLan(caps)
            }

            override fun onLost(network: Network) {
                _lan.value = false
            }
        })
    }

    override val peers get() = service.peers
    override val incoming get() = service.incoming
    override val lan: StateFlow<Boolean> = _lan.asStateFlow()

    override fun availability() = service.availability()

    override fun start(): List<String> {
        service.start()
        return (service.discoveryState.value as? DiscoveryState.Running)?.warnings.orEmpty()
    }

    override fun stop() = service.stop()

    override fun deviceName(): String? {
        if (service.identity.value == null) service.availability()
        return service.identity.value?.name
    }

    override fun defaultName() = DeviceInfo.read(context).name

    override fun setName(name: String) = service.setName(name).name

    override fun ownAddress(): ShareAddress? {
        val port = service.port ?: return null
        val links: LinkProperties? = connectivity.getLinkProperties(connectivity.activeNetwork)
        val active = links?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        val host = (active ?: hotspotAddress())?.hostAddress ?: return null
        return ShareAddress(host, port, service.identity.value?.fingerprint)
    }

    override fun buildManifest(roms: List<Rom>, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean) =
        service.buildManifest(library, roms, onProgress, cancelled)

    override fun send(peer: Peer, built: BuiltManifest, onCode: (String) -> Unit, onProgress: (TransferProgress) -> Unit, onDone: (SendResult) -> Unit): () -> Unit {
        val transfer = service.send(service.client(peer), built, onCode, onProgress, onDone)
        return transfer::cancel
    }

    override fun plan(offer: IncomingOffer, overrides: Map<String, Policy>) = service.prepareReceive(offer, library, overrides = overrides)

    override fun accept(offerId: String, plan: ReceivePlan, onProgress: (TransferProgress) -> Unit, onDone: (ReceiveResult) -> Unit): () -> Unit {
        service.accept(offerId, plan, library, onProgress, onDone)
        return { service.cancel(offerId) }
    }

    override fun decline(offerId: String) = service.decline(offerId)

    override fun addManualPeer(address: ShareAddress) = service.addManualPeer(address)

    /** Wi-Fi or Ethernet, or this phone's own hotspot (its network is then mobile data). */
    private fun onLan(caps: NetworkCapabilities?) = caps != null &&
        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ||
        hotspotAddress() != null

    /** A private IPv4 address on a local interface that is not the mobile data one: the hotspot's, when it is on. */
    private fun hotspotAddress(): Inet4Address? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && MOBILE_INTERFACES.none { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as Inet4Address?
    } catch (_: SocketException) {
        null
    }

    private companion object {
        val MOBILE_INTERFACES = listOf("rmnet", "ccmni", "tun", "ppp", "dummy", "v4-")
    }
}
