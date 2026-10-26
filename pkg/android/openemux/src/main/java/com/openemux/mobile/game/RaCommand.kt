package com.openemux.mobile.game

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object GameExtras {
    const val TITLE = "OE_TITLE"
    const val CONSOLE = "OE_CONSOLE"
    const val CART = "OE_CART"
    const val STATES_DIR = "OE_STATES_DIR"
    const val ROM_BASE = "OE_ROM_BASE"
    const val PLAYTIME = "OE_PLAYTIME"
    const val SHADERS_DIR = "OE_SHADERS_DIR"
    const val SHADER = "OE_SHADER"
    const val CORE_NAME = "OE_CORE_NAME"
    const val LOAD_SLOT = "OE_LOAD_SLOT"
    const val HARDCORE = "OE_HARDCORE"
}

/**
 * Talks to the running engine through RetroArch's network command
 * interface (network_cmd_enable), which the engine polls on its own
 * thread every frame, paused or not. Call from a background thread.
 */
object RaCommand {
    private const val PORT = 55355
    // The engine binds IPv4 only; getLoopbackAddress() may be ::1.
    private val host: InetAddress by lazy { InetAddress.getByName("127.0.0.1") }

    fun send(command: String) {
        runCatching {
            DatagramSocket().use { socket ->
                val data = command.toByteArray()
                socket.send(DatagramPacket(data, data.size, host, PORT))
            }
        }
    }

    fun ask(command: String, timeoutMs: Int = 400): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val data = command.toByteArray()
            socket.send(DatagramPacket(data, data.size, host, PORT))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                String(packet.data, 0, packet.length).trim()
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }.getOrNull()

    // The reply is "GET_STATUS <PAUSED|PLAYING> <system>,<file>": match the state word, not a file name that holds it.
    fun isPaused(): Boolean = ask("GET_STATUS")?.startsWith("GET_STATUS PAUSED") == true

    /** True only on a reply saying the content runs: no reply (a timeout) is not taken as running. */
    fun isPlaying(): Boolean = ask("GET_STATUS")?.startsWith("GET_STATUS PLAYING") == true

    fun resume() {
        if (isPaused()) send("PAUSE_TOGGLE")
    }
}
