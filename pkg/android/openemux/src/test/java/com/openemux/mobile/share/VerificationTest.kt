package com.openemux.mobile.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * share_vectors.json is OpenEmux's tests/data/share_vectors.json, copied from
 * commit c46bec0 (issue 588's merge, which added the manifest sample): both
 * apps must reproduce it.
 */
class VerificationTest {
    private val vectors = JSONObject(javaClass.getResource("/share_vectors.json")!!.readText()).getJSONArray("vectors")

    private fun each(block: (JSONObject) -> Unit) = (0 until vectors.length()).forEach { block(vectors.getJSONObject(it)) }

    @Test
    fun `every vector reproduces its commit and code`() {
        assertEquals(4, vectors.length())
        each { v ->
            val nonceS = v.getString("nonce_s").hexToBytes()
            assertEquals(v.getString("name"), v.getString("commit"), Verification.commit(nonceS))
            assertTrue(Verification.checkCommit(v.getString("commit"), nonceS))
            val code = Verification.code(v.getString("fp_sender"), v.getString("fp_receiver"), nonceS, v.getString("nonce_r").hexToBytes())
            assertEquals(v.getString("name"), v.getString("code"), code)
        }
    }

    @Test
    fun `the code keeps its leading zero and the fingerprints their order`() {
        val codes = (0 until vectors.length()).associate { vectors.getJSONObject(it).let { v -> v.getString("name") to v.getString("code") } }
        assertEquals("093444", codes["leading zero"])
        assertNotEquals(codes["hashed"], codes["fingerprints swapped"])
    }

    @Test
    fun `a commit for another nonce does not match`() {
        val nonce = Verification.newNonce()
        assertEquals(32, nonce.size)
        assertFalse(Verification.checkCommit(Verification.commit(nonce), Verification.newNonce()))
        assertTrue(Verification.checkCommit(Verification.commit(nonce).uppercase(), nonce))
    }

    @Test
    fun `the code is shown in two groups`() {
        assertEquals("093 444", Verification.formatCode("093444"))
    }
}
