package com.openemux.mobile.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.openemux.mobile.data.Rom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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
    private val transfers = mutableMapOf<String, IncomingTransfer>()

    /** The port the server listens on while running (the QR code carries it). */
    val port: Int? get() = server?.port
    private var discovery: Discovery? = null
    private val store by lazy(identityStore)

    /** Checked before TLS or the Keystore is touched: TLS 1.3 needs Android 10. */
    fun availability(): ShareAvailability = try {
        availableIdentity()
        ShareAvailability.Available
    } catch (e: ShareError) {
        ShareAvailability.Unavailable(e.details["reason"] as? String ?: REASON_KEYSTORE)
    }

    /** The identity, or `unavailable` with the reason: the SDK first, then the Keystore. */
    private fun availableIdentity(): DeviceIdentity {
        if (sdkInt < MIN_SDK) throw unavailable(REASON_TLS13)
        return try {
            loadIdentity()
        } catch (_: ShareError) {
            throw unavailable(REASON_KEYSTORE)
        }
    }

    private fun unavailable(reason: String) = ShareError(ErrorCode.UNAVAILABLE, reason, mapOf("reason" to reason))

    /** Listens and becomes discoverable; `unavailable` when this install cannot. */
    @Synchronized
    fun start() {
        if (server != null) return
        val identity = availableIdentity()
        val server = newServer(identity)
        server.listener = object : IncomingOfferListener {
            override fun onOffer(offer: IncomingOffer) {
                _incoming.value = offer
            }

            override fun onCancelled(offerId: String) {
                if (_incoming.value?.offerId == offerId) _incoming.value = null
                synchronized(transfers) { transfers.remove(offerId) }?.onRemoteCancel()
            }
        }
        server.fileReceiver = FileReceiver { offer, item, body ->
            val transfer = synchronized(transfers) { transfers[offer.offerId] }
                ?: throw ShareError(ErrorCode.NOT_IMPLEMENTED, "no transfer for this offer")
            transfer.handlePut(item.id, body, item.size)
        }
        val port = server.start()
        val discovery = try {
            newDiscovery(identity.id) { _peers.value = it }.also { it.start(identity.announcement(port)) }
        } catch (e: Exception) {
            // Not kept anywhere yet: a server left listening here would never be stopped.
            server.stop()
            throw e
        }
        _discoveryState.value = discovery.state.value
        this.server = server
        this.discovery = discovery
        _running.value = true
    }

    @Synchronized
    fun stop() {
        synchronized(transfers) { transfers.values.toList().also { transfers.clear() } }.forEach { it.cancel() }
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

    /** The receiver's decisions for [offer] in [library]; nothing is answered or written. */
    fun prepareReceive(
        offer: IncomingOffer,
        library: ShareLibrary,
        policies: Map<String, Policy> = emptyMap(),
        overrides: Map<String, Policy> = emptyMap(),
        unchecked: Set<String> = emptySet(),
    ): ReceivePlan = planReceive(offer.manifest, library.paths, CoreIdentities(library.paths), policies, overrides, unchecked)

    /**
     * Accepts [offerId] with [plan] and starts receiving into [library]. When a
     * filesystem lacks the room (with the reserve), it refuses with
     * `too_large`, its `stores` detail listing `{root, needed, free}`, and the
     * offer stays unanswered. [onDone] hears how it ended.
     */
    fun accept(
        offerId: String,
        plan: ReceivePlan,
        library: ShareLibrary,
        onProgress: (TransferProgress) -> Unit = {},
        onDone: (ReceiveResult) -> Unit = {},
    ): IncomingTransfer {
        val server = requireServer()
        val checks = plan.spaceCheck()
        if (!plan.okToAccept(checks)) {
            val stores = JSONArray(checks.filter { !it.ok }.map {
                JSONObject().put("root", it.root.path).put("needed", it.needed).put("free", it.free)
            })
            throw ShareError(ErrorCode.TOO_LARGE, "not enough free space", mapOf("stores" to stores))
        }
        val transfer = IncomingTransfer(offerId, plan, library, onProgress = onProgress)
        transfer.onAbort = { id, inUpload ->
            synchronized(transfers) { transfers.remove(id) }
            server.cancel(id, closeUploads = !inUpload)
        }
        transfer.onDone = { result ->
            synchronized(transfers) { if (transfers[offerId] === transfer) transfers.remove(offerId) }
            onDone(result)
        }
        synchronized(transfers) { transfers[offerId] = transfer }
        try {
            server.accept(offerId, plan.answerItems())
        } catch (e: ShareError) {
            synchronized(transfers) { transfers.remove(offerId) }
            throw e
        }
        clearIncoming(offerId)
        transfer.start()
        if (transfer.status.value == TransferStatus.RECEIVING) transfer.startWatchdog()
        return transfer
    }

    /** The manifest for [roms]; hashes files, so off the main thread. */
    fun buildManifest(
        library: ShareLibrary,
        roms: List<Rom>,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): BuiltManifest = ManifestBuilder(library, onProgress = onProgress, cancelled = cancelled).build(roms)

    /** Offers [built] to the receiver behind [client] on a worker thread; [OutgoingTransfer.cancel] stops it. */
    fun send(
        client: OfferClient,
        built: BuiltManifest,
        onCode: (String) -> Unit = {},
        onProgress: (TransferProgress) -> Unit = {},
        onDone: (SendResult) -> Unit = {},
    ): OutgoingTransfer {
        val identity = availableIdentity()
        val sender = OfferSender(identity.id, identity.name, identity.platform, identity.fingerprint)
        return OutgoingTransfer(client, sender, built, onCode, onProgress, onDone).also { it.start() }
    }

    /** The transfer receiving [offerId], while it runs. */
    fun transfer(offerId: String): IncomingTransfer? = synchronized(transfers) { transfers[offerId] }

    fun decline(offerId: String) {
        requireServer().decline(offerId)
        clearIncoming(offerId)
    }

    fun cancel(offerId: String) {
        synchronized(transfers) { transfers.remove(offerId) }?.cancel()
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
        val list = currentPeers()
        val info = PeerInfo.parse(client.request("GET", "/info").second)
        if (info.id == _identity.value?.id) throw ShareError.badRequest("that address is this device")
        val fp = client.peerFingerprint!!
        val announcement = Announcement(info.id, info.name, info.platform, info.versions, address.port, fp)
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
