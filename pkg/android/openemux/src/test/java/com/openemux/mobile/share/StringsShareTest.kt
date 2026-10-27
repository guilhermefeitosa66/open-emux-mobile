package com.openemux.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every Share nearby string exists in every locale, with the same placeholders. */
class StringsShareTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%\\d\\$[sd]")

    /** key -> the placeholders of each of its forms (one for a string, one per quantity for plurals). */
    private fun read(folder: String): Map<String, List<Set<String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$folder/strings_share.xml"))
        val root = doc.documentElement
        val out = linkedMapOf<String, List<Set<String>>>()
        val strings = root.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out[e.getAttribute("name")] = listOf(placeholder.findAll(e.textContent).map { it.value }.toSet())
        }
        val plurals = root.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i) as Element
            val items = e.getElementsByTagName("item")
            out[e.getAttribute("name")] = (0 until items.length).map { j ->
                val text = items.item(j).textContent
                assertTrue("${e.getAttribute("name")} in $folder is empty", text.isNotBlank())
                placeholder.findAll(text).map { it.value }.toSet()
            }
        }
        return out
    }

    @Test
    fun `every locale has every key with the English placeholders`() {
        val english = read("values")
        assertTrue(english.size > 150)
        for (locale in listOf("de", "es", "fr", "ja", "pt-rBR", "ta", "zh-rCN")) {
            val other = read("values-$locale")
            assertEquals(locale, english.keys, other.keys)
            for ((key, forms) in english) {
                // A plural's "one" may spell the number out; its "other" form carries them all.
                val expected = forms.last()
                other.getValue(key).forEach { assertTrue("$key in $locale: $it vs $expected", expected.containsAll(it)) }
                assertEquals("$key in $locale", expected, other.getValue(key).last())
            }
        }
    }
}
