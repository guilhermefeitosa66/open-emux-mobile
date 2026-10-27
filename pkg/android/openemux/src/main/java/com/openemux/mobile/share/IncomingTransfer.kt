package com.openemux.mobile.share

import android.util.Log
import com.openemux.mobile.data.CART_COLORS
import com.openemux.mobile.data.SHADERS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class TransferStatus { RECEIVING, DONE, FAILED, CANCELLED }

/** One file's progress, and the whole transfer's. */
data class TransferProgress(
    val itemId: String,
    val name: String,
    val kind: String,
    val bytes: Long,
    val size: Long,
    val doneBytes: Long,
    val totalBytes: Long,
    val filesDone: Int,
    val fileCount: Int,
)

/** How a receive ended. [skipped] pairs each item not asked for with its reason; [error] is an [ErrorCode]. */
data class ReceiveResult(
    val status: TransferStatus,
    val placed: List<File> = emptyList(),
    val replaced: List<File> = emptyList(),
    val skipped: List<Pair<String, String?>> = emptyList(),
    val mtimeNotKept: List<File> = emptyList(),
    val error: String? = null,
)

/** The filesystem calls a transfer makes, open so the tests can make each one fail. */
open class FileOps {
    open fun output(file: File): OutputStream = FileOutputStream(file)

    /** Flushes [stream] to the disk before the file counts as received. */
    open fun sync(stream: OutputStream) {
        (stream as? FileOutputStream)?.fd?.sync()
    }

    open fun link(target: File, existing: File) {
        Files.createLink(target.toPath(), existing.toPath())
    }

    open fun move(from: File, to: File, replace: Boolean) {
        if (replace) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } else {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    open fun setMtime(file: File, millis: Long) {
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(millis))
    }

    open fun delete(file: File): Boolean = file.delete()
}

/**
 * Carries out one accepted [ReceivePlan]. Each upload is staged as a hidden
 * temporary file in its target's own folder and checked (length, sha256, the
 * header of art). Nothing appears in the library until the last file has
 * arrived: then everything is committed at once, in the plan's order, each
 * file it replaces moved aside first. A commit that fails halfway is rolled
 * back, and a cancel from either side, a broken connection, a bad hash, a full
 * disk or a sender that goes quiet discard everything staged. Either way the
 * library is left as it was.
 *
 * [handlePut] runs on the server's connection threads; everything shared is
 * under one lock. The callbacks run on those threads (or the watchdog's).
 * [onAbort] hears when this side ends the transfer, so the offer ends for the
 * sender too; `inUpload` is true when the upload that failed will carry the
 * error back itself.
 */
class IncomingTransfer(
    val offerId: String,
    val plan: ReceivePlan,
    private val library: ShareLibrary,
    private val cores: CoreIdentities = CoreIdentities(library.paths),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ops: FileOps = FileOps(),
    var onProgress: (TransferProgress) -> Unit = {},
    var onDone: (ReceiveResult) -> Unit = {},
    var onAbort: (offerId: String, inUpload: Boolean) -> Unit = { _, _ -> },
) {
    private val sends = plan.toSend().associateBy { it.itemId }
    private val staged = linkedMapOf<String, File>()
    private val uploading = mutableSetOf<String>()
    /** Folders made for this transfer, in creation order, removed again on abort. */
    private val createdDirs = linkedSetOf<File>()
    private val totalBytes = sends.values.sumOf { it.size }
    private var doneBytes = 0L
    private var lastActivity = clock()
    private var lastProgress: Long? = null
    private val lock = Any()
    private var watchdog: CountDownLatch? = null

    private val _status = MutableStateFlow(TransferStatus.RECEIVING)
    val status: StateFlow<TransferStatus> = _status.asStateFlow()

    @Volatile var result: ReceiveResult? = null
        private set

    init {
        sweepStaleTemps()
    }

    private class Cancelled : Exception()
    private class ReadFailed(cause: IOException) : Exception(cause)

    /** Finishes at once when nothing is to be sent; otherwise waits for the uploads. */
    fun start() {
        val committing = synchronized(lock) { (_status.value == TransferStatus.RECEIVING && sends.isEmpty()).also { if (it) commit() } }
        if (committing) afterCommit()
    }

    /** Checks the idle timeout every [intervalMs] until the transfer ends. */
    fun startWatchdog(intervalMs: Long = WATCHDOG_INTERVAL_MS) {
        val stop = CountDownLatch(1)
        synchronized(lock) { watchdog = stop }
        Thread({
            while (!stop.await(intervalMs, TimeUnit.MILLISECONDS)) {
                if (sweep(clock())) return@Thread
            }
        }, "share-receive-watchdog").apply { isDaemon = true; start() }
    }

    // -- uploads -------------------------------------------------------------

    /**
     * Stages one upload; the last one commits the whole transfer. Throws a
     * [ShareError] for the sender: `bad_request` for an upload this plan does
     * not expect (the transfer goes on), the transfer's own failure once it was
     * aborted, `cancelled` once it is over.
     */
    fun handlePut(itemId: String, body: InputStream, length: Long) {
        val decision = synchronized(lock) {
            checkLive()
            val decision = sends[itemId]
            if (decision == null || itemId in staged || itemId in uploading) throw ShareError.badRequest("item is not expected")
            if (length != decision.size) throw ShareError.badRequest("Content-Length differs from the offered size")
            uploading += itemId
            lastActivity = clock()
            noteDirs(decision.target!!.parentFile!!)
            decision
        }
        val target = decision.target!!
        val tmp = File(target.parentFile, ".${target.name}.${randomSuffix()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            target.parentFile!!.mkdirs()
            ops.output(tmp).use { out ->
                val buffer = ByteArray(READ_CHUNK)
                while (true) {
                    val n = try {
                        body.read(buffer)
                    } catch (e: IOException) {
                        throw ReadFailed(e)
                    }
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    received += n
                    synchronized(lock) {
                        if (_status.value != TransferStatus.RECEIVING) throw Cancelled()
                        lastActivity = clock()
                        doneBytes += n
                    }
                    progress(decision, received, final = false)
                }
                ops.sync(out)
            }
        } catch (_: Cancelled) {
            tmp.delete()
            uploadEnded(itemId)
            throw closed()
        } catch (e: ReadFailed) {
            tmp.delete()
            uploadEnded(itemId)
            throw abort(ErrorCode.UNREACHABLE, "upload interrupted: ${e.cause?.message}", inUpload = true)
        } catch (e: IOException) {
            // Writing failed: the disk is full, or as good as.
            tmp.delete()
            uploadEnded(itemId)
            throw abort(ErrorCode.NO_SPACE, "write failed: ${e.message}", inUpload = true)
        }
        val problem = when {
            received != decision.size -> "short upload"
            decision.sha256 != null && digest.digest().toHex() != decision.sha256 -> "sha256 mismatch"
            decision.kind == Item.COVER && Units.imageKind(tmp) == null -> "art is not an image"
            decision.kind == Item.LABEL && Units.imageKind(tmp) != "png" -> "label is not a PNG"
            else -> null
        }
        synchronized(lock) {
            uploading -= itemId
            if (_status.value != TransferStatus.RECEIVING) {
                tmp.delete()
                removeCreatedDirs()
                throw closed()
            }
            staged[itemId] = tmp
        }
        if (problem != null) throw abort(ErrorCode.BAD_REQUEST, problem, inUpload = true)
        progress(decision, received, final = true)
        val committing = synchronized(lock) {
            (_status.value == TransferStatus.RECEIVING && staged.size == sends.size).also { if (it) commit() }
        }
        if (committing) {
            afterCommit()
            if (_status.value == TransferStatus.FAILED) throw ShareError(ErrorCode.INTERNAL, "commit failed")
        }
    }

    private fun uploadEnded(itemId: String) = synchronized(lock) {
        uploading -= itemId
        if (_status.value != TransferStatus.RECEIVING) removeCreatedDirs()
    }

    private fun checkLive() {
        if (_status.value != TransferStatus.RECEIVING) throw closed()
    }

    /** Whatever ended it, for the sender this offer is over. */
    private fun closed() = ShareError(ErrorCode.CANCELLED, "transfer ended")

    private fun noteDirs(dir: File) {
        val missing = generateSequence(dir) { it.parentFile }.takeWhile { !it.exists() }.toList()
        createdDirs += missing.asReversed()
    }

    private fun progress(decision: Decision, bytes: Long, final: Boolean) {
        val now = clock()
        val snapshot = synchronized(lock) {
            val last = lastProgress
            if (!final && last != null && now - last < PROGRESS_INTERVAL_MS) return
            lastProgress = now
            TransferProgress(
                decision.itemId, decision.name, decision.kind, bytes, decision.size, doneBytes, totalBytes,
                staged.size, sends.size,
            )
        }
        onProgress(snapshot)
    }

    // -- ending ----------------------------------------------------------------

    /** The receiver's user cancelled; idempotent. */
    fun cancel() = end(TransferStatus.CANCELLED, ErrorCode.CANCELLED, notify = true)

    /** The sender cancelled, or the offer is gone; idempotent. */
    fun onRemoteCancel() = end(TransferStatus.CANCELLED, ErrorCode.CANCELLED, notify = false)

    /** Aborts after [ShareProtocol.IDLE_TIMEOUT_MS] without data; true once the transfer is over. */
    fun sweep(now: Long): Boolean {
        val idle = synchronized(lock) {
            if (_status.value != TransferStatus.RECEIVING) return true
            now - lastActivity > ShareProtocol.IDLE_TIMEOUT_MS
        }
        if (idle) end(TransferStatus.FAILED, ErrorCode.IDLE_TIMEOUT, notify = true)
        return idle
    }

    private fun abort(code: String, message: String, inUpload: Boolean): ShareError {
        Log.w(TAG, "share receive $offerId aborted: $code ($message)")
        end(TransferStatus.FAILED, code, notify = true, inUpload = inUpload)
        return ShareError(code, message)
    }

    private fun end(status: TransferStatus, code: String, notify: Boolean, inUpload: Boolean = false) {
        synchronized(lock) {
            if (_status.value != TransferStatus.RECEIVING) return
            discardAll()
            finish(status, error = code)
        }
        if (notify) onAbort(offerId, inUpload)
        onDone(result!!)
    }

    private fun discardAll() {
        staged.values.forEach { it.delete() }
        staged.clear()
        removeCreatedDirs()
    }

    private fun removeCreatedDirs() {
        // Not empty (an upload still finishing will discard into it) or gone: either way left alone.
        createdDirs.toList().asReversed().forEach { it.delete() }
    }

    private fun finish(
        status: TransferStatus,
        placed: List<File> = emptyList(),
        replaced: List<File> = emptyList(),
        mtimeNotKept: List<File> = emptyList(),
        error: String? = null,
    ) {
        val skipped = plan.decisions.filter { it.itemId !in sends }.map { it.itemId to it.reason }
        result = ReceiveResult(status, placed, replaced, skipped, mtimeNotKept, error)
        _status.value = status
        watchdog?.countDown()
    }

    // -- commit ----------------------------------------------------------------

    /** Places every staged file at once; called with the lock held. */
    private fun commit() {
        val committed = mutableListOf<File>()
        val aside = mutableListOf<Pair<File, File>>()
        val mtimeNotKept = mutableListOf<File>()
        try {
            for (decision in plan.toSend()) {
                val target = decision.target!!
                val replaces = decision.replaces
                if (replaces != null && replaces.exists()) {
                    val moved = File(replaces.parentFile, ".${replaces.name}.share-old-$offerId")
                    ops.move(replaces, moved, replace = true)
                    aside += moved to replaces
                }
                place(staged.getValue(decision.itemId), target, replace = replaces != null)
                committed += target
                try {
                    ops.setMtime(target, (decision.mtime * 1000).toLong())
                } catch (e: IOException) {
                    // Some external storage refuses it; the file is still there.
                    Log.w(TAG, "share receive: mtime of $target not kept", e)
                    mtimeNotKept += target
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "share receive $offerId: commit failed, rolling back", e)
            rollback(committed, aside)
            finish(TransferStatus.FAILED, error = ErrorCode.INTERNAL)
            return
        }
        for ((moved, _) in aside) {
            if (!ops.delete(moved)) Log.w(TAG, "share receive: replaced file kept at $moved")
        }
        staged.clear()
        for (decision in plan.toSend().filter { it.kind == Item.LABEL }) {
            File(decision.target!!.parentFile, decision.target.nameWithoutExtension + ".none").delete()
        }
        applyMetadata()
        finish(TransferStatus.DONE, committed, aside.map { it.second }, mtimeNotKept)
    }

    /** [tmp] to [target]. Without [replace], a target that appeared since the plan aborts the commit. */
    private fun place(tmp: File, target: File, replace: Boolean) {
        if (replace) return ops.move(tmp, target, replace = true)
        try {
            ops.link(target, tmp)
            tmp.delete()
        } catch (e: FileAlreadyExistsException) {
            throw e
        } catch (_: UnsupportedOperationException) {
            moveNew(tmp, target)
        } catch (_: IOException) {
            // External storage often has no hard links.
            moveNew(tmp, target)
        }
    }

    private fun moveNew(tmp: File, target: File) {
        if (target.exists()) throw FileAlreadyExistsException(target.path)
        ops.move(tmp, target, replace = false)
    }

    private fun afterCommit() {
        val result = result!!
        if (result.status == TransferStatus.FAILED) {
            onAbort(offerId, true)
        } else {
            try {
                library.onReceived(result.placed)
            } catch (e: Exception) {
                Log.w(TAG, "share receive $offerId: library not refreshed", e)
            }
        }
        onDone(result)
    }

    private fun rollback(committed: List<File>, aside: List<Pair<File, File>>) {
        for (target in committed.asReversed()) {
            if (!ops.delete(target)) Log.e(TAG, "share receive: could not remove $target on rollback")
        }
        for ((moved, original) in aside.asReversed()) {
            try {
                ops.move(moved, original, replace = true)
            } catch (e: IOException) {
                Log.e(TAG, "share receive: $original left at $moved on rollback", e)
            }
        }
        discardAll()
    }

    /**
     * A received game's settings. Favorite is merged into a ROM the library
     * already had; the rest only reaches a newly placed one, and never
     * overwrites a setting it already has.
     */
    private fun applyMetadata() {
        for (game in plan.games) {
            val console = game.console ?: continue
            val rom = game.romFile ?: continue
            if (!game.romStatus.placed && game.romStatus != RomStatus.EXISTING) continue
            val metadata = game.metadata ?: JSONObject()
            val key = romKey(console, rom)
            try {
                if (metadata.opt("favorite") == true) library.updateRom(key) { it.copy(favorite = true) }
                if (!game.romStatus.placed) continue
                val color = (metadata.opt("cartridge_color") as? String)?.takeIf { c -> c != "default" && CART_COLORS.any { it.id == c } }
                val core = (metadata.opt("core") as? String)?.let { cores.findByName(console, it) }
                val shader = (metadata.opt("shader") as? String)?.takeIf { s -> SHADERS.any { it.id == s } }
                if (color != null || core != null || shader != null) {
                    library.updateRom(key) { meta ->
                        meta.copy(color = meta.color ?: color, core = meta.core ?: core?.id, shader = meta.shader ?: shader)
                    }
                }
                val playtime = metadata.opt("playtime_seconds")
                if ((playtime is Int || playtime is Long) && (playtime as Number).toLong() > 0) {
                    val file = library.paths.playtime(console, game.finalStem)
                    file.parentFile?.mkdirs()
                    file.writeText(playtime.toLong().toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "share receive: metadata of $key not applied", e)
            }
        }
    }

    // -- start-up ----------------------------------------------------------------

    /** Temporary files a killed app left in the folders this transfer writes to. */
    private fun sweepStaleTemps() {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
        for (dir in sends.values.map { it.target!!.parentFile!! }.toSet()) {
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".tmp") && it.lastModified() < cutoff }
                .forEach { it.delete() }
        }
    }

    companion object {
        const val READ_CHUNK = 256 * 1024
        /** Progress callbacks: at most ten a second, besides the last one of each file. */
        const val PROGRESS_INTERVAL_MS = 100L
        const val STALE_TEMP_MS = 60 * 60_000L
        const val WATCHDOG_INTERVAL_MS = 1_000L
        private val random = SecureRandom()

        private fun randomSuffix(): String = ByteArray(4).also(random::nextBytes).toHex()
    }
}
