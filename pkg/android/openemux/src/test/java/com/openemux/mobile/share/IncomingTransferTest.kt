package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import com.openemux.mobile.data.RomMeta
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class IncomingTransferTest {
    private val lib = FakeLibrary()
    private val paths = lib.paths
    private val clock = FakeClock()
    private val snes9x = Console.SFC.core("snes9x")
    private val bsnes = Console.SFC.core("bsnes")

    private val romBytes = ByteArray(600 * 1024) { (it % 251).toByte() }
    private val saveBytes = "new save".toByteArray()
    private val stateBytes = "state".toByteArray()
    private val bodies = mapOf("g1i1" to romBytes, "g1i2" to saveBytes, "g1i3" to stateBytes, "g1i4" to PNG, "g1i5" to PNG)

    private val aborts = mutableListOf<Pair<String, Boolean>>()
    private val results = mutableListOf<ReceiveResult>()
    private val progress = mutableListOf<TransferProgress>()

    init {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        lib.installCore(bsnes, "bsnes", "115")
    }

    private val metadata = JSONObject().put("favorite", true).put("cartridge_color", "red").put("core", "bsnes")
        .put("shader", "zfast").put("playtime_seconds", 5400)

    private fun game(mtime: Long = 1_700_000_100L, meta: JSONObject? = metadata) = manifest {
        game("g1", "SFC", "Game", meta) {
            item("rom", "Game.sfc", romBytes, mtime)
            item("battery_save", "Game.srm", saveBytes, mtime)
            item("state", "Game.state1", stateBytes, mtime, state = stateInfo("Snes9x", "1.62.3", 1))
            item("cover", "Game.png", PNG, mtime)
            item("label", "Game.png", PNG, mtime)
        }
    }

    private fun transfer(manifest: Manifest = game(), ops: FileOps = FileOps(), policies: Map<String, Policy> = emptyMap()): IncomingTransfer {
        val plan = planReceive(manifest, paths, CoreIdentities(paths), policies, probe = FakeSpace())
        return IncomingTransfer(
            "offer1", plan, lib, CoreIdentities(paths), clock, ops,
            onProgress = { progress += it }, onDone = { results += it }, onAbort = { id, inUpload -> aborts += id to inUpload },
        )
    }

    private fun IncomingTransfer.put(id: String, body: ByteArray = bodies.getValue(id), length: Long = body.size.toLong()) =
        handlePut(id, ByteArrayInputStream(body), length)

    private fun putAll(transfer: IncomingTransfer, ids: List<String> = transfer.plan.toSend().map { it.itemId }) =
        ids.forEach { transfer.put(it) }

    private fun temps(): List<File> = listOf(paths.base, paths.internal).flatMap { root ->
        root.walk().filter { it.isFile && it.name.startsWith(".") }.toList()
    }

    private fun sfc(dir: (Console) -> File, name: String) = File(dir(Console.SFC), name)

    @Test
    fun `a whole game lands only when its last file arrives, with its mtimes and settings`() {
        File(paths.labels(Console.SFC), "Game.none").put("")
        val transfer = transfer()
        transfer.start()
        transfer.put("g1i1")
        transfer.put("g1i2")
        assertFalse(sfc(paths::roms, "Game.sfc").exists())
        assertEquals(2, temps().count { it.name.endsWith(".tmp") })
        transfer.put("g1i3")
        transfer.put("g1i4")
        transfer.put("g1i5")

        assertEquals(TransferStatus.DONE, transfer.status.value)
        for ((file, bytes) in listOf(
            sfc(paths::roms, "Game.sfc") to romBytes, sfc(paths::saves, "Game.srm") to saveBytes,
            sfc(paths::states, "Game.state1") to stateBytes, sfc(paths::covers, "Game.png") to PNG, sfc(paths::labels, "Game.png") to PNG,
        )) {
            assertArrayEquals(file.name, bytes, file.readBytes())
            assertEquals(1_700_000_100_000L, file.lastModified())
        }
        assertFalse(sfc(paths::labels, "Game.none").exists())
        assertTrue(temps().isEmpty())
        assertEquals(RomMeta(favorite = true, color = "red", core = "bsnes", shader = "zfast"), lib.meta["SFC/Game.sfc"])
        assertEquals("5400", paths.playtime(Console.SFC, "Game").readText())
        val result = results.single()
        assertEquals(TransferStatus.DONE, result.status)
        assertEquals(5, result.placed.size)
        assertEquals(listOf(result.placed), lib.received)
        assertTrue(aborts.isEmpty())
        assertTrue(result.skipped.isEmpty())
        assertEquals(result, transfer.result)
    }

    @Test
    fun `a ROM the library has only takes the favorite`() {
        sfc(paths::roms, "Game.sfc").put(romBytes)
        lib.meta["SFC/Game.sfc"] = RomMeta(color = "blue")
        val transfer = transfer(policies = mapOf("battery_save" to Policy.SKIP, "state" to Policy.SKIP))
        // Only the save, state and art are asked for.
        putAll(transfer)
        assertEquals(RomMeta(favorite = true, color = "blue"), lib.meta["SFC/Game.sfc"])
        assertFalse(paths.playtime(Console.SFC, "Game").exists())
        assertEquals(listOf("g1i1" to "exists_skip"), results.single().skipped)
    }

    @Test
    fun `a transfer with nothing to receive finishes at start`() {
        sfc(paths::roms, "Game.sfc").put(romBytes)
        val manifest = manifest { game("g1", "SFC", "Game", JSONObject().put("favorite", true)) { item("rom", "Game.sfc", romBytes) } }
        val transfer = transfer(manifest)
        transfer.start()
        assertEquals(TransferStatus.DONE, transfer.status.value)
        assertTrue(lib.meta.getValue("SFC/Game.sfc").favorite)
        transfer.start()
        assertEquals(1, results.size)
    }

    @Test
    fun `a newly placed game keeps the settings it already had, and odd metadata is ignored`() {
        lib.meta["SFC/Game.sfc"] = RomMeta(color = "blue", core = "snes9x", shader = "dot")
        val odd = JSONObject().put("favorite", "yes").put("cartridge_color", "default").put("core", "Unknown")
            .put("shader", "nope").put("playtime_seconds", 1.5)
        putAll(transfer(game(meta = odd)).also { it.start() })
        assertEquals(RomMeta(color = "blue", core = "snes9x", shader = "dot"), lib.meta["SFC/Game.sfc"])
        assertFalse(paths.playtime(Console.SFC, "Game").exists())
        val none = manifest { game("g1", "SFC") { item("rom", "Plain.sfc", "p".toByteArray()) } }
        transfer(none).put("g1i1", "p".toByteArray())
        assertNull(lib.meta["SFC/Plain.sfc"])
    }

    @Test
    fun `a broken library hook does not undo the transfer`() {
        lib.failOnReceived = true
        val transfer = transfer()
        putAll(transfer)
        assertEquals(TransferStatus.DONE, transfer.status.value)
        assertTrue(sfc(paths::roms, "Game.sfc").exists())
    }

    @Test
    fun `cancel from this side, mid-file or between files, leaves nothing behind`() {
        val between = transfer()
        between.put("g1i1")
        between.cancel()
        between.cancel()
        assertEquals(TransferStatus.CANCELLED, between.status.value)
        assertEquals(listOf("offer1" to false), aborts)
        assertTrue(temps().isEmpty())
        assertFalse(paths.roms(Console.SFC).exists())
        assertEquals(ErrorCode.CANCELLED, assertThrows(ShareError::class.java) { between.put("g1i2") }.code)

        val mid = transfer()
        val cancelling = object : InputStream() {
            var sent = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent > 0) mid.cancel()
                sent += len
                return len
            }
        }
        val error = assertThrows(ShareError::class.java) { mid.handlePut("g1i1", cancelling, romBytes.size.toLong()) }
        assertEquals(ErrorCode.CANCELLED, error.code)
        assertTrue(temps().isEmpty())
        assertEquals(ErrorCode.CANCELLED, mid.result!!.error)
    }

    @Test
    fun `a cancel from the sender ends it without telling it back`() {
        val transfer = transfer()
        transfer.put("g1i1")
        transfer.onRemoteCancel()
        assertEquals(TransferStatus.CANCELLED, transfer.status.value)
        assertTrue(aborts.isEmpty())
        assertTrue(temps().isEmpty())
        assertEquals(1, results.size)
    }

    @Test
    fun `a cancel while a file is being flushed discards that file too`() {
        lateinit var transfer: IncomingTransfer
        val ops = object : FileOps() {
            override fun sync(stream: OutputStream) {
                super.sync(stream)
                transfer.cancel()
            }
        }
        transfer = transfer(ops = ops)
        assertEquals(ErrorCode.CANCELLED, assertThrows(ShareError::class.java) { transfer.put("g1i1") }.code)
        assertTrue(temps().isEmpty())
        assertFalse(paths.roms(Console.SFC).exists())
    }

    @Test
    fun `a short body, a bad hash or art that is not an image abort the whole transfer`() {
        val cases = listOf<(IncomingTransfer) -> Unit>(
            { it.put("g1i2", saveBytes.copyOf(3), saveBytes.size.toLong()) },
            { it.put("g1i2", "bad save".toByteArray()) },
        )
        for (case in cases) {
            val transfer = transfer()
            transfer.put("g1i1")
            val error = assertThrows(ShareError::class.java) { case(transfer) }
            assertEquals(ErrorCode.BAD_REQUEST, error.code)
            assertEquals(TransferStatus.FAILED, transfer.status.value)
            assertEquals("offer1" to true, aborts.last())
            assertTrue(temps().isEmpty())
            assertFalse(paths.roms(Console.SFC).exists())
        }
        val art = manifest {
            game("g1", "SFC") {
                item("rom", "Game.sfc", romBytes)
                item("cover", "Game.png", "not an image".toByteArray(), hash = false)
            }
        }
        val badCover = transfer(art)
        badCover.put("g1i1")
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ShareError::class.java) { badCover.put("g1i2", "not an image".toByteArray()) }.code)
        val jpegLabel = manifest {
            game("g1", "SFC") {
                item("rom", "Game.sfc", romBytes)
                item("label", "Game.png", JPEG, hash = false)
            }
        }
        val badLabel = transfer(jpegLabel)
        assertEquals("label is not a PNG", assertThrows(ShareError::class.java) { badLabel.put("g1i2", JPEG) }.message)
        val webpCover = manifest { game("g1", "SFC") { item("rom", "Game.sfc", romBytes); item("cover", "Game.webp", WEBP) } }
        val goodWebp = transfer(webpCover)
        goodWebp.put("g1i1")
        goodWebp.put("g1i2", WEBP)
        assertEquals(TransferStatus.DONE, goodWebp.status.value)
    }

    @Test
    fun `a write failure is no_space, a read failure unreachable`() {
        val fullDisk = object : FileOps() {
            override fun output(file: File): OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw IOException("No space left on device")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("No space left on device")
            }
        }
        val full = transfer(ops = fullDisk)
        assertEquals(ErrorCode.NO_SPACE, assertThrows(ShareError::class.java) { full.put("g1i1") }.code)
        assertEquals(ErrorCode.NO_SPACE, full.result!!.error)
        assertTrue(temps().isEmpty())

        val cut = transfer()
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("reset")
        }
        assertEquals(ErrorCode.UNREACHABLE, assertThrows(ShareError::class.java) { cut.handlePut("g1i1", broken, romBytes.size.toLong()) }.code)
        assertEquals(TransferStatus.FAILED, cut.status.value)
    }

    @Test
    fun `uploads the plan does not expect are refused and the transfer goes on`() {
        sfc(paths::labels, "Game.png").put(PNG)
        val transfer = transfer()
        transfer.put("g1i1")
        for (attempt in listOf<() -> Unit>(
            { transfer.put("g1i1") },
            { transfer.put("nope", PNG) },
            { transfer.put("g1i5") },
            { transfer.put("g1i2", saveBytes, 99) },
        )) {
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ShareError::class.java) { attempt() }.code)
        }
        assertEquals(TransferStatus.RECEIVING, transfer.status.value)
        putAll(transfer, listOf("g1i2", "g1i3", "g1i4"))
        assertEquals(TransferStatus.DONE, transfer.status.value)
        assertEquals(listOf("g1i5" to "exists_skip"), results.single().skipped)
    }

    /** Moves fail on the [failAt]-th call; links are unsupported when [noLinks]. */
    private class FlakyOps(val failAt: Int = -1, val noLinks: Boolean = false, val noMtime: Boolean = false) : FileOps() {
        var moves = 0
        override fun link(target: File, existing: File) {
            if (noLinks) throw UnsupportedOperationException("no links here")
            super.link(target, existing)
        }

        override fun move(from: File, to: File, replace: Boolean) {
            if (++moves == failAt) throw IOException("move $moves failed")
            super.move(from, to, replace)
        }

        override fun setMtime(file: File, millis: Long) {
            if (noMtime) throw IOException("operation not permitted")
            super.setMtime(file, millis)
        }
    }

    @Test
    fun `a commit that fails halfway is rolled back and what it replaced comes back intact`() {
        val oldSave = "old save".toByteArray()
        sfc(paths::saves, "Game.srm").put(oldSave, mtime = 1_600_000_000)
        val oldState = "old state".toByteArray()
        sfc(paths::states, "Game.state1").put(oldState, mtime = 1_600_000_000)
        // Moves: save aside (1), save in (2), state aside (3), state in (4): the fourth fails.
        val ops = FlakyOps(failAt = 4)
        val transfer = transfer(ops = ops)
        transfer.put("g1i1")
        transfer.put("g1i2")
        transfer.put("g1i3")
        transfer.put("g1i4")
        assertEquals(ErrorCode.INTERNAL, assertThrows(ShareError::class.java) { transfer.put("g1i5") }.code)
        assertEquals(TransferStatus.FAILED, transfer.status.value)
        assertArrayEquals(oldSave, sfc(paths::saves, "Game.srm").readBytes())
        assertArrayEquals(oldState, sfc(paths::states, "Game.state1").readBytes())
        assertEquals(1_600_000_000_000L, sfc(paths::saves, "Game.srm").lastModified())
        assertFalse(sfc(paths::roms, "Game.sfc").exists())
        assertTrue(temps().isEmpty())
        assertEquals(listOf("offer1" to true), aborts)
        assertTrue(lib.received.isEmpty())
        assertEquals(ErrorCode.INTERNAL, results.single().error)
    }

    @Test
    fun `a replacing commit reports what it replaced`() {
        sfc(paths::saves, "Game.srm").put("old", mtime = 1_600_000_000)
        val transfer = transfer()
        putAll(transfer)
        assertEquals(listOf(sfc(paths::saves, "Game.srm")), results.single().replaced)
        assertArrayEquals(saveBytes, sfc(paths::saves, "Game.srm").readBytes())
        assertTrue(temps().isEmpty())
    }

    @Test
    fun `a target that appeared since the plan aborts the commit`() {
        val transfer = transfer()
        putAll(transfer, listOf("g1i1", "g1i2", "g1i3", "g1i4"))
        sfc(paths::labels, "Game.png").put("the user's own")
        assertEquals(ErrorCode.INTERNAL, assertThrows(ShareError::class.java) { transfer.put("g1i5") }.code)
        assertEquals("the user's own", sfc(paths::labels, "Game.png").readText())
        assertFalse(sfc(paths::roms, "Game.sfc").exists())

        // The same without hard links.
        sfc(paths::labels, "Game.png").delete()
        val noLinks = transfer(ops = FlakyOps(noLinks = true))
        putAll(noLinks, listOf("g1i1", "g1i2", "g1i3", "g1i4"))
        sfc(paths::labels, "Game.png").put("the user's own")
        assertEquals(ErrorCode.INTERNAL, assertThrows(ShareError::class.java) { noLinks.put("g1i5") }.code)
        assertFalse(sfc(paths::roms, "Game.sfc").exists())
    }

    @Test
    fun `without hard links files are moved, and a refused mtime is only reported`() {
        val ops = FlakyOps(noLinks = true, noMtime = true)
        val transfer = transfer(ops = ops)
        putAll(transfer)
        val result = results.single()
        assertEquals(TransferStatus.DONE, result.status)
        assertEquals(result.placed, result.mtimeNotKept)
        assertArrayEquals(romBytes, sfc(paths::roms, "Game.sfc").readBytes())
        assertTrue(temps().isEmpty())
    }

    @Test
    fun `a link the filesystem refuses falls back to a move`() {
        val ops = object : FileOps() {
            override fun link(target: File, existing: File) = throw java.nio.file.FileSystemException("EPERM")
        }
        putAll(transfer(ops = ops))
        assertEquals(TransferStatus.DONE, results.single().status)
    }

    @Test
    fun `a sender that goes quiet is timed out`() {
        val transfer = transfer()
        transfer.put("g1i1")
        clock.now += ShareProtocol.IDLE_TIMEOUT_MS
        assertFalse(transfer.sweep(clock.now))
        clock.now += 1
        assertTrue(transfer.sweep(clock.now))
        assertEquals(ErrorCode.IDLE_TIMEOUT, transfer.result!!.error)
        assertEquals(listOf("offer1" to false), aborts)
        assertTrue(transfer.sweep(clock.now))
        assertTrue(temps().isEmpty())
    }

    @Test
    fun `the watchdog sweeps on its own and stops with the transfer`() {
        val transfer = transfer()
        transfer.startWatchdog(5)
        clock.now += ShareProtocol.IDLE_TIMEOUT_MS + 1
        val deadline = System.currentTimeMillis() + 5_000
        while (transfer.status.value == TransferStatus.RECEIVING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(TransferStatus.FAILED, transfer.status.value)

        val finished = transfer(manifest { game("g1", "SFC") { item("rom", "X.sfc", "x".toByteArray()) } })
        finished.startWatchdog(5)
        finished.put("g1i1", "x".toByteArray())
        assertEquals(TransferStatus.DONE, finished.status.value)
    }

    @Test
    fun `progress is throttled, with a last call per file`() {
        val transfer = transfer()
        transfer.put("g1i1")
        // 600 KiB in 256 KiB chunks: the first chunk and the final call; the clock never moved.
        assertEquals(2, progress.size)
        assertEquals(romBytes.size.toLong(), progress.last().bytes)
        assertEquals(1, progress.last().filesDone)
        assertEquals(5, progress.last().fileCount)
        clock.now += IncomingTransfer.PROGRESS_INTERVAL_MS
        transfer.put("g1i2")
        assertEquals(4, progress.size)
        assertEquals(romBytes.size.toLong() + saveBytes.size, progress.last().doneBytes)
    }

    @Test
    fun `stale temporary files are cleared from the folders a transfer writes to`() {
        val dir = paths.roms(Console.SFC)
        val stale = File(dir, ".Old.sfc.0a0b0c0d.tmp").put("x", mtime = System.currentTimeMillis() / 1000 - 7200)
        val fresh = File(dir, ".New.sfc.0a0b0c0d.tmp").put("x", mtime = System.currentTimeMillis() / 1000)
        val notTemp = File(dir, ".hidden").put("x", mtime = 1)
        val notHidden = File(dir, "Old.tmp").put("x", mtime = 1)
        transfer()
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(notTemp.exists())
        assertTrue(notHidden.exists())
    }

    @Test
    fun `a staged file is never taken for a game, a state or a save`() {
        val tmp = ".Game.sfc.0a0b0c0d.tmp"
        assertTrue(Console.entries.none { "tmp" in it.extensions })
        assertFalse(Units.isBatterySave(".Game.srm.0a0b0c0d.tmp", "Game", Console.SFC))
        val rom = lib.rom(Console.SFC, "Game.sfc")
        File(paths.states(Console.SFC), ".Game.state1.0a0b0c0d.tmp").put("x")
        assertTrue(lib.states(rom).isEmpty())
        assertTrue(tmp.startsWith("."))
    }
}
