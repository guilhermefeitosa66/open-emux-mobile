package com.openemux.mobile.data

import android.content.Context
import android.net.Uri
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Box art from the openemux-artwork mirror, matched by name against the
 * No-Intro titles it holds (shipped as one list per console).
 */
class Covers(private val context: Context) {
    private val indexes = ConcurrentHashMap<Console, Index>()
    private val cache = ConcurrentHashMap<String, String?>()

    private class Index(val exact: Map<String, List<String>>, val tokens: List<Pair<Set<String>, String>>)

    fun urlFor(rom: Rom): String? = cache.getOrPut(rom.key) {
        match(rom.console, rom.file.nameWithoutExtension)?.let { name ->
            "https://raw.githubusercontent.com/guilhermefeitosa66/openemux-artwork/main/" +
                "${rom.console.artworkDir}/${Uri.encode(name)}.webp"
        }
    }

    private fun index(console: Console): Index = indexes.getOrPut(console) {
        val names = context.assets.open("covers/covers_${console.id.lowercase()}.txt")
            .bufferedReader().readLines().filter { it.isNotBlank() }
        Index(
            exact = names.groupBy { normalize(it) },
            tokens = names.map { tokens(it) to it },
        )
    }

    private fun match(console: Console, fileName: String): String? {
        val idx = index(console)
        idx.exact[normalize(fileName)]?.let { return preferRegion(it) }
        val wanted = tokens(fileName)
        if (wanted.isEmpty()) return null
        var best: String? = null
        var bestScore = 0.0
        for ((candidate, name) in idx.tokens) {
            if (candidate.isEmpty()) continue
            val common = wanted.count { it in candidate }
            val score = common.toDouble() / (wanted.size + candidate.size - common)
            if (score > bestScore || (score == bestScore && best != null && regionRank(name) < regionRank(best))) {
                bestScore = score
                best = name
            }
        }
        return if (bestScore >= 0.5) best else null
    }

    private fun preferRegion(names: List<String>) = names.minBy { regionRank(it) }

    private fun regionRank(name: String) = when {
        "(USA" in name -> 0
        "(World" in name -> 1
        "(Europe" in name || "(USA, Europe" in name -> 2
        "(Brazil" in name -> 3
        "(Japan" in name -> 5
        else -> 4
    } + (if ("Beta" in name || "Proto" in name || "Demo" in name) 10 else 0)

    companion object {
        private val tags = Regex("[(\\[][^)\\]]*[)\\]]")
        private val stop = setOf("the", "a", "of", "and", "version", "u", "e", "j")

        fun normalize(name: String): String {
            val noTags = name.replace(tags, " ")
            val ascii = Normalizer.normalize(noTags, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            return ascii.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "")
        }

        fun tokens(name: String): Set<String> {
            val noTags = name.replace(tags, " ")
            val ascii = Normalizer.normalize(noTags, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            return ascii.lowercase().replace("&", " and ")
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() && it !in stop }
                .toSet()
        }
    }
}
