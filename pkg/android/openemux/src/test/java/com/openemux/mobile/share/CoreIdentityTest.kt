package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreIdentityTest {
    private val lib = FakeLibrary()
    private val snes9x = Console.SFC.core("snes9x")
    private val bsnes = Console.SFC.core("bsnes")

    @Test
    fun `info values are read quoted, unquoted, and missing as empty`() {
        assertEquals(CoreIdentity("Snes9x", "1.62.3"), parseCoreInfo("corename = \"Snes9x\"\ndisplay_version = \"1.62.3\""))
        assertEquals(CoreIdentity("bsnes", "115"), parseCoreInfo("# comment\n  corename=bsnes  \ndisplay_version = 115\n"))
        assertEquals(CoreIdentity("A", ""), parseCoreInfo("corename = \"A\"\nnot a line\ncorename = \"B\""))
        assertEquals(CoreIdentity.UNKNOWN, parseCoreInfo(""))
        assertFalse(CoreIdentity("A", "").known)
        assertTrue(CoreIdentity("A", "1").known)
    }

    @Test
    fun `a core without an info file has no identity`() {
        assertEquals(CoreIdentity.UNKNOWN, CoreIdentities(lib.paths).identityOf(snes9x))
    }

    @Test
    fun `installed means a non-empty library file`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        File(lib.paths.cores, bsnes.fileName).put(ByteArray(0))
        assertEquals(listOf(snes9x), CoreIdentities(lib.paths).installed(Console.SFC))
    }

    @Test
    fun `a compatible core matches name and version exactly, and only when installed`() {
        lib.installCore(snes9x, "Snes9x", "1.62.3")
        lib.installCore(bsnes, "bsnes", "115", so = false)
        val cores = CoreIdentities(lib.paths)
        assertEquals(snes9x, cores.findCompatible(Console.SFC, "Snes9x", "1.62.3"))
        assertNull(cores.findCompatible(Console.SFC, "snes9x", "1.62.3"))
        assertNull(cores.findCompatible(Console.SFC, "Snes9x", "1.62"))
        assertNull(cores.findCompatible(Console.SFC, "Snes9x", ""))
        assertNull(cores.findCompatible(Console.SFC, "", ""))
        assertNull(cores.findCompatible(Console.SFC, "bsnes", "115"))
    }

    @Test
    fun `a core is found by name whether installed or not`() {
        lib.installCore(bsnes, "bsnes", "115", so = false)
        val cores = CoreIdentities(lib.paths)
        assertEquals(bsnes, cores.findByName(Console.SFC, "bsnes"))
        assertNull(cores.findByName(Console.SFC, "Snes9x"))
        assertNull(cores.findByName(Console.SFC, ""))
    }
}
