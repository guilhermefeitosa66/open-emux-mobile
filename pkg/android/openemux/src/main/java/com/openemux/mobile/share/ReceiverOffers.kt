package com.openemux.mobile.share

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** Who sent an offer, as its body declared (the name is self-declared; the code is the proof). */
data class OfferSender(val id: String, val name: String, val platform: String, val fp: String)

/** What the receiver's UI is handed once an offer is revealed. */
class IncomingOffer(val offerId: String, val sender: OfferSender, val manifest: Manifest, val code: String)

/** The receiver's answer for one manifest item: `send`, or `skip` with a reason. */
data class ItemAnswer(val id: String, val action: String, val reason: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("action", action).apply { reason?.let { put("reason", it) } }

    companion object {
        const val SEND = "send"
        const val SKIP = "skip"

        fun send(id: String) = ItemAnswer(id, SEND)
        fun skip(id: String, reason: String) = ItemAnswer(id, SKIP, reason)
    }
}

/**
 * The receiver's offers (spec §8): one pending at a time, 60 s to be answered,
 * finished ones kept 5 minutes for the sender's last poll. Pure and
 * thread-safe; [clock] is in milliseconds. The routes of [ShareServer] call in
 * here, and so does the UI's answer.
 */
class ReceiverOffers(
    private val ownFingerprint: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newOfferId: () -> String = ::randomOfferId,
    private val newNonce: () -> ByteArray = Verification::newNonce,
) {
    enum class Status(val wire: String) {
        COMMITTED("pending"), PENDING("pending"), ACCEPTED("accepted"),
        DECLINED("declined"), EXPIRED("expired"), CANCELLED("cancelled");

        val live: Boolean get() = this == COMMITTED || this == PENDING
        val closed: Boolean get() = this == DECLINED || this == EXPIRED || this == CANCELLED
    }

    private class Offer(
        val id: String,
        val sender: OfferSender,
        val commit: String,
        val manifest: Manifest,
        val nonceR: ByteArray,
        val created: Long,
    ) {
        var status = Status.COMMITTED
        var finished = 0L
        var answers: List<ItemAnswer> = emptyList()
        val sent = mutableSetOf<String>()
        var incoming: IncomingOffer? = null

        fun finish(status: Status, at: Long) {
            this.status = status
            finished = at
        }
    }

    private val offers = LinkedHashMap<String, Offer>()

    /** `POST /offer`: the `201` body. */
    @Synchronized
    fun create(body: JSONObject): JSONObject {
        val sender = body.opt("sender") as? JSONObject ?: throw ShareError.badRequest("sender must be an object")
        val senderId = sender.opt("id")
        val senderFp = sender.opt("fp")
        if (!isDeviceId(senderId)) throw ShareError.badRequest("sender.id must be 32 hex characters")
        if (!isFingerprint(senderFp)) throw ShareError.badRequest("sender.fp must be 64 hex characters")
        val commit = body.opt("commit")
        if (!isFingerprint(commit)) throw ShareError.badRequest("commit must be 64 hex characters")
        val manifest = ManifestParser.parse(body.opt("manifest"))
        expire()
        if (offers.values.any { it.status.live }) throw ShareError(ErrorCode.BUSY, "another offer is pending")
        val offer = Offer(
            newOfferId(),
            OfferSender(senderId as String, displayName(sender.opt("name"), senderId), sanitizePlatform(sender.opt("pf")), senderFp as String),
            commit as String, manifest, newNonce(), clock(),
        )
        offers[offer.id] = offer
        return JSONObject().put("offer_id", offer.id).put("nonce_r", offer.nonceR.toHex())
            .put("expires_in", ShareProtocol.OFFER_TTL_MS / 1000)
    }

    /** `POST /offer/<id>/reveal`: the code, and the offer to show the user. */
    @Synchronized
    fun reveal(offerId: String, body: JSONObject): IncomingOffer {
        val nonceS = body.opt("nonce_s")
        val offer = live(offerId)
        if (!isHex(nonceS, 2 * Verification.NONCE_BYTES)) throw ShareError.badRequest("nonce_s must be 64 hex characters")
        if (offer.status != Status.COMMITTED) throw ShareError.badRequest("offer already revealed")
        val nonce = (nonceS as String).hexToBytes()
        if (!Verification.checkCommit(offer.commit, nonce)) {
            offers.remove(offerId)
            throw ShareError(ErrorCode.BAD_COMMIT, "nonce does not match the commitment")
        }
        val code = Verification.code(offer.sender.fp, ownFingerprint, nonce, offer.nonceR)
        offer.status = Status.PENDING
        return IncomingOffer(offer.id, offer.sender, offer.manifest, code).also { offer.incoming = it }
    }

    /** `GET /offer/<id>`. */
    @Synchronized
    fun poll(offerId: String): JSONObject {
        expire()
        val offer = offers[offerId] ?: throw ShareError(ErrorCode.NOT_FOUND, "no such offer")
        return JSONObject().put("status", offer.status.wire).apply {
            if (offer.status == Status.ACCEPTED) put("items", JSONArray(offer.answers.map { it.toJson() }))
        }
    }

    /** `POST /cancel/<id>` from the sender; true when it stopped something the UI should hear of. */
    @Synchronized
    fun cancelBySender(offerId: String): Boolean {
        expire()
        val offer = offers[offerId] ?: throw ShareError(ErrorCode.NOT_FOUND, "no such offer")
        if (offer.status.closed) return false
        offer.finish(Status.CANCELLED, clock())
        return true
    }

    /** The user's decision on a revealed offer. */
    @Synchronized
    fun answer(offerId: String, accepted: Boolean, answers: List<ItemAnswer> = emptyList()) {
        val offer = live(offerId)
        if (offer.status != Status.PENDING) throw ShareError.badRequest("offer is not waiting for an answer")
        offer.answers = if (accepted) answers else emptyList()
        offer.finish(if (accepted) Status.ACCEPTED else Status.DECLINED, clock())
    }

    /** Cancel from this side; the sender learns it on its next poll or upload. */
    @Synchronized
    fun cancelByReceiver(offerId: String): Boolean {
        expire()
        val offer = offers[offerId] ?: return false
        if (offer.status.closed) return false
        offer.finish(Status.CANCELLED, clock())
        return true
    }

    /** Every offer still open, cancelled (the server stops). */
    @Synchronized
    fun cancelAll(): List<String> = offers.values.filter { !it.status.closed }
        .onEach { it.finish(Status.CANCELLED, clock()) }.map { it.id }

    /** The checks of `PUT /file` before a byte is written: the offer and item to receive. */
    @Synchronized
    fun beginUpload(offerId: String, itemId: String, length: Long): Pair<IncomingOffer, Item> {
        val offer = live(offerId)
        if (offer.status == Status.CANCELLED || offer.status == Status.DECLINED) {
            throw ShareError(offer.status.wire, "offer ${offer.status.wire}")
        }
        val sendable = offer.answers.any { it.id == itemId && it.action == ItemAnswer.SEND }
        if (offer.status != Status.ACCEPTED || !sendable || itemId in offer.sent) {
            throw ShareError.badRequest("item is not accepted for upload")
        }
        val item = offer.manifest.items.first { it.id == itemId }
        if (item.size != length) throw ShareError.badRequest("Content-Length is not the item's size")
        // A transfer in progress keeps its offer: retention counts from the last upload.
        offer.finished = clock()
        return offer.incoming!! to item
    }

    @Synchronized
    fun uploaded(offerId: String, itemId: String) {
        offers[offerId]?.let {
            it.sent += itemId
            it.finished = clock()
        }
    }

    @Synchronized
    fun status(offerId: String): Status? {
        expire()
        return offers[offerId]?.status
    }

    private fun live(offerId: String): Offer {
        expire()
        val offer = offers[offerId] ?: throw ShareError(ErrorCode.NOT_FOUND, "no such offer")
        if (offer.status == Status.EXPIRED) throw ShareError(ErrorCode.EXPIRED, "offer expired")
        return offer
    }

    private fun expire() {
        val now = clock()
        val iterator = offers.values.iterator()
        while (iterator.hasNext()) {
            val offer = iterator.next()
            if (offer.status.live) {
                if (now - offer.created > ShareProtocol.OFFER_TTL_MS) offer.finish(Status.EXPIRED, offer.created + ShareProtocol.OFFER_TTL_MS)
            } else if (now - offer.finished > FINISHED_RETENTION_MS) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val FINISHED_RETENTION_MS = 5 * 60_000L
        val OFFER_ID = Regex("[A-Za-z0-9_-]{22}")
        private val random = SecureRandom()

        /** 128 random bits, URL-safe base64 without padding: 22 characters. */
        fun randomOfferId(): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
