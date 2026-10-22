package com.openemux.mobile.data

import android.content.Context
import android.net.Uri
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Box art from the openemux-artwork mirror, matched by name against the
 * No-Intro titles it holds (shipped as one list per console).
 */
class Covers(private val context: Context) {
    private val indexes = ConcurrentHashMap<Console, Index>()
    /** "" marks a ROM with no match: ConcurrentHashMap takes no nulls. */
    private val cache = ConcurrentHashMap<String, String>()

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /** Warms the image cache with every ROM's box art, so the library fills in without waiting. */
    suspend fun prefetch(roms: List<Rom>) = coroutineScope {
        val withArt = roms.mapNotNull { rom -> urlFor(rom)?.let { rom to it } }
        val missing = roms.size - withArt.size
        val cached = withArt.filter { (_, url) -> context.imageLoader.diskCache?.openSnapshot(url)?.use { true } == true }
        val todo = withArt - cached.toSet()
        val done = AtomicInteger(missing + cached.size)
        val offline = AtomicInteger()
        _progress.value = SyncProgress(running = todo.isNotEmpty(), total = roms.size, done = done.get(), missing = missing)
        if (todo.isEmpty()) return@coroutineScope
        val gate = Semaphore(4)
        todo.map { (rom, url) ->
            async {
                gate.withPermit {
                    val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).build())
                    if ((result as? ErrorResult)?.throwable is IOException) offline.incrementAndGet()
                    _progress.value = _progress.value.copy(done = done.incrementAndGet(), current = rom.title)
                }
            }
        }.awaitAll()
        _progress.value = SyncProgress(
            total = roms.size, done = done.get(), missing = missing,
            paused = if (offline.get() > 0) SyncPause.Offline else null,
            finishedAt = if (offline.get() > 0) 0L else System.currentTimeMillis(),
        )
    }

    private class Index(val exact: Map<String, List<String>>, val tokens: List<Pair<Set<String>, String>>)

    fun urlFor(rom: Rom): String? = cache.getOrPut(rom.key) {
        match(rom.console, rom.file.nameWithoutExtension)?.let { name ->
            "https://raw.githubusercontent.com/guilhermefeitosa66/openemux-artwork/main/" +
                "${rom.console.artworkDir}/${Uri.encode(name)}.webp"
        }.orEmpty()
    }.ifEmpty { null }

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
