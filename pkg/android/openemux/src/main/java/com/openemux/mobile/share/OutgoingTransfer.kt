package com.openemux.mobile.share

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How a send ended: `done`, `declined`, `expired`, `cancelled` or `failed` ([error] then says why). */
data class SendResult(
    val status: String,
    val sent: List<String> = emptyList(),
    /** Item ids the receiver did not ask for, with its reason (null when it gave none). */
    val skipped: List<Pair<String, String?>> = emptyList(),
    val error: String? = null,
) {
    companion object {
        const val DONE = "done"
        const val DECLINED = "declined"
        const val EXPIRED = "expired"
        const val CANCELLED = "cancelled"
        const val FAILED = "failed"
    }
}

/**
 * One offer on its way out, on a worker thread ([run], or [start]): offer the
 * manifest, hand the verification code to [onCode], poll until the receiver's
 * user decides, then upload every item asked for, in manifest order, checking
 * first that its source is still the file that was offered. [cancel] stops it
 * from any thread: the upload in progress is cut and the receiver is told.
 */
class OutgoingTransfer(
    private val client: OfferClient,
    private val sender: OfferSender,
    private val built: BuiltManifest,
    var onCode: (String) -> Unit = {},
    var onProgress: (TransferProgress) -> Unit = {},
    var onDone: (SendResult) -> Unit = {},
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    @Volatile var offerId: String? = null
        private set

    @Volatile var result: SendResult? = null
        private set

    private val cancelled = CountDownLatch(1)
    private val items: Map<String, JSONObject> = linkedMapOf<String, JSONObject>().apply {
        val games = built.json.getJSONArray("games")
        for (g in 0 until games.length()) {
            val list = games.getJSONObject(g).getJSONArray("items")
            for (i in 0 until list.length()) list.getJSONObject(i).let { put(it.getString("id"), it) }
        }
    }

    private class LocalCancel : RuntimeException()

    fun cancel() = cancelled.countDown()

    fun start(): Thread = Thread(::run, "share-send").apply { isDaemon = true; start() }

    fun run(): SendResult {
        val sent = mutableListOf<String>()
        val skipped = mutableListOf<Pair<String, String?>>()
        return try {
            checkCancel()
            val offer = client.offer(sender, built.json)
            offerId = offer.offerId
            onCode(offer.code)
            val answer = waitForAnswer()
            val status = answer.getString("status")
            if (status != ACCEPTED) return done(status, sent, skipped)
            upload(accepted(answer.optJSONArray("items"), skipped), sent)
            done(SendResult.DONE, sent, skipped)
        } catch (_: LocalCancel) {
            tellReceiver()
            done(SendResult.CANCELLED, sent, skipped)
        } catch (e: ShareError) {
            when {
                e.code == ErrorCode.CANCELLED -> done(SendResult.CANCELLED, sent, skipped)
                e.code == ErrorCode.DECLINED || e.code == ErrorCode.EXPIRED -> done(e.code, sent, skipped)
                e.code == ErrorCode.UNREACHABLE && receiverCancelled() -> done(SendResult.CANCELLED, sent, skipped)
                else -> {
                    Log.w(TAG, "share send $offerId failed: ${e.code} (${e.message})")
                    tellReceiver()
                    done(SendResult.FAILED, sent, skipped, e.code)
                }
            }
        }
    }

    private fun checkCancel() {
        if (cancelled.count == 0L) throw LocalCancel()
    }

    private fun waitForAnswer(): JSONObject {
        while (true) {
            checkCancel()
            val answer = client.poll(offerId!!)
            when (answer.getString("status")) {
                ACCEPTED, SendResult.DECLINED, SendResult.EXPIRED, SendResult.CANCELLED -> return answer
                PENDING -> cancelled.await(pollIntervalMs, TimeUnit.MILLISECONDS)
                else -> throw ShareError.badRequest("unknown offer status")
            }
        }
    }

    /** The items to upload, in manifest order; the others go to [skipped]. */
    private fun accepted(answers: JSONArray?, skipped: MutableList<Pair<String, String?>>): List<JSONObject> {
        val wanted = mutableSetOf<String>()
        for (i in 0 until (answers?.length() ?: 0)) {
            val entry = answers!!.opt(i) as? JSONObject ?: continue
            val id = entry.opt("id") as? String ?: continue
            if (id !in items || id in wanted || skipped.any { it.first == id }) continue
            if (entry.opt("action") == ItemAnswer.SEND) wanted += id else skipped += id to (entry.opt("reason") as? String)
        }
        // An item the receiver did not answer is not to be sent.
        items.keys.filter { it !in wanted && skipped.none { s -> s.first == it } }.forEach { skipped += it to null }
        return items.filterKeys { it in wanted }.values.toList()
    }

    private fun upload(toSend: List<JSONObject>, sent: MutableList<String>) {
        val total = toSend.sumOf { it.getLong("size") }
        var done = 0L
        for (item in toSend) {
            checkCancel()
            val id = item.getString("id")
            val source = built.sources.getValue(id)
            val size = item.getLong("size")
            // The mtime too: a battery save keeps its size when the game writes it.
            if (!source.isFile || source.length() != size || source.lastModified() / 1000 != item.getLong("mtime")) {
                throw ShareError(ErrorCode.SOURCE_CHANGED, "${source.name} changed since it was offered")
            }
            var bytes = 0L
            client.putFile(offerId!!, id, source, size) { n ->
                checkCancel()
                bytes += n
                done += n
                report(item, bytes, done, total, sent.size, toSend.size)
            }
            sent += id
            report(item, size, done, total, sent.size, toSend.size)
        }
    }

    private fun report(item: JSONObject, bytes: Long, done: Long, total: Long, filesDone: Int, fileCount: Int) {
        onProgress(
            TransferProgress(
                item.getString("id"), item.getString("name"), item.getString("kind"), bytes, item.getLong("size"),
                done, total, filesDone, fileCount,
            ),
        )
    }

    /** After a cut connection: did the receiver cancel? Asked once. */
    private fun receiverCancelled(): Boolean {
        val id = offerId ?: return false
        return try {
            client.poll(id).optString("status") == SendResult.CANCELLED
        } catch (_: ShareError) {
            false
        }
    }

    private fun tellReceiver() {
        val id = offerId ?: return
        try {
            client.cancel(id)
        } catch (e: ShareError) {
            Log.d(TAG, "share send $id: cancel not delivered (${e.code})")
        }
    }

    private fun done(status: String, sent: List<String>, skipped: List<Pair<String, String?>>, error: String? = null): SendResult =
        SendResult(status, sent.toList(), skipped.toList(), error).also {
            result = it
            onDone(it)
        }

    companion object {
        const val POLL_INTERVAL_MS = 1_000L
        private const val ACCEPTED = "accepted"
        private const val PENDING = "pending"
    }
}
