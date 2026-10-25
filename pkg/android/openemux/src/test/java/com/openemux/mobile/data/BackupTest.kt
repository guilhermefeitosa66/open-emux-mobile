package com.openemux.mobile.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupTest {
    private lateinit var tmp: File
    private lateinit var roots: BackupRoots

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("backup").toFile()
        roots = device("phone")
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun device(name: String) = BackupRoots(File(tmp, "$name/external"), File(tmp, "$name/internal"))

    private fun File.put(path: String, text: String): File = File(this, path).apply { parentFile?.mkdirs(); writeText(text) }

    /** A phone with a bit of everything, including what must never leave it. */
    private fun seed(r: BackupRoots = roots) {
        r.external.put("roms/GBA/Game.gba", "rom-bytes")
        r.external.put("roms/GBA/.Game2.gba.part", "half")
        r.external.put("saves/GBA/Game.srm.tmp", "half")
        r.external.put("saves/GBA/Game.srm", "save")
        r.external.put("states/GBA/Game.state1", "state")
        r.external.put("states/GBA/Game.state1.png", "thumb")
        r.external.put("playtime/GBA_Game.txt", "120")
        r.external.put("screenshots/shot.png", "png")
        r.external.put("system/gba_bios.bin", "bios")
        r.internal.put("meta.json", "{\"roms\":{}}")
        r.internal.put("meta.json.tmp", "partial")
        r.internal.put("labels/GBA/Game.png", "label")
        r.internal.put("cores/mgba_libretro_android.so", "core")
        r.internal.put("launch/current/retroarch.cfg", "cheevos_token = \"secret\"")
        r.internal.put("setup.done", "1/x86_64")
        r.internal.put("shaders/crt.glslp", "shader")
    }

    private val prefs = JSONObject().put("theme", JSONObject().put("t", "string").put("v", "dark"))

    private fun export(includeRoms: Boolean = true, r: BackupRoots = roots, cancelAfter: Int = -1): ByteArray {
        val out = ByteArrayOutputStream()
        var calls = 0
        BackupWriter(r).write(out, includeRoms, prefs, "1.18.0", 1_700_000_000_000, 1, isCancelled = { cancelAfter >= 0 && calls++ >= cancelAfter })
        return out.toByteArray()
    }

    private fun names(zip: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.map { it.name }.toList()
    }

    private fun entry(zip: ByteArray, name: String): String = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.first { it.name == name }
        z.readBytes().decodeToString()
    }

    /** A hand-made archive: the manifest given, then the entries. */
    private fun archive(manifest: String?, vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (manifest != null) { z.putNextEntry(ZipEntry(BackupFormat.MANIFEST)); z.write(manifest.toByteArray()); z.closeEntry() }
            entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun manifest(roots: List<String>, files: Int, bytes: Long) =
        BackupManifest("1.18.0", 1L, roots, mapOf(BackupGroup.SAVES to BackupSize(files, bytes)), 0, 0).toJson()

    private inline fun <reified T : BackupError> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (e: BackupError) {
            if (e is T) return e
            fail("expected ${T::class.simpleName}, got ${e::class.simpleName}: ${e.message}")
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }

    private fun stagingGone() = roots.stagingDirs.none { it.exists() }

    // Manifest

    @Test
    fun manifestRoundTrip() {
        val m = BackupManifest("1.18.0", 42L, listOf("external/saves"), mapOf(BackupGroup.SAVES to BackupSize(2, 10), BackupGroup.ROMS to BackupSize(1, 5)), 1, 2)
        val back = BackupManifest.parse(m.toJson())
        assertEquals(m, back)
        assertEquals(BackupSize(3, 15), back.total)
    }

    @Test
    fun manifestRejectsGarbageAndForeignFormats() {
        assertFails<BackupError.NotABackup> { BackupManifest.parse("not json") }
        assertFails<BackupError.NotABackup> { BackupManifest.parse("{}") }
        assertFails<BackupError.NotABackup> { BackupManifest.parse("""{"format":"openemux-mobile-backup"}""") }
        assertFails<BackupError.NotABackup> { BackupManifest.parse("""{"format":"openemux-mobile-backup","version":1}""") }
        assertFails<BackupError.NotABackup> {
            BackupManifest.parse("""{"format":"openemux-mobile-backup","version":1,"roots":[],"groups":{"saves":{"files":-1,"bytes":0}}}""")
        }
        assertFails<BackupError.NotABackup> {
            BackupManifest.parse("""{"format":"openemux-mobile-backup","version":1,"roots":[],"groups":{"saves":{"files":1,"bytes":-5}}}""")
        }
    }

    @Test
    fun manifestFromANewerAppIsTooNew() {
        assertFails<BackupError.TooNew> { BackupManifest.parse("""{"format":"openemux-mobile-backup","version":2}""") }
    }

    // Preferences

    @Test
    fun prefsKeepTheirTypes() {
        val values = mapOf("s" to "x", "b" to true, "i" to 3, "l" to 4L, "f" to 1.5f, "set" to setOf("b", "a"))
        val json = BackupFormat.prefsToJson(values + ("odd" to listOf(1)) + ("null" to null))
        assertFalse(json.has("odd"))
        assertFalse(json.has("null"))
        assertEquals(values, BackupFormat.prefsFromJson(JSONObject(json.toString())))
    }

    @Test
    fun prefsOfUnknownShapeAreSkipped() {
        val json = JSONObject()
            .put("plain", "not an entry")
            .put("weird", JSONObject().put("t", "blob").put("v", 1))
            .put("noset", JSONObject().put("t", "set").put("v", 1))
        assertTrue(BackupFormat.prefsFromJson(json).isEmpty())
    }

    // Writer

    @Test
    fun writerKeepsToTheAllowlist() {
        seed()
        val zip = export()
        val names = names(zip)
        assertEquals(BackupFormat.MANIFEST, names.first())
        assertEquals(
            setOf(
                BackupFormat.MANIFEST, BackupFormat.PREFS,
                "external/roms/GBA/Game.gba", "external/saves/GBA/Game.srm", "external/states/GBA/Game.state1",
                "external/states/GBA/Game.state1.png", "external/playtime/GBA_Game.txt", "external/screenshots/shot.png",
                "external/system/gba_bios.bin", "internal/meta.json", "internal/labels/GBA/Game.png",
            ),
            names.toSet(),
        )
        assertFalse(names.any { "cores" in it || "launch" in it || "setup" in it || it.endsWith(".tmp") || it.endsWith(".part") })
        assertEquals("dark", JSONObject(entry(zip, BackupFormat.PREFS)).getJSONObject("theme").getString("v"))
        val m = BackupManifest.parse(entry(zip, BackupFormat.MANIFEST))
        assertEquals(1, m.roms)
        assertEquals(2, m.saves)
        assertEquals(BackupSize(1, 9), m.groups[BackupGroup.ROMS])
        assertFalse("internal/nds-layout" in m.roots)
    }

    @Test
    fun writerWithoutRomsLeavesThemOut() {
        seed()
        roots.internal.put("nds-layout", "top")
        val zip = export(includeRoms = false)
        assertFalse(names(zip).any { it.startsWith("external/roms") })
        val m = BackupManifest.parse(entry(zip, BackupFormat.MANIFEST))
        assertNull(m.groups[BackupGroup.ROMS])
        assertEquals(0, m.roms)
        assertTrue("internal/nds-layout" in m.roots)
    }

    @Test
    fun measureCountsEachGroup() {
        seed()
        val sizes = BackupWriter(roots).measure(includeRoms = true)
        assertEquals(BackupSize(1, 9), sizes[BackupGroup.ROMS])
        assertEquals(BackupSize(5, 4 + 5 + 5 + 3 + 3), sizes[BackupGroup.SAVES])
        assertEquals(BackupSize(3, 4 + 11 + 5), sizes[BackupGroup.SETTINGS])
        assertNull(BackupWriter(roots).measure(includeRoms = false)[BackupGroup.ROMS])
    }

    @Test
    fun measureOfAnEmptyPhone() {
        val sizes = BackupWriter(roots).measure(includeRoms = true)
        assertEquals(BackupSize(), sizes[BackupGroup.ROMS])
        assertEquals(BackupSize(), sizes[BackupGroup.SETTINGS])
    }

    @Test
    fun writerStopsWhenCancelled() {
        seed()
        assertFails<BackupError.Cancelled> { export(cancelAfter = 1) }
    }

    @Test
    fun writerReportsProgress() {
        seed()
        val seen = mutableListOf<String>()
        var last = 0L to 0L
        BackupWriter(roots).write(ByteArrayOutputStream(), true, prefs, "1", 0, 0, onProgress = { f, d, t -> seen += f; last = d to t })
        assertTrue("states" in seen && "roms" in seen)
        assertEquals(last.second, last.first)
    }

    // Reader

    @Test
    fun readManifestAlone() {
        seed()
        assertEquals("1.18.0", BackupReader(roots).readManifest(ByteArrayInputStream(export())).app)
    }

    @Test
    fun restoreOnAFreshPhone() {
        seed()
        roots.external.put("states/GBA/Game.state1", "state")
        File(roots.external, "states/GBA/Game.state1").setLastModified(1_600_000_000_000)
        val zip = export()
        val fresh = device("new")
        fresh.internal.put("cores/keep.so", "core")
        val staged = BackupReader(fresh).extract(ByteArrayInputStream(zip))
        assertEquals("dark", staged.prefs?.get("theme"))
        fresh.swapIn(staged.roots)
        assertEquals("rom-bytes", File(fresh.external, "roms/GBA/Game.gba").readText())
        assertEquals("state", File(fresh.external, "states/GBA/Game.state1").readText())
        assertEquals(1_600_000_000_000, File(fresh.external, "states/GBA/Game.state1").lastModified())
        assertEquals("{\"roms\":{}}", File(fresh.internal, "meta.json").readText())
        assertEquals("label", File(fresh.internal, "labels/GBA/Game.png").readText())
        assertTrue(File(fresh.internal, "cores/keep.so").exists())
        assertFalse(File(fresh.internal, "launch").exists())
        assertTrue(fresh.stagingDirs.none { it.exists() })
        assertTrue(fresh.all.none { fresh.old(it).exists() })
    }

    @Test
    fun restoreReplacesWhatTheArchiveCoversAndKeepsTheRest() {
        seed()
        val zip = export(includeRoms = false)
        roots.external.put("saves/GBA/Other.srm", "newer")
        roots.external.put("roms/GBA/Extra.gba", "extra")
        roots.swapIn(BackupReader(roots).extract(ByteArrayInputStream(zip)).roots)
        assertFalse(File(roots.external, "saves/GBA/Other.srm").exists())
        assertTrue(File(roots.external, "roms/GBA/Extra.gba").exists())
    }

    @Test
    fun emptyFoldersInTheArchiveEmptyThePhone() {
        val zip = export()
        roots.external.put("states/GBA/Stale.state", "x")
        roots.swapIn(BackupReader(roots).extract(ByteArrayInputStream(zip)).roots)
        assertTrue(File(roots.external, "states").isDirectory)
        assertFalse(File(roots.external, "states/GBA/Stale.state").exists())
    }

    @Test
    fun readerReportsProgress() {
        seed()
        var calls = 0
        BackupReader(roots).extract(ByteArrayInputStream(export()), onProgress = { _, _, _ -> calls++ })
        assertTrue(calls > 0)
    }

    @Test
    fun readerRejectsWhatIsNotABackup() {
        val reader = BackupReader(roots)
        assertFails<BackupError.NotABackup> { reader.readManifest(ByteArrayInputStream(ByteArray(0))) }
        assertFails<BackupError.NotABackup> { reader.readManifest(ByteArrayInputStream("plain text, not a zip".toByteArray())) }
        assertFails<BackupError.NotABackup> { reader.readManifest(ByteArrayInputStream(archive(null, "game.gba" to "x"))) }
        assertFails<BackupError.NotABackup> { reader.readManifest(ByteArrayInputStream(archive("{ nope"))) }
        assertFails<BackupError.TooNew> {
            reader.readManifest(ByteArrayInputStream(archive("""{"format":"openemux-mobile-backup","version":2}""")))
        }
    }

    @Test
    fun readerRejectsAManifestTooLargeToBeOne() {
        assertFails<BackupError.NotABackup> { BackupReader(roots).readManifest(ByteArrayInputStream(archive(" ".repeat(70 * 1024)))) }
    }

    private fun rejected(zip: ByteArray) {
        roots.external.put("saves/GBA/Mine.srm", "mine")
        assertFails<BackupError.NotABackup> { BackupReader(roots).extract(ByteArrayInputStream(zip)) }
        assertTrue(stagingGone())
        assertEquals("mine", File(roots.external, "saves/GBA/Mine.srm").readText())
    }

    @Test
    fun readerRejectsPathsThatClimbOut() {
        val m = manifest(listOf("external/saves"), 1, 1)
        rejected(archive(m, "external/saves/../../../evil" to "x"))
        rejected(archive(m, "/external/saves/abs" to "x"))
        rejected(archive(m, "external/saves\\win" to "x"))
        rejected(archive(m, "external/saves//double" to "x"))
        rejected(archive(m, "external/saves/./dot" to "x"))
    }

    @Test
    fun readerRejectsEntriesOutsideTheListedRoots() {
        rejected(archive(manifest(listOf("external/saves"), 1, 1), "internal/cores/x.so" to "x"))
        rejected(archive(manifest(listOf("external/saves"), 1, 1), "external/states/a" to "x"))
        rejected(archive(manifest(listOf("external/saves"), 1, 1), "external/saves" to "x"))
        rejected(archive(manifest(listOf("internal/launch"), 1, 1), "internal/launch/retroarch.cfg" to "x"))
        rejected(archive(manifest(listOf("external/saves", "external/saves"), 1, 1), "external/saves/a" to "x"))
    }

    @Test
    fun readerRejectsDuplicatesAndExtraFiles() {
        // ZipOutputStream refuses a repeated name: rename the second entry in the bytes.
        val twice = archive(manifest(listOf("external/saves"), 2, 2), "external/saves/a" to "x", "external/saves/b" to "y")
        rejected(String(twice, Charsets.ISO_8859_1).replace("external/saves/b", "external/saves/a").toByteArray(Charsets.ISO_8859_1))
        rejected(archive(manifest(listOf("external/saves"), 1, 2), "external/saves/a" to "x", "external/saves/b" to "y"))
    }

    @Test
    fun readerRejectsMoreBytesThanDeclared() {
        rejected(archive(manifest(listOf("external/saves"), 1, 3), "external/saves/a" to "a much longer file"))
    }

    @Test
    fun readerRejectsAnArchiveShortOfWhatItDeclared() {
        rejected(archive(manifest(listOf("external/saves"), 2, 2), "external/saves/a" to "x"))
        rejected(archive(manifest(listOf("external/saves"), 1, 5), "external/saves/a" to "x"))
    }

    @Test
    fun readerRejectsGarbledPrefs() {
        rejected(archive(manifest(listOf("external/saves"), 1, 5), BackupFormat.PREFS to "{oops"))
    }

    @Test
    fun readerSkipsDirectoryEntries() {
        val zip = archive(manifest(listOf("external/saves"), 1, 1), "external/saves/GBA/" to "", "external/saves/GBA/a" to "x")
        val staged = BackupReader(roots).extract(ByteArrayInputStream(zip))
        assertNull(staged.prefs)
        assertEquals("x", File(roots.staging(roots.byName("external/saves")!!), "GBA/a").readText())
    }

    @Test
    fun readerRejectsATruncatedArchive() {
        seed()
        val zip = export()
        rejected(zip.copyOf(zip.size / 2))
    }

    /** An archive with one large entry nothing can compress, so a cut lands inside its data. */
    private fun bigArchive(): ByteArray {
        val data = ByteArray(256 * 1024).also { java.util.Random(7).nextBytes(it) }
        val text = String(data, Charsets.ISO_8859_1)
        return archive(manifest(listOf("external/saves"), 1, text.toByteArray().size.toLong()), "external/saves/big" to text)
    }

    @Test
    fun readerRejectsAnArchiveCutInsideAnEntry() {
        val zip = bigArchive()
        rejected(zip.copyOf(zip.size * 2 / 3))
    }

    @Test
    fun readerRejectsDamagedData() {
        val zip = bigArchive()
        // Flip bytes in the middle of the entry: the inflater or the CRC check refuses it.
        for (i in zip.size / 2 until zip.size / 2 + 64) zip[i] = (zip[i].toInt() xor 0x5a).toByte()
        rejected(zip)
    }

    @Test
    fun readerStopsWhenCancelled() {
        seed()
        roots.external.put("saves/GBA/Mine.srm", "mine")
        assertFails<BackupError.Cancelled> { BackupReader(roots).extract(ByteArrayInputStream(export()), isCancelled = { true }) }
        assertTrue(stagingGone())
    }

    @Test
    fun readerTurnsOtherReadFailuresIntoIo() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("card removed")
        }
        val e = assertFails<BackupError.Io> { BackupReader(roots).extract(broken) }
        assertEquals("card removed", e.message)
        assertTrue(stagingGone())
    }

    @Test
    fun readerClearsAStaleStaging() {
        seed()
        File(roots.external, "${BackupRoots.STAGING}/external/saves/leftover").apply { parentFile?.mkdirs(); writeText("x") }
        BackupReader(roots).extract(ByteArrayInputStream(export()))
        assertFalse(File(roots.external, "${BackupRoots.STAGING}/external/saves/leftover").exists())
    }

    // Swap and recovery

    @Test
    fun aFailedSwapPutsEverythingBack() {
        seed()
        val zip = export()
        roots.external.put("saves/GBA/Game.srm", "current")
        val staged = BackupReader(roots).extract(ByteArrayInputStream(zip))
        var renames = 0
        roots.rename = { from, to -> if (++renames == 4) false else from.renameTo(to) }
        assertFails<BackupError.Io> { roots.swapIn(staged.roots) }
        assertEquals("current", File(roots.external, "saves/GBA/Game.srm").readText())
        assertEquals("rom-bytes", File(roots.external, "roms/GBA/Game.gba").readText())
        assertTrue(roots.all.none { roots.old(it).exists() })
    }

    @Test
    fun aSwapThatCannotSetTheDeviceCopyAsideChangesNothing() {
        seed()
        val staged = BackupReader(roots).extract(ByteArrayInputStream(export()))
        roots.rename = { _, _ -> false }
        assertFails<BackupError.Io> { roots.swapIn(staged.roots) }
        assertEquals("save", File(roots.external, "saves/GBA/Game.srm").readText())
    }

    @Test
    fun aSwapIntoAPhoneWithoutTheRootStillRollsBack() {
        seed()
        val zip = export()
        val fresh = device("new")
        val staged = BackupReader(fresh).extract(ByteArrayInputStream(zip))
        var renames = 0
        fresh.rename = { from, to -> if (++renames == 2) false else from.renameTo(to) }
        assertFails<BackupError.Io> { fresh.swapIn(staged.roots) }
        assertFalse(File(fresh.external, "roms").exists())
    }

    @Test
    fun recoverUndoesASwapCutShort() {
        seed()
        val saves = roots.byName("external/saves")!!
        val states = roots.byName("external/states")!!
        // Killed after saves was swapped and states set aside, before states came in.
        saves.file.renameTo(roots.old(saves))
        roots.external.put("saves/GBA/Game.srm", "from-archive")
        states.file.renameTo(roots.old(states))
        File(roots.external, "${BackupRoots.STAGING}/external/states/x").apply { parentFile?.mkdirs(); writeText("x") }
        roots.recover()
        assertEquals("save", File(roots.external, "saves/GBA/Game.srm").readText())
        assertEquals("state", File(roots.external, "states/GBA/Game.state1").readText())
        assertTrue(stagingGone())
    }

    @Test
    fun recoverFinishesACommittedSwap() {
        seed()
        val saves = roots.byName("external/saves")!!
        saves.file.renameTo(roots.old(saves))
        roots.external.put("saves/GBA/Game.srm", "from-archive")
        File(roots.internal, "${BackupRoots.STAGING}/committed").apply { parentFile?.mkdirs(); writeText("") }
        roots.recover()
        assertEquals("from-archive", File(roots.external, "saves/GBA/Game.srm").readText())
        assertFalse(roots.old(saves).exists())
        assertTrue(stagingGone())
    }

    @Test
    fun recoverOnAHealthyPhoneDoesNothing() {
        seed()
        roots.recover()
        assertEquals("save", File(roots.external, "saves/GBA/Game.srm").readText())
    }

    @Test
    fun sharedStorageFallbackKeepsStagingApart() {
        // Without external storage, Paths puts both under filesDir.
        val one = File(tmp, "single")
        val r = BackupRoots(one, one)
        seed(r)
        val staged = BackupReader(r).extract(ByteArrayInputStream(export(r = r)))
        assertEquals(1, r.stagingDirs.size)
        r.swapIn(staged.roots)
        assertEquals("save", File(one, "saves/GBA/Game.srm").readText())
        assertEquals("{\"roms\":{}}", File(one, "meta.json").readText())
    }

    @Test
    fun filesOfAMissingRoot() {
        assertTrue(roots.files(roots.byName("external/saves")!!).isEmpty())
        assertTrue(roots.files(roots.byName("internal/meta.json")!!).isEmpty())
    }
}
