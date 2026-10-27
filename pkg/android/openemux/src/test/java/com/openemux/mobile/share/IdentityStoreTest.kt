package com.openemux.mobile.share

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class IdentityStoreTest {
    private lateinit var dir: File
    private val backend = FakeKeyBackend()
    private var defaults = IdentityStore.Defaults("Pixel 8", "phone")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("share").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = IdentityStore(dir, backend) { defaults }
    private val file get() = File(dir, "identity.json")

    @Test
    fun `the first run generates and the second reuses`() {
        val first = store().load()
        assertTrue(isDeviceId(first.id))
        assertEquals("Pixel 8", first.name)
        assertEquals("phone", first.platform)
        assertEquals("CN=OpenEmux Share ${first.id}", first.certificate.subjectX500Principal.name)
        val second = store().load()
        assertEquals(first.id, second.id)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(1, backend.generated)
    }

    @Test
    fun `the fingerprint is the SHA-256 of the DER certificate`() {
        val identity = store().load()
        val expected = MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).joinToString("") { "%02x".format(it) }
        assertEquals(expected, identity.fingerprint)
        assertEquals(64, identity.fingerprint.length)
    }

    @Test
    fun `a missing key regenerates id and key together`() {
        val first = store().load()
        backend.keys.clear()
        val second = store().load()
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertEquals(second.id, JSONObject(file.readText()).getString("id"))
    }

    @Test
    fun `a certificate naming another id regenerates`() {
        val first = store().load()
        backend.keys[IdentityStore.ALIAS] = TestCerts.material(DeviceIdentity.subjectCn("b".repeat(32)))
        val second = store().load()
        assertNotEquals(first.id, second.id)
        assertEquals(IdentityStore.cnId(second.certificate), second.id)
    }

    @Test
    fun `an unreadable key or file regenerates`() {
        val first = store().load()
        file.writeText("{not json")
        val second = store().load()
        assertNotEquals(first.id, second.id)
        file.writeText("""{"id":"short"}""")
        assertNotEquals(second.id, store().load().id)
        val third = store().load()
        backend.failLoad = true
        backend.keys.clear()
        backend.failLoad = false
        assertNotEquals(third.id, store().load().id)
    }

    @Test
    fun `a broken keystore read regenerates instead of crashing`() {
        store().load()
        val broken = object : KeyBackend by backend {
            override fun load(alias: String): KeyMaterial? = throw IllegalStateException("boom")
        }
        val identity = IdentityStore(dir, broken) { defaults }.load()
        assertTrue(isDeviceId(identity.id))
    }

    @Test
    fun `a failed key generation is unavailable keystore`() {
        backend.failGenerate = true
        val error = assertThrows(ShareError::class.java) { store().load() }
        assertEquals(ErrorCode.UNAVAILABLE, error.code)
        assertEquals("keystore", error.details["reason"])
    }

    @Test
    fun `the name edit is sanitised, persisted and resettable`() {
        val store = store()
        val first = store.load()
        val renamed = store.setName("  Living\u0000 room ")
        assertEquals(first.id, renamed.id)
        assertEquals("Living room", renamed.name)
        assertEquals("Living room", store().load().name)
        assertEquals("Pixel 8", store.setName("   ").name)
        assertTrue(JSONObject(file.readText()).isNull("name"))
    }

    @Test
    fun `defaults follow the device until the user renames it`() {
        val first = store().load()
        defaults = IdentityStore.Defaults("", "fridge")
        val identity = store().load()
        assertEquals(first.id.take(6), identity.name)
        assertEquals("desktop", identity.platform)
    }

    @Test
    fun `info and announcement describe the identity`() {
        val identity = store().load()
        val info = identity.info()
        assertEquals(identity.id, info.getString("id"))
        assertEquals("phone", info.getString("pf"))
        assertEquals(1, info.getInt("v"))
        assertEquals(1, info.getInt("vmin"))
        val announcement = identity.announcement(1234)
        assertEquals(1234, announcement.port)
        assertEquals(identity.fingerprint, announcement.fp)
    }

    @Test
    fun `cnId ignores certificates that are not ours`() {
        assertNull(IdentityStore.cnId(TestCerts.material("Something else").certificate))
        assertNull(IdentityStore.cnId(TestCerts.material("OpenEmux Share xyz").certificate))
    }
}
