package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OutgoingTransferTest {
    private val lib = FakeLibrary()
    private val sender = OfferSender("a".repeat(32), "Phone", "phone", "b".repeat(64))

    private val built: BuiltManifest = run {
        val rom = lib.rom(Console.SFC, "Game.sfc", ByteArray(300 * 1024) { 1 })
        File(lib.paths.saves(Console.SFC), "Game.srm").put("save")
        File(lib.paths.states(Console.SFC), "Game.state1").put("state")
        ManifestBuilder(lib).build(listOf(rom))
    }

    private fun answer(status: String, vararg items: JSONObject) =
        JSONObject().put("status", status).apply { if (items.isNotEmpty()) put("items", JSONArray(items.toList())) }

    private fun send(id: String) = ItemAnswer.send(id).toJson()
    private fun skip(id: String, reason: String) = ItemAnswer.skip(id, reason).toJson()

    /** Answers polls from [polls] in turn; uploads are read in 128 KiB chunks. */
    private open inner class FakeClient(vararg polls: Any) : OfferClient {
        val polls = ArrayDeque(polls.toList())
        val puts = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        var offers = 0
        var cancelError: ShareError? = null

        override fun offer(sender: OfferSender, manifest: JSONObject): OutgoingOffer {
            offers++
            ManifestParser.parse(manifest)
            return OutgoingOffer("offer1", "123456")
        }

        override fun poll(offerId: String): JSONObject = when (val next = polls.removeFirst()) {
            is ShareError -> throw next
            else -> next as JSONObject
        }

        override fun cancel(offerId: String) {
            cancels += offerId
            cancelError?.let { throw it }
        }

        override fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit) {
            var left = size
            while (left > 0) {
                val n = minOf(left, 128L * 1024).toInt()
                onChunk(n)
                left -= n
            }
            puts += itemId
        }
    }

    private fun transfer(client: OfferClient, progress: MutableList<TransferProgress> = mutableListOf(), codes: MutableList<String> = mutableListOf()) =
        OutgoingTransfer(client, sender, built, onCode = { codes += it }, onProgress = { progress += it }, pollIntervalMs = 1)

    @Test
    fun `an accepted offer uploads what was asked for, in manifest order`() {
        val client = FakeClient(
            answer("pending"),
            answer("accepted", send("g1i3"), skip("g1i2", "exists_skip"), send("g1i1"), JSONObject().put("id", 7), "junk".let { JSONObject().put("x", it) }, send("zzz"), send("g1i1")),
        )
        val progress = mutableListOf<TransferProgress>()
        val codes = mutableListOf<String>()
        val done = mutableListOf<SendResult>()
        val transfer = transfer(client, progress, codes).apply { onDone = { done += it } }
        val result = transfer.run()
        assertEquals(SendResult.DONE, result.status)
        assertEquals(listOf("g1i1", "g1i3"), client.puts)
        assertEquals(listOf("g1i1", "g1i3"), result.sent)
        assertEquals(listOf("g1i2" to "exists_skip"), result.skipped)
        assertEquals(listOf("123456"), codes)
        assertEquals("offer1", transfer.offerId)
        assertEquals(listOf(result), done)
        assertEquals(result, transfer.result)
        val total = 300L * 1024 + "state".length
        assertEquals(total, progress.last().doneBytes)
        assertEquals(total, progress.last().totalBytes)
        assertEquals(2, progress.last().filesDone)
        assertEquals("Game.state1", progress.last().name)
        assertTrue(client.cancels.isEmpty())
    }

    @Test
    fun `items the receiver did not answer are skipped without a reason`() {
        val result = transfer(FakeClient(JSONObject().put("status", "accepted").put("items", JSONArray().put("junk")))).run()
        assertEquals(listOf("g1i1" to null, "g1i2" to null, "g1i3" to null), result.skipped)
        assertTrue(result.sent.isEmpty())
    }

    @Test
    fun `a declined, expired or cancelled offer ends with that status`() {
        for (status in listOf("declined", "expired", "cancelled")) {
            val client = FakeClient(answer(status))
            assertEquals(status, transfer(client).run().status)
            assertTrue(client.puts.isEmpty())
        }
    }

    @Test
    fun `the receiver's cancel during an upload ends it as cancelled`() {
        val client = object : FakeClient(answer("accepted", send("g1i1"))) {
            override fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit) =
                throw ShareError(ErrorCode.CANCELLED, "offer cancelled")
        }
        assertEquals(SendResult.CANCELLED, transfer(client).run().status)
        assertTrue(client.cancels.isEmpty())
        val expired = object : FakeClient(answer("accepted", send("g1i1"))) {
            override fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit) =
                throw ShareError(ErrorCode.EXPIRED, "offer expired")
        }
        assertEquals(SendResult.EXPIRED, transfer(expired).run().status)
    }

    @Test
    fun `a cut connection asks once whether the receiver cancelled`() {
        val cut = ShareError(ErrorCode.UNREACHABLE, "reset")
        val cancelled = object : FakeClient(answer("accepted", send("g1i1")), answer("cancelled")) {
            override fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit) = throw cut
        }
        assertEquals(SendResult.CANCELLED, transfer(cancelled).run().status)
        val gone = object : FakeClient(answer("accepted", send("g1i1")), ShareError(ErrorCode.UNREACHABLE, "down")) {
            override fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit) = throw cut
        }
        val result = transfer(gone).run()
        assertEquals(SendResult.FAILED, result.status)
        assertEquals(ErrorCode.UNREACHABLE, result.error)
        assertEquals(listOf("offer1"), gone.cancels)
    }

    @Test
    fun `a local cancel mid-upload tells the receiver before the upload is cut`() {
        lateinit var transfer: OutgoingTransfer
        val client = object : FakeClient(answer("accepted", send("g1i1"), send("g1i3"))) {
            var toldWhileOpen: List<String>? = null

            override fun putFile(offerId: String, itemId: String, file: File, size: Long, onChunk: (Int) -> Unit) {
                onChunk(1)
                transfer.cancel()
                try {
                    onChunk(1)
                } finally {
                    toldWhileOpen = cancels.toList()
                }
            }
        }
        transfer = transfer(client)
        val result = transfer.run()
        assertEquals(SendResult.CANCELLED, result.status)
        // Once, and already while the upload was open.
        assertEquals(listOf("offer1"), client.toldWhileOpen)
        assertEquals(listOf("offer1"), client.cancels)
        assertTrue(result.sent.isEmpty())
    }

    @Test
    fun `a cancel before the offer sends nothing at all`() {
        val client = FakeClient()
        val transfer = transfer(client)
        transfer.cancel()
        assertEquals(SendResult.CANCELLED, transfer.run().status)
        assertEquals(0, client.offers)
        assertTrue(client.cancels.isEmpty())
        assertNull(transfer.offerId)
    }

    @Test
    fun `a cancel while waiting for the answer stops the polling`() {
        lateinit var transfer: OutgoingTransfer
        val client = object : FakeClient() {
            override fun poll(offerId: String): JSONObject {
                transfer.cancel()
                return answer("pending")
            }
        }
        transfer = transfer(client)
        assertEquals(SendResult.CANCELLED, transfer.run().status)
        assertEquals(listOf("offer1"), client.cancels)
    }

    @Test
    fun `a source that changed since the offer fails the send`() {
        built.sources.getValue("g1i2").put("longer save")
        val client = FakeClient(answer("accepted", send("g1i1"), send("g1i2")))
        client.cancelError = ShareError(ErrorCode.UNREACHABLE, "gone")
        val result = transfer(client).run()
        assertEquals(SendResult.FAILED, result.status)
        assertEquals(ErrorCode.SOURCE_CHANGED, result.error)
        assertEquals(listOf("g1i1"), result.sent)
        assertEquals(listOf("offer1"), client.cancels)

        built.sources.getValue("g1i2").put("save", mtime = 1_800_000_000)
        assertEquals(ErrorCode.SOURCE_CHANGED, transfer(FakeClient(answer("accepted", send("g1i2")))).run().error)
        built.sources.getValue("g1i2").delete()
        assertEquals(ErrorCode.SOURCE_CHANGED, transfer(FakeClient(answer("accepted", send("g1i2")))).run().error)
    }

    @Test
    fun `other errors fail the send with their code`() {
        val result = transfer(FakeClient(ShareError(ErrorCode.INTERNAL, "boom"))).run()
        assertEquals(SendResult.FAILED, result.status)
        assertEquals(ErrorCode.INTERNAL, result.error)
        val unknown = transfer(FakeClient(answer("weird"))).run()
        assertEquals(ErrorCode.BAD_REQUEST, unknown.error)
        val refused = object : FakeClient() {
            override fun offer(sender: OfferSender, manifest: JSONObject): OutgoingOffer = throw ShareError(ErrorCode.BUSY, "busy")
        }
        assertEquals(ErrorCode.BUSY, transfer(refused).run().error)
        assertTrue(refused.cancels.isEmpty())
    }

    @Test
    fun `start runs it on its own thread`() {
        val transfer = transfer(FakeClient(answer("declined")))
        transfer.start().join(5_000)
        assertEquals(SendResult.DECLINED, transfer.result!!.status)
    }
}
