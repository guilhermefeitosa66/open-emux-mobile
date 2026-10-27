package com.openemux.mobile.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Whether Share nearby can run on this install. */
sealed interface ShareAvailability {
    data object Available : ShareAvailability
    /** `reason` is `tls13` (Android 9 and below) or `keystore` (no identity could be made). */
    data class Unavailable(val reason: String) : ShareAvailability
}

/**
 * The share layer for the screens (#591): availability, identity, the server
 * and discovery started and stopped together, the peers, incoming offers and
 * a client for outgoing ones, as [StateFlow]s. A plain class, not an Android
 * Service: nothing here runs until [start] is called, and nothing calls it at
 * app launch.
 */
class ShareService(
    private val sdkInt: Int,
    private val identityStore: () -> IdentityStore,
    private val newDiscovery: (ownId: String, onPeers: (List<Peer>) -> Unit) -> Discovery,
    private val newServer: (DeviceIdentity) -> ShareServer = { ShareServer(it) },
) {
    private val _identity = MutableStateFlow<DeviceIdentity?>(null)
    val identity: StateFlow<DeviceIdentity?> = _identity.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    /** The revealed offer waiting for the user's answer, if any. */
    private val _incoming = MutableStateFlow<IncomingOffer?>(null)
    val incoming: StateFlow<IncomingOffer?> = _incoming.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var server: ShareServer? = null
    private var discovery: Discovery? = null
    private val store by lazy(identityStore)

    /** Checked before TLS or the Keystore is touched: TLS 1.3 needs Android 10. */
    fun availability(): ShareAvailability {
        if (sdkInt < MIN_SDK) return ShareAvailability.Unavailable(REASON_TLS13)
        return try {
            loadIdentity()
            ShareAvailability.Available
        } catch (_: ShareError) {
            ShareAvailability.Unavailable(REASON_KEYSTORE)
        }
    }

    /** Listens and becomes discoverable; `unavailable` when this install cannot. */
    @Synchronized
    fun start() {
        if (server != null) return
        when (val availability = availability()) {
            is ShareAvailability.Unavailable -> throw ShareError(ErrorCode.UNAVAILABLE, availability.reason, mapOf("reason" to availability.reason))
            ShareAvailability.Available -> Unit
        }
        val identity = loadIdentity()
        val server = newServer(identity)
        server.listener = object : IncomingOfferListener {
            override fun onOffer(offer: IncomingOffer) {
                _incoming.value = offer
            }

            override fun onCancelled(offerId: String) {
                if (_incoming.value?.offerId == offerId) _incoming.value = null
            }
        }
        val port = server.start()
        val discovery = newDiscovery(identity.id) { _peers.value = it }
        discovery.start(identity.announcement(port))
        _discoveryState.value = discovery.state.value
        this.server = server
        this.discovery = discovery
        _running.value = true
    }

    @Synchronized
    fun stop() {
        discovery?.stop()
        server?.stop()
        discovery = null
        server = null
        _discoveryState.value = DiscoveryState.Idle
        _incoming.value = null
        _running.value = false
    }

    fun accept(offerId: String, answers: List<ItemAnswer>) {
        requireServer().accept(offerId, answers)
        clearIncoming(offerId)
    }

    fun decline(offerId: String) {
        requireServer().decline(offerId)
        clearIncoming(offerId)
    }

    fun cancel(offerId: String) {
        requireServer().cancel(offerId)
        clearIncoming(offerId)
    }

    /** Renames this device; takes effect on the next [start]. */
    fun setName(name: String): DeviceIdentity = store.setName(name).also { _identity.value = it }

    /** A client for [peer], pinned to its fingerprint. */
    fun client(peer: Peer): ShareClient =
        ShareClient(peer.addresses.firstOrNull() ?: throw ShareError(ErrorCode.UNREACHABLE, "no address"), peer.port, peer.fp)

    /** A client for a typed or scanned address; pinned when it carries a fingerprint. */
    fun client(address: ShareAddress): ShareClient = ShareClient(address.host, address.port, address.fp)

    /**
     * Fills a manual peer from the address's `GET /info` and lists it until
     * [removeManualPeer]. The fingerprint is the one seen in TLS (trust on
     * first use when the address carried none).
     */
    fun addManualPeer(address: ShareAddress): Peer {
        val client = client(address)
        val info = PeerInfo.parse(client.request("GET", "/info").second)
        val fp = client.peerFingerprint!!
        val announcement = Announcement(info.id, info.name, info.platform, info.versions, address.port, fp)
        val list = currentPeers()
        list.seen(PeerPath.MANUAL, announcement, address.host)
        return list.peers().first { it.id == info.id && it.fp == fp }
    }

    fun removeManualPeer(peer: Peer) = currentPeers().gone(PeerPath.MANUAL, peer.id, peer.fp)

    private fun currentPeers(): PeerList =
        discovery?.peers ?: throw ShareError(ErrorCode.UNAVAILABLE, "not started", mapOf("reason" to "stopped"))

    private fun requireServer(): ShareServer =
        server ?: throw ShareError(ErrorCode.UNAVAILABLE, "not started", mapOf("reason" to "stopped"))

    private fun clearIncoming(offerId: String) {
        if (_incoming.value?.offerId == offerId) _incoming.value = null
    }

    private fun loadIdentity(): DeviceIdentity = store.load().also { _identity.value = it }

    companion object {
        const val MIN_SDK = 29
        const val REASON_TLS13 = "tls13"
        const val REASON_KEYSTORE = "keystore"

        /** The service wired to the real Keystore, NSD, multicast and Wi-Fi lock. */
        fun create(context: Context): ShareService {
            val app = context.applicationContext
            return ShareService(
                Build.VERSION.SDK_INT,
                { IdentityStore(File(app.filesDir, "share"), AndroidKeyStoreBackend()) { DeviceInfo.read(app) } },
                { ownId, onPeers ->
                    Discovery(
                        AndroidMulticastLock(app.getSystemService(WifiManager::class.java)),
                        listOf(NsdTransport(app.getSystemService(NsdManager::class.java)), BeaconTransport()),
                        PeerList(ownId, onChange = onPeers),
                    )
                },
            ).also { Log.d(TAG, "share service created (sdk ${Build.VERSION.SDK_INT})") }
        }
    }
}
