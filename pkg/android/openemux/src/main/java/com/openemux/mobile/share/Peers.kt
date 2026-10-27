package com.openemux.mobile.share

/** How a peer was found. Each path lives and dies on its own (spec §6). */
enum class PeerPath { MDNS, BEACON, MANUAL }

data class Peer(
    val id: String,
    val fp: String,
    val name: String,
    val platform: String,
    val versions: VersionRange,
    val port: Int,
    /** IPv4 first, then IPv6, each once. */
    val addresses: List<String>,
    val paths: Set<PeerPath>,
) {
    val compatible: Boolean get() = versions.compatible
}

/**
 * The merged list of devices discovery found, keyed by `(id, fp)`: a device
 * seen by mDNS and by the beacon is one entry; a beacon reusing a known id with
 * another certificate is a second entry, never a replacement. Thread-safe.
 * [clock] is in milliseconds.
 */
class PeerList(
    private val ownId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (List<Peer>) -> Unit = {},
) {
    private class Entry(var info: Announcement) {
        val seen = mutableMapOf<PeerPath, Long>()
        val addresses = mutableMapOf<PeerPath, MutableList<String>>()
    }

    private val entries = LinkedHashMap<Pair<String, String>, Entry>()

    /** [announcement] reached us by [path], from [address] when known. */
    fun seen(path: PeerPath, announcement: Announcement, address: String? = null) {
        if (announcement.id == ownId) return
        val snapshot = synchronized(this) {
            val key = announcement.id to announcement.fp
            val entry = entries.getOrPut(key) { Entry(announcement) }
            var changed = path !in entry.seen || entry.info != announcement
            entry.info = announcement
            entry.seen[path] = clock()
            if (address != null) {
                val list = entry.addresses.getOrPut(path) { mutableListOf() }
                if (address !in list) {
                    list += address
                    changed = true
                }
            }
            if (changed) peersLocked() else null
        }
        snapshot?.let(onChange)
    }

    /** Forgets [path] for the device (every certificate of it when [fp] is null). */
    fun gone(path: PeerPath, id: String, fp: String? = null) {
        val snapshot = synchronized(this) {
            var changed = false
            for (key in entries.keys.toList()) {
                if (key.first == id && (fp == null || key.second == fp)) changed = dropLocked(key, path) || changed
            }
            if (changed) peersLocked() else null
        }
        snapshot?.let(onChange)
    }

    /** Drops the beacon paths that went quiet for [ShareProtocol.PEER_EXPIRY_MS]. */
    fun expire(now: Long = clock()) {
        val snapshot = synchronized(this) {
            var changed = false
            for ((key, entry) in entries.entries.toList()) {
                val seen = entry.seen[PeerPath.BEACON] ?: continue
                if (now - seen > ShareProtocol.PEER_EXPIRY_MS) changed = dropLocked(key, PeerPath.BEACON) || changed
            }
            if (changed) peersLocked() else null
        }
        snapshot?.let(onChange)
    }

    /** Forgets everything a transport found (discovery stopped); manual entries stay. */
    fun clearDiscovered() {
        val snapshot = synchronized(this) {
            var changed = false
            for (key in entries.keys.toList()) {
                changed = dropLocked(key, PeerPath.MDNS) or dropLocked(key, PeerPath.BEACON) || changed
            }
            if (changed) peersLocked() else null
        }
        snapshot?.let(onChange)
    }

    @Synchronized
    fun peers(): List<Peer> = peersLocked()

    private fun dropLocked(key: Pair<String, String>, path: PeerPath): Boolean {
        val entry = entries[key] ?: return false
        if (entry.seen.remove(path) == null) return false
        entry.addresses.remove(path)
        if (entry.seen.isEmpty()) entries.remove(key)
        return true
    }

    private fun peersLocked(): List<Peer> = entries.values.map { entry ->
        val info = entry.info
        Peer(
            info.id, info.fp, info.name, info.platform, info.versions, info.port,
            orderedAddresses(entry.addresses.values.flatten()), entry.seen.keys.toSet(),
        )
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    companion object {
        fun orderedAddresses(addresses: List<String>): List<String> {
            val unique = addresses.distinct()
            return unique.filter { ':' !in it } + unique.filter { ':' in it }
        }
    }
}
