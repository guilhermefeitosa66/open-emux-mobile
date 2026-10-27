package com.openemux.mobile.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverOffersTest {
    private val clock = FakeClock()
    private val ownFp = "f".repeat(64)
    private val senderFp = "e".repeat(64)
    private val offers = ReceiverOffers(ownFp, clock)
    private val nonceS = ByteArray(32) { 7 }

    private fun manifest() = JSONObject(
        """{"games":[{"id":"g1","console":"GBA","title":"Game","items":[
            {"id":"i1","kind":"rom","name":"Game.gba","size":4,"mtime":1},
            {"id":"i2","kind":"battery_save","name":"Game.srm","size":2,"mtime":1}]}],
            "total_size":6,"file_count":2}"""
    )

    private fun body(commit: String = Verification.commit(nonceS)) = JSONObject()
        .put("sender", JSONObject().put("id", "a".repeat(32)).put("name", "Phone\u0000").put("pf", "phone").put("fp", senderFp))
        .put("commit", commit)
        .put("manifest", manifest())

    private fun reveal(id: String, nonce: ByteArray = nonceS) = offers.reveal(id, JSONObject().put("nonce_s", nonce.toHex()))

    private fun code(block: () -> Unit) = assertThrows(ShareError::class.java, block).code

    @Test
    fun `an offer is created, revealed with the right code and accepted`() {
        val created = offers.create(body())
        val id = created.getString("offer_id")
        assertTrue(ReceiverOffers.OFFER_ID.matches(id))
        assertEquals(60, created.getInt("expires_in"))
        assertEquals("pending", offers.poll(id).getString("status"))
        val incoming = reveal(id)
        val expected = Verification.code(senderFp, ownFp, nonceS, created.getString("nonce_r").hexToBytes())
        assertEquals(expected, incoming.code)
        assertEquals("Phone", incoming.sender.name)
        assertEquals(2, incoming.manifest.items.size)
        assertEquals("pending", offers.poll(id).getString("status"))
        assertFalse(offers.poll(id).has("items"))
        offers.answer(id, true, listOf(ItemAnswer.send("i1"), ItemAnswer.skip("i2", "exists_skip")))
        val poll = offers.poll(id)
        assertEquals("accepted", poll.getString("status"))
        assertEquals("exists_skip", poll.getJSONArray("items").getJSONObject(1).getString("reason"))
        assertFalse(poll.getJSONArray("items").getJSONObject(0).has("reason"))
    }

    @Test
    fun `one pending offer at a time`() {
        offers.create(body())
        assertEquals(ErrorCode.BUSY, code { offers.create(body()) })
    }

    @Test
    fun `an answer naming no manifest item, a bad action or sending an unknown kind is refused`() {
        val unknownKind = manifest().apply {
            getJSONArray("games").getJSONObject(0).getJSONArray("items").getJSONObject(1).put("kind", "movie")
        }
        val id = offers.create(body().put("manifest", unknownKind)).getString("offer_id")
        reveal(id)
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.answer(id, true, listOf(ItemAnswer.send("nope"))) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.answer(id, true, listOf(ItemAnswer("i1", "maybe"))) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.answer(id, true, listOf(ItemAnswer.send("i2"))) })
        offers.answer(id, true, listOf(ItemAnswer.send("i1"), ItemAnswer.skip("i2", "user_unchecked")))
        assertEquals("accepted", offers.poll(id).getString("status"))
    }

    @Test
    fun `malformed offers are bad requests`() {
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.create(JSONObject()) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.create(body().apply { getJSONObject("sender").put("id", "x") }) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.create(body().apply { getJSONObject("sender").remove("fp") }) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.create(body(commit = "zz")) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.create(body().put("manifest", JSONObject())) })
    }

    @Test
    fun `an offer expires 60 s after it was posted`() {
        val id = offers.create(body()).getString("offer_id")
        clock.now += 60_000
        assertEquals(ReceiverOffers.Status.COMMITTED, offers.status(id))
        clock.now += 1
        assertEquals("expired", offers.poll(id).getString("status"))
        assertEquals(ErrorCode.EXPIRED, code { reveal(id) })
        offers.create(body())
    }

    @Test
    fun `reveal errors`() {
        assertEquals(ErrorCode.NOT_FOUND, code { reveal("nope") })
        val id = offers.create(body()).getString("offer_id")
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.reveal(id, JSONObject().put("nonce_s", "12")) })
        reveal(id)
        assertEquals(ErrorCode.BAD_REQUEST, code { reveal(id) })
    }

    @Test
    fun `a nonce that breaks the commitment discards the offer`() {
        val id = offers.create(body()).getString("offer_id")
        assertEquals(ErrorCode.BAD_COMMIT, code { reveal(id, ByteArray(32) { 8 }) })
        assertNull(offers.status(id))
        assertEquals(ErrorCode.NOT_FOUND, code { offers.poll(id) })
        offers.create(body())
    }

    @Test
    fun `answering needs a revealed offer`() {
        val id = offers.create(body()).getString("offer_id")
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.answer(id, true) })
        reveal(id)
        offers.answer(id, false)
        assertEquals("declined", offers.poll(id).getString("status"))
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.answer(id, true) })
        offers.create(body())
    }

    @Test
    fun `finished offers are kept 5 minutes`() {
        val id = offers.create(body()).getString("offer_id")
        reveal(id)
        offers.answer(id, false)
        clock.now += ReceiverOffers.FINISHED_RETENTION_MS
        assertEquals("declined", offers.poll(id).getString("status"))
        clock.now += 1
        assertEquals(ErrorCode.NOT_FOUND, code { offers.poll(id) })
    }

    @Test
    fun `the sender cancels in any state`() {
        assertEquals(ErrorCode.NOT_FOUND, code { offers.cancelBySender("nope") })
        val id = offers.create(body()).getString("offer_id")
        assertTrue(offers.cancelBySender(id))
        assertFalse(offers.cancelBySender(id))
        assertEquals("cancelled", offers.poll(id).getString("status"))
    }

    @Test
    fun `the receiver cancels and the sender reads it`() {
        assertFalse(offers.cancelByReceiver("nope"))
        val id = offers.create(body()).getString("offer_id")
        reveal(id)
        assertTrue(offers.cancelByReceiver(id))
        assertFalse(offers.cancelByReceiver(id))
        assertEquals("cancelled", offers.poll(id).getString("status"))
        assertEquals(ErrorCode.CANCELLED, code { offers.beginUpload(id, "i1", 4) })
    }

    @Test
    fun `cancelAll closes every open offer`() {
        val id = offers.create(body()).getString("offer_id")
        assertEquals(listOf(id), offers.cancelAll())
        assertEquals(emptyList<String>(), offers.cancelAll())
    }

    @Test
    fun `uploads are checked before a byte is read`() {
        val id = offers.create(body()).getString("offer_id")
        assertEquals(ErrorCode.NOT_FOUND, code { offers.beginUpload("nope", "i1", 4) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.beginUpload(id, "i1", 4) })
        reveal(id)
        offers.answer(id, true, listOf(ItemAnswer.send("i1"), ItemAnswer.skip("i2", "user_unchecked")))
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.beginUpload(id, "i2", 2) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.beginUpload(id, "i9", 2) })
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.beginUpload(id, "i1", 5) })
        val (incoming, item) = offers.beginUpload(id, "i1", 4)
        assertEquals(id, incoming.offerId)
        assertEquals("Game.gba", item.name)
        offers.uploaded(id, "i1")
        offers.uploaded("nope", "i1")
        assertEquals(ErrorCode.BAD_REQUEST, code { offers.beginUpload(id, "i1", 4) })
    }

    @Test
    fun `uploads into a declined or expired offer say so`() {
        val id = offers.create(body()).getString("offer_id")
        reveal(id)
        offers.answer(id, false)
        assertEquals(ErrorCode.DECLINED, code { offers.beginUpload(id, "i1", 4) })
        val other = ReceiverOffers(ownFp, clock)
        val second = other.create(body()).getString("offer_id")
        clock.now += 61_000
        assertEquals(ErrorCode.EXPIRED, code { other.beginUpload(second, "i1", 4) })
    }

    @Test
    fun `offer ids are 22 url-safe characters`() {
        repeat(50) { assertTrue(ReceiverOffers.OFFER_ID.matches(ReceiverOffers.randomOfferId())) }
    }
}
