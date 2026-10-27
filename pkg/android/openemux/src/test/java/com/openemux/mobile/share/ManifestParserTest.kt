package com.openemux.mobile.share

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserTest {
    /** The sample of spec §10. */
    private fun sample() = JSONObject(
        """
        {"games": [{"id": "g1", "console": "SFC", "title": "Super Example",
          "metadata": {"favorite": true, "cartridge_color": "black", "core": "snes9x",
                       "shader": "crt-easymode", "playtime_seconds": 5400},
          "items": [
            {"id": "i1", "kind": "rom", "name": "Super Example (USA).sfc", "size": 1048576,
             "mtime": 1700000000, "sha256": "${"AB".repeat(32)}"},
            {"id": "i2", "kind": "battery_save", "name": "Super Example (USA).srm", "size": 8192, "mtime": 1700000500.5},
            {"id": "i3", "kind": "state", "name": "Super Example (USA).state1", "size": 300000,
             "mtime": 1700000600, "state": {"core": "snes9x", "core_version": "1.62.3", "slot": 1}}
          ]}],
         "total_size": 1356768, "file_count": 3, "future": "ignored"}
        """
    )

    private fun item(index: Int = 0) = sample().getJSONArray("games").getJSONObject(0).getJSONArray("items").getJSONObject(index)

    private fun withItem(edit: JSONObject.() -> Unit): JSONObject = sample().apply {
        getJSONArray("games").getJSONObject(0).getJSONArray("items").getJSONObject(0).edit()
    }

    private fun assertBad(manifest: Any?) {
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ShareError::class.java) { ManifestParser.parse(manifest) }.code)
    }

    @Test
    fun `the spec sample is accepted`() {
        val manifest = ManifestParser.parse(sample())
        assertEquals(1356768L, manifest.totalSize)
        assertEquals(3L, manifest.fileCount)
        val game = manifest.games.single()
        assertEquals("SFC", game.console)
        assertTrue(game.metadata!!.getBoolean("favorite"))
        assertEquals(listOf("i1", "i2", "i3"), manifest.items.map { it.id })
        assertEquals("ab".repeat(32), manifest.items[0].sha256)
        assertEquals(1700000500.5, manifest.items[1].mtime, 0.0)
        assertTrue(manifest.items.all { it.knownKind })
        assertEquals("ignored", manifest.json.getString("future"))
    }

    @Test
    fun `missing or mistyped fields are refused`() {
        assertBad(null)
        assertBad(JSONObject())
        assertBad(sample().put("games", JSONArray().put("x")))
        assertBad(sample().apply { getJSONArray("games").getJSONObject(0).remove("title") })
        assertBad(sample().apply { getJSONArray("games").getJSONObject(0).put("items", "x") })
        assertBad(sample().apply { getJSONArray("games").getJSONObject(0).put("metadata", "x") })
        assertBad(sample().put("total_size", -1))
        assertBad(sample().apply { remove("file_count") })
        assertBad(sample().put("file_count", 1.5))
        assertBad(withItem { remove("kind") })
        assertBad(withItem { put("size", -1) })
        assertBad(withItem { put("size", "1") })
        assertBad(withItem { put("mtime", "now") })
        assertBad(withItem { put("sha256", "abc") })
        assertBad(sample().apply { getJSONArray("games").getJSONObject(0).getJSONArray("items").put(5) })
    }

    @Test
    fun `duplicate ids are refused`() {
        assertBad(withItem { put("id", "i2") })
        assertBad(sample().apply { getJSONArray("games").put(JSONObject(getJSONArray("games").getJSONObject(0).toString())) })
    }

    @Test
    fun `more than 2000 items is refused`() {
        val items = JSONArray()
        repeat(2001) { items.put(JSONObject(item().toString()).put("id", "i$it")) }
        val manifest = sample().apply { getJSONArray("games").getJSONObject(0).put("items", items) }
        assertBad(manifest)
        items.remove(2000)
        assertEquals(2000, ManifestParser.parse(manifest).items.size)
    }

    @Test
    fun `names are never paths`() {
        for (name in listOf("", ".", "..", "a/b", "a\\b", "a..b", "nul\u0000", "x".repeat(256), "é".repeat(128))) {
            assertBad(withItem { put("name", name) })
        }
        assertTrue(ManifestParser.isSafeFileName("x".repeat(255)))
        assertFalse(ManifestParser.isSafeFileName(3))
    }

    @Test
    fun `an unknown kind is kept and flagged`() {
        val manifest = ManifestParser.parse(withItem { put("kind", "manual_pdf") })
        assertFalse(manifest.items[0].knownKind)
    }
}
