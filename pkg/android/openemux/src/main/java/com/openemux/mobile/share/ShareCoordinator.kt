package com.openemux.mobile.share

import com.openemux.mobile.data.Rom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/** The manifest behind the Share screen: built when it opens, then checked and unchecked by the user. */
sealed interface SendPrep {
    data object None : SendPrep
    data class Building(val done: Long, val total: Long) : SendPrep
    data class Ready(val built: BuiltManifest, val selection: SendSelection) : SendPrep
    data class Failed(val message: ShareMessage) : SendPrep
}

enum class ResultKind { RECEIVED, SENT, CANCELLED, DECLINED, EXPIRED, FAILED, CONNECTION_LOST }

/** How a transfer ended, as the result card (spec 08) and the final notification say it. */
data class ShareResult(
    val kind: ResultKind,
    val outgoing: Boolean,
    val peer: String,
    val games: Int = 0,
    val skipped: Int = 0,
    /** Saves and states kept because the local one was newer. */
    val keptNewer: Int = 0,
    val error: ShareMessage? = null,
    /** The console of the first game received, for "Show in Library". */
    val firstConsole: String? = null,
)

/** The one transfer this device runs at a time. */
sealed interface TransferState {
    data object Idle : TransferState
    /** The sender's wait for the receiver's answer; [code] once the offer is revealed. */
    data class Waiting(val peer: String, val code: String?) : TransferState
    data class Running(val outgoing: Boolean, val peer: String, val games: Int, val progress: TransferProgress?) : TransferState
    data class Finished(val result: ShareResult) : TransferState

    val busy: Boolean get() = this is Waiting || this is Running
}

/** What the coordinator posts when the app is not in front. */
interface ShareNotifier {
    fun offer(sender: String, code: String)
    fun clearOffer()
    fun finished(result: ShareResult)
}

/** One-off things a screen shows as a toast. */
sealed interface ShareEvent {
    data class OfferExpired(val sender: String) : ShareEvent
}

/** What Connect by Address got: the peer it added, or why not ([peerVersion] for an incompatible one). */
data class ConnectOutcome(val peer: Peer? = null, val error: ShareMessage? = null, val peerVersion: Int? = null)

/**
 * The app side of Share nearby (#591): owns the share layer's lifetime, the
 * one transfer at a time, the pending offer and the send selection, as
 * [StateFlow]s the screens collect. The only class that talks to the
 * [ShareLayer]; the blocking calls go to [io], which the app makes serial.
 */
class ShareCoordinator(
    private val layer: ShareLayer,
    private val scheduler: Scheduler,
    private val notifier: ShareNotifier,
    /** Starts the foreground service that keeps a transfer alive; called only from a visible screen's action. */
    private val startService: () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Where the manifest is hashed: off [io], so a long hash does not hold up offers, Connect by Address or start/stop. */
    private val work: CoroutineDispatcher = io,
) {
    private val _availability = MutableStateFlow<ShareAvailability?>(null)
    val availability: StateFlow<ShareAvailability?> = _availability.asStateFlow()

    val peers: StateFlow<List<Peer>> get() = layer.peers
    val lan: StateFlow<Boolean> get() = layer.lan

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    /** The layer runs: this device is discoverable and searching. */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _nothingFound = MutableStateFlow(false)
    val nothingFound: StateFlow<Boolean> = _nothingFound.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _ownAddress = MutableStateFlow<ShareAddress?>(null)
    val ownAddress: StateFlow<ShareAddress?> = _ownAddress.asStateFlow()

    private val _sendPrep = MutableStateFlow<SendPrep>(SendPrep.None)
    val sendPrep: StateFlow<SendPrep> = _sendPrep.asStateFlow()

    private val _sendRoms = MutableStateFlow<List<Rom>>(emptyList())
    val sendRoms: StateFlow<List<Rom>> = _sendRoms.asStateFlow()

    private val _pendingOffer = MutableStateFlow<OfferModel?>(null)
    val pendingOffer: StateFlow<OfferModel?> = _pendingOffer.asStateFlow()

    private val _transfer = MutableStateFlow<TransferState>(TransferState.Idle)
    val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    private val _events = MutableSharedFlow<ShareEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ShareEvent> = _events.asSharedFlow()

    /** An `openemux-share://` link to open Connect by Address with; the root screen consumes it. */
    val link = MutableStateFlow<String?>(null)

    private var foreground = false
    private var cancelTransfer: (() -> Unit)? = null
    private var cancelRequested = false
    private var prepareJob: Job? = null
    private var expiry: Cancellable? = null
    private var nothingCheck: Cancellable? = null
    /** The offer the layer last revealed, while it is still being planned or shown. */
    @Volatile private var incomingId: String? = null
    private val nothing = NothingFound()
    private val answered: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val discoverability = Discoverability(
        scheduler,
        onStart = { scope.launch(io) { startLayer() } },
        onStop = { scope.launch(io) { stopLayer() } },
    )

    init {
        scope.launch { layer.peers.collect { onPeers(it) } }
        scope.launch { layer.incoming.collect { onIncoming(it) } }
    }

    // -- the layer's lifetime ------------------------------------------------

    /** A share screen opened; [send] when it shows the games picked by [startSend]. */
    fun openScreen(send: Boolean) {
        discoverability.open()
        if (send && _sendPrep.value == SendPrep.None && _sendRoms.value.isNotEmpty()) prepare(emptySet())
    }

    fun closeScreen() = discoverability.close()

    private fun startLayer() {
        val availability = layer.availability()
        _availability.value = availability
        if (availability !is ShareAvailability.Available) return discoverability.failed()
        try {
            _warnings.value = layer.start()
        } catch (e: Exception) {
            _availability.value = ShareAvailability.Unavailable((e as? ShareError)?.details?.get("reason") as? String ?: ErrorCode.INTERNAL)
            return discoverability.failed()
        }
        _deviceName.value = layer.deviceName()
        _ownAddress.value = layer.ownAddress()
        _searching.value = true
        synchronized(nothing) { nothing.started(scheduler.now()) }
        onPeers(layer.peers.value)
    }

    private fun stopLayer() {
        layer.stop()
        _searching.value = false
        _warnings.value = emptyList()
        _ownAddress.value = null
        synchronized(nothing) { nothing.stopped() }
        refreshNothing()
    }

    private fun onPeers(peers: List<Peer>) {
        if (!_searching.value) return
        synchronized(nothing) { nothing.peers(peers, scheduler.now()) }
        refreshNothing()
    }

    private fun refreshNothing(): Unit = synchronized(nothing) {
        val now = scheduler.now()
        _nothingFound.value = nothing.isNothingFound(now)
        nothingCheck?.cancel()
        nothingCheck = nothing.remaining(now)?.takeIf { it > 0 }?.let { scheduler.schedule(it) { refreshNothing() } }
    }

    // -- sending ---------------------------------------------------------------

    /** The games the next Share screen opens with (navigation carries no lists). */
    fun startSend(roms: List<Rom>) {
        prepareJob?.cancel()
        _sendRoms.value = roms
        _sendPrep.value = SendPrep.None
    }

    private fun prepare(keep: Set<Pair<String, SendPart>>) {
        val roms = _sendRoms.value
        prepareJob?.cancel()
        _sendPrep.value = SendPrep.Building(0, 0)
        prepareJob = scope.launch(work) {
            try {
                // A cancelled build must not write over the state the next one starts from.
                val built = layer.buildManifest(roms, { done, total -> if (isActive) _sendPrep.value = SendPrep.Building(done, total) }, { !isActive })
                if (isActive) _sendPrep.value = SendPrep.Ready(built, SendSelection.from(built, roms).copy(unchecked = keep))
            } catch (e: ShareError) {
                if (isActive) _sendPrep.value = SendPrep.Failed(ShareFormat.errorKey(e.code))
            } catch (e: Exception) {
                if (isActive) _sendPrep.value = SendPrep.Failed(ShareMessage.GENERIC)
            }
        }
    }

    fun updateSelection(selection: SendSelection) {
        val ready = _sendPrep.value as? SendPrep.Ready ?: return
        _sendPrep.value = ready.copy(selection = selection)
    }

    /** Offers the checked part of the selection to [peer]. */
    fun send(peer: Peer) {
        val ready = _sendPrep.value as? SendPrep.Ready ?: return
        if (ready.selection.isEmpty || !peer.compatible || _transfer.value.busy) return
        val built = ready.selection.filter(ready.built)
        val games = ready.selection.gameCount
        begin(TransferState.Waiting(peer.name, null))
        scope.launch(io) {
            try {
                val cancel = layer.send(
                    peer, built,
                    onCode = { code -> if (_transfer.value is TransferState.Waiting) _transfer.value = TransferState.Waiting(peer.name, code) },
                    onProgress = { progress -> if (_transfer.value.busy) _transfer.value = TransferState.Running(true, peer.name, games, progress) },
                    onDone = { finish(sendResult(peer.name, games, it)) },
                )
                holdCancel(cancel)
            } catch (e: ShareError) {
                finish(sendResult(peer.name, games, SendResult(SendResult.FAILED, error = e.code)))
            } catch (e: Exception) {
                // Anything else would leave the transfer busy for good (and take the app down).
                finish(sendResult(peer.name, games, SendResult(SendResult.FAILED, error = ErrorCode.INTERNAL)))
            }
        }
    }

    private fun sendResult(peer: String, games: Int, result: SendResult): ShareResult {
        val kind = when (result.status) {
            SendResult.DONE -> ResultKind.SENT
            SendResult.DECLINED -> ResultKind.DECLINED
            SendResult.EXPIRED -> ResultKind.EXPIRED
            SendResult.CANCELLED -> ResultKind.CANCELLED
            else -> failure(result.error)
        }
        // A file changed while the screen was open: the next try needs a fresh manifest.
        if (result.error == ErrorCode.SOURCE_CHANGED) {
            prepare((_sendPrep.value as? SendPrep.Ready)?.selection?.unchecked.orEmpty())
        }
        return ShareResult(kind, true, peer, games, result.skipped.size, error = errorOf(kind, result.error))
    }

    // -- receiving -------------------------------------------------------------

    private fun onIncoming(offer: IncomingOffer?) {
        if (offer == null) {
            // The sender cancelled, or the layer stopped: an offer still being planned must not show up afterwards.
            val stale = incomingId
            incomingId = null
            if (stale != null) answered += stale
            if (stale != null || _pendingOffer.value != null) dropOffer()
            return
        }
        if (offer.offerId in answered || _pendingOffer.value?.offer?.offerId == offer.offerId) return
        if (_transfer.value.busy) {
            // One transfer at a time: the sheet never stacks on a running one.
            answered += offer.offerId
            scope.launch(io) { quietly { layer.decline(offer.offerId) } }
            return
        }
        incomingId = offer.offerId
        val receivedAt = scheduler.now()
        expiry?.cancel()
        expiry = scheduler.schedule(ShareProtocol.OFFER_TTL_MS) { expire(offer.offerId) }
        if (!foreground) notifier.offer(offer.sender.name, offer.code)
        scope.launch(io) {
            try {
                val model = OfferModel.build(offer, { layer.plan(offer, it) }, emptyMap(), receivedAt)
                if (offer.offerId !in answered) _pendingOffer.value = model
            } catch (e: Exception) {
                // An offer this side cannot even plan is not shown half-built.
                answered += offer.offerId
                dropOffer()
                quietly { layer.decline(offer.offerId) }
            }
        }
    }

    private fun dropOffer() {
        expiry?.cancel()
        expiry = null
        _pendingOffer.value = null
        notifier.clearOffer()
    }

    private fun expire(offerId: String) {
        val model = _pendingOffer.value?.takeIf { it.offer.offerId == offerId }
        answered += offerId
        dropOffer()
        if (model != null) _events.tryEmit(ShareEvent.OfferExpired(model.sender))
    }

    /** The user's choice for one conflicting item; the plan and the space check follow it. */
    fun setOverride(itemId: String, policy: Policy) {
        val offerId = _pendingOffer.value?.offer?.offerId ?: return
        scope.launch(io) {
            // The latest model, read in turn: two quick choices both count.
            val model = _pendingOffer.value?.takeIf { it.offer.offerId == offerId } ?: return@launch
            val next = try {
                model.replan({ layer.plan(model.offer, it) }, model.overrides + (itemId to policy))
            } catch (e: Exception) {
                return@launch
            }
            if (_pendingOffer.value?.offer?.offerId == offerId) _pendingOffer.value = next
        }
    }

    fun accept() {
        val model = _pendingOffer.value ?: return
        if (_transfer.value.busy || !model.spaceOk) return
        val offerId = model.offer.offerId
        begin(TransferState.Running(false, model.sender, model.plan.games.count { g -> g.decisions.any { it.send } }, null))
        scope.launch(io) {
            // A choice made just before Accept replans on [io] first: accept what it planned.
            val model = _pendingOffer.value?.takeIf { it.offer.offerId == offerId } ?: model
            val games = model.plan.games.count { g -> g.decisions.any { it.send } }
            if (!model.spaceOk) {
                _transfer.value = TransferState.Idle
                discoverability.transferEnded()
                return@launch
            }
            try {
                val cancel = layer.accept(
                    offerId, model.plan,
                    onProgress = { progress -> if (_transfer.value.busy) _transfer.value = TransferState.Running(false, model.sender, games, progress) },
                    onDone = { finish(receiveResult(model, games, it)) },
                )
                answered += offerId
                dropOffer()
                holdCancel(cancel)
            } catch (e: ShareError) {
                // Not enough room after all, or the offer went away: the sheet says so and stays.
                _transfer.value = TransferState.Idle
                discoverability.transferEnded()
                if (_pendingOffer.value?.offer?.offerId == offerId) _pendingOffer.value = model.withError(ShareFormat.errorKey(e.code))
            } catch (e: Exception) {
                _transfer.value = TransferState.Idle
                discoverability.transferEnded()
                if (_pendingOffer.value?.offer?.offerId == offerId) _pendingOffer.value = model.withError(ShareMessage.GENERIC)
            }
        }
    }

    private fun receiveResult(model: OfferModel, games: Int, result: ReceiveResult): ShareResult {
        val kind = when (result.status) {
            TransferStatus.DONE -> ResultKind.RECEIVED
            TransferStatus.CANCELLED -> ResultKind.CANCELLED
            else -> failure(result.error)
        }
        val kinds = model.plan.decisions.associate { it.itemId to it.kind }
        // Kept because the local one was newer: Keep Newest only, not a Skip the user chose.
        val keptNewer = result.skipped.count { (id, reason) ->
            val kind = kinds[id]
            reason == SkipReason.EXISTS_SKIP && kind in setOf(Item.BATTERY_SAVE, Item.STATE) &&
                (model.overrides[id] ?: DEFAULT_POLICIES[kind]) == Policy.NEWEST
        }
        val first = model.plan.games.firstOrNull { it.romStatus.placed || it.romStatus == RomStatus.REPLACED }?.console?.id
        return ShareResult(kind, false, model.sender, games, result.skipped.size, keptNewer, errorOf(kind, result.error), first)
    }

    fun decline() {
        val model = _pendingOffer.value ?: return
        answered += model.offer.offerId
        dropOffer()
        scope.launch(io) { quietly { layer.decline(model.offer.offerId) } }
    }

    // -- the transfer ----------------------------------------------------------

    private fun begin(state: TransferState) {
        synchronized(this) {
            cancelTransfer = null
            cancelRequested = false
        }
        _transfer.value = state
        discoverability.transferStarted()
        startService()
    }

    private fun holdCancel(cancel: () -> Unit) {
        val now = synchronized(this) {
            if (!_transfer.value.busy) return
            cancelTransfer = cancel
            cancelRequested
        }
        if (now) cancel()
    }

    /** From the screen or the notification. */
    fun cancel() {
        val cancel = synchronized(this) {
            if (!_transfer.value.busy) return
            cancelRequested = true
            cancelTransfer
        }
        cancel?.invoke()
    }

    private fun finish(result: ShareResult) {
        synchronized(this) { cancelTransfer = null }
        _transfer.value = TransferState.Finished(result)
        discoverability.transferEnded()
        if (!foreground) notifier.finished(result)
    }

    /** The result card was closed. */
    fun dismissResult() {
        if (_transfer.value is TransferState.Finished) _transfer.value = TransferState.Idle
    }

    private fun failure(code: String?) =
        if (code == ErrorCode.UNREACHABLE || code == ErrorCode.IDLE_TIMEOUT) ResultKind.CONNECTION_LOST else ResultKind.FAILED

    private fun errorOf(kind: ResultKind, code: String?) = if (kind == ResultKind.FAILED) ShareFormat.errorKey(code) else null

    // -- the rest ----------------------------------------------------------------

    /** The clock offers are timed with: the sheet's countdown reads it too. */
    fun now(): Long = scheduler.now()

    /** The main activity is visible (offers and results show in the app) or not (they go to notifications). */
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) notifier.clearOffer()
    }

    fun loadName() {
        scope.launch(io) { quietly { _deviceName.value = layer.deviceName() } }
    }

    fun defaultName(): String = layer.defaultName()

    /** Trimmed and cut to the protocol's limit; empty goes back to the default. Takes effect on the next start. */
    fun rename(name: String) {
        val clean = name.trim().take(ShareProtocol.MAX_NAME_CHARS)
        scope.launch(io) { quietly { _deviceName.value = layer.setName(clean) } }
    }

    /** Connect by Address: checks [text], reaches the device and lists it. */
    suspend fun connect(text: String): ConnectOutcome = withContext(io) {
        val address = try {
            ShareAddress.parse(text)
        } catch (_: ShareError) {
            return@withContext ConnectOutcome(error = ShareMessage.BAD_REQUEST)
        }
        try {
            val peer = layer.addManualPeer(address)
            if (peer.compatible) ConnectOutcome(peer) else ConnectOutcome(peer, ShareMessage.INCOMPATIBLE_VERSION, peer.versions.max)
        } catch (e: ShareError) {
            ConnectOutcome(
                error = when (e.code) {
                    ErrorCode.FINGERPRINT_MISMATCH -> ShareMessage.FINGERPRINT_MISMATCH
                    ErrorCode.INCOMPATIBLE_VERSION -> ShareMessage.INCOMPATIBLE_VERSION
                    ErrorCode.BAD_REQUEST -> ShareMessage.BAD_REQUEST
                    else -> ShareMessage.UNREACHABLE
                },
            )
        }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: ShareError) {
        }
    }
}
