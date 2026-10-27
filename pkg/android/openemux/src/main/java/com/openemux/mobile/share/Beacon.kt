package com.openemux.mobile.share

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** One beacon datagram, parsed. */
data class BeaconMessage(val bye: Boolean, val announcement: Announcement)

/** The UDP beacon payload (spec §5): one JSON object, at most 1024 bytes. */
object BeaconCodec {
    fun encode(bye: Boolean, announcement: Announcement): ByteArray = JSONObject()
        .put("p", ShareProtocol.BEACON_PROTOCOL)
        .put("t", if (bye) "bye" else "hello")
        .put("id", announcement.id)
        .put("name", announcement.name)
        .put("pf", announcement.platform)
        .put("v", announcement.versions.max)
        .put("vmin", announcement.versions.min)
        .put("port", announcement.port)
        .put("fp", announcement.fp)
        .toString().toByteArray(Charsets.UTF_8)

    /** The datagram in [data] (the first [length] bytes), or null to drop it silently. */
    fun parse(data: ByteArray, length: Int = data.size): BeaconMessage? {
        // The size is checked before a single byte is parsed.
        if (length > ShareProtocol.BEACON_MAX_BYTES) return null
        val json = parseJsonObject(data.copyOf(length)) ?: return null
        if (json.opt("p") != ShareProtocol.BEACON_PROTOCOL) return null
        val bye = when (json.opt("t")) {
            "hello" -> false
            "bye" -> true
            else -> return null
        }
        val port = toPort(json.opt("port")) ?: return null
        val id = json.opt("id")
        val fp = json.opt("fp")
        if (!isDeviceId(id) || !isFingerprint(fp)) return null
        return BeaconMessage(
            bye, Announcement.of(id as String, json.opt("name"), json.opt("pf"), json.opt("v"), json.opt("vmin"), port, fp as String),
        )
    }
}

/** A discovery path. [start] throws when the path cannot run at all. */
interface DiscoveryTransport {
    val path: PeerPath
    fun start(announcement: Announcement, peers: PeerList)
    fun stop()
}

/** The socket the beacon talks through; a fake in tests. */
interface BeaconSocket {
    /** Sends [data] out of every multicast-capable IPv4 interface, joining the group on new ones. */
    fun sendToAll(data: ByteArray)
    /** The next datagram as (bytes, source address), or null on a receive timeout. */
    fun receive(): Pair<ByteArray, String>?
    fun close()
}

/** The beacon on a real [MulticastSocket] bound to 53318, TTL 1. */
class MulticastBeaconSocket : BeaconSocket {
    private val group = InetAddress.getByName(ShareProtocol.BEACON_GROUP)
    private val socket = MulticastSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(ShareProtocol.BEACON_PORT))
        timeToLive = ShareProtocol.BEACON_TTL
        soTimeout = RECEIVE_TIMEOUT_MS
    }
    private val joined = mutableSetOf<String>()
    private val buffer = ByteArray(ShareProtocol.BEACON_MAX_BYTES + 1)

    // The send loop and stop()'s bye both come through here.
    @Synchronized
    override fun sendToAll(data: ByteArray) {
        val current = interfaces()
        // An interface that went down lost its membership: join again when it is back.
        joined.retainAll(current.map { it.name }.toSet())
        for (iface in current) {
            if (iface.name !in joined) {
                try {
                    socket.joinGroup(InetSocketAddress(group, ShareProtocol.BEACON_PORT), iface)
                    joined += iface.name
                } catch (e: IOException) {
                    Log.d(TAG, "beacon: ${iface.name} join failed (${e.message})")
                }
            }
            try {
                socket.networkInterface = iface
                socket.send(DatagramPacket(data, data.size, group, ShareProtocol.BEACON_PORT))
            } catch (e: IOException) {
                Log.d(TAG, "beacon: ${iface.name} failed (${e.message})")
            }
        }
    }

    override fun receive(): Pair<ByteArray, String>? {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            buffer.copyOf(packet.length) to (packet.address.hostAddress ?: return null)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() = socket.close()

    private fun interfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { iface ->
            iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                iface.inetAddresses.toList().any { it is Inet4Address }
        }
    } catch (e: IOException) {
        Log.d(TAG, "beacon: no interfaces (${e.message})")
        emptyList()
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MS = 500
    }
}

/**
 * The UDP multicast beacon: a `hello` every 2 s while running, a `bye` on
 * stop, and a receive loop feeding the peer list. The peer's address is the
 * datagram's source, never a field of the payload.
 */
class BeaconTransport(
    private val socketFactory: () -> BeaconSocket = ::MulticastBeaconSocket,
    private val intervalMs: Long = ShareProtocol.BEACON_INTERVAL_MS,
) : DiscoveryTransport {
    override val path = PeerPath.BEACON

    @Volatile private var socket: BeaconSocket? = null
    @Volatile private var running = false
    private var threads = emptyList<Thread>()
    private var announcement: Announcement? = null
    private val wake = Object()

    @Synchronized
    override fun start(announcement: Announcement, peers: PeerList) {
        if (running) return
        val socket = socketFactory()
        this.socket = socket
        this.announcement = announcement
        running = true
        val hello = BeaconCodec.encode(false, announcement)
        threads = listOf(
            Thread({
                while (running) {
                    socket.sendToAll(hello)
                    synchronized(wake) { if (running) wake.wait(intervalMs) }
                }
            }, "share-beacon-send"),
            Thread({ receiveLoop(socket, peers) }, "share-beacon-receive"),
        ).onEach { it.isDaemon = true; it.start() }
    }

    private fun receiveLoop(socket: BeaconSocket, peers: PeerList) {
        while (running) {
            val (data, source) = try {
                socket.receive() ?: continue
            } catch (e: IOException) {
                if (running) Log.w(TAG, "beacon: receive failed", e)
                return
            }
            val message = BeaconCodec.parse(data) ?: continue
            if (message.bye) peers.gone(PeerPath.BEACON, message.announcement.id, message.announcement.fp)
            else peers.seen(PeerPath.BEACON, message.announcement, source)
        }
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        synchronized(wake) { wake.notifyAll() }
        val socket = socket ?: return
        try {
            announcement?.let { socket.sendToAll(BeaconCodec.encode(true, it)) }
        } finally {
            socket.close()
            threads.forEach { it.join(JOIN_TIMEOUT_MS) }
            threads = emptyList()
            this.socket = null
        }
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
