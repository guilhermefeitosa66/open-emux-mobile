package com.openemux.mobile.share

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Who this device is on the wire (spec §3). The key never leaves the Android Keystore. */
class DeviceIdentity(
    val id: String,
    val name: String,
    val platform: String,
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) {
    val fingerprint: String = fingerprint(certificate)

    /** The body of `GET /info`. */
    fun info(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("pf", platform)
        .put("v", ShareProtocol.VERSION).put("vmin", ShareProtocol.MIN_VERSION)

    fun announcement(port: Int) =
        Announcement(id, name, platform, VersionRange.OWN, port, fingerprint)

    companion object {
        /** Lowercase hex SHA-256 of the DER certificate. */
        fun fingerprint(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

        fun subjectCn(id: String) = "OpenEmux Share $id"
    }
}

class KeyMaterial(val privateKey: PrivateKey, val certificate: X509Certificate)

/** Where the key and its self-signed certificate live; the Keystore in the app, memory in tests. */
interface KeyBackend {
    fun load(alias: String): KeyMaterial?
    fun generate(alias: String, subjectCn: String): KeyMaterial
    fun delete(alias: String)
}

/**
 * The id and the user's name for this device in `files/share/identity.json`,
 * the key and certificate in a [KeyBackend], kept consistent: a missing key, a
 * certificate whose CN names another id, or an unreadable file regenerate id,
 * key and certificate together.
 */
class IdentityStore(
    private val dir: File,
    private val backend: KeyBackend,
    private val defaults: () -> Defaults,
) {
    /** What the device is called and what it is, when the user has not renamed it. */
    data class Defaults(val name: String, val platform: String)

    private val file get() = File(dir, "identity.json")

    /** The identity, created on first use; `unavailable("keystore")` when no key can be made. */
    @Synchronized
    fun load(): DeviceIdentity {
        val stored = read()
        val material = stored?.let { safeLoad() }
        if (stored != null && material != null && cnId(material.certificate) == stored.id) {
            return identity(stored, material)
        }
        if (stored != null) Log.w(TAG, "share identity inconsistent; generating a new one")
        val fresh = Stored(newId(), stored?.name)
        val made = try {
            backend.delete(ALIAS)
            backend.generate(ALIAS, DeviceIdentity.subjectCn(fresh.id)).also { write(fresh) }
        } catch (e: Exception) {
            Log.w(TAG, "share identity: could not be created", e)
            throw ShareError(ErrorCode.UNAVAILABLE, "keystore", mapOf("reason" to "keystore"))
        }
        return identity(fresh, made)
    }

    /** Renames the device; a blank name goes back to the default. */
    @Synchronized
    fun setName(name: String): DeviceIdentity {
        val current = load()
        write(Stored(current.id, sanitizeName(name).ifEmpty { null }))
        return load()
    }

    private fun identity(stored: Stored, material: KeyMaterial): DeviceIdentity {
        val defaults = defaults()
        val name = stored.name ?: sanitizeName(defaults.name).ifEmpty { stored.id.take(6) }
        return DeviceIdentity(stored.id, name, sanitizePlatform(defaults.platform), material.certificate, material.privateKey)
    }

    private fun safeLoad(): KeyMaterial? = try {
        backend.load(ALIAS)
    } catch (e: Exception) {
        Log.w(TAG, "share identity: key unreadable", e)
        null
    }

    private data class Stored(val id: String, val name: String?)

    private fun read(): Stored? {
        if (!file.isFile) return null
        val json = try {
            parseJsonObject(file.readBytes())
        } catch (_: IOException) {
            null
        } ?: return null
        val id = json.opt("id")
        if (!isDeviceId(id)) return null
        val name = (json.opt("name") as? String)?.let(::sanitizeName)?.ifEmpty { null }
        return Stored(id as String, name)
    }

    private fun write(stored: Stored) {
        dir.mkdirs()
        val tmp = File(dir, "identity.json.tmp")
        tmp.writeText(JSONObject().put("id", stored.id).put("name", stored.name ?: JSONObject.NULL).toString())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not write ${file.path}")
        }
    }

    companion object {
        const val ALIAS = "openemux-share"
        private val random = SecureRandom()

        fun newId(): String = ByteArray(16).also(random::nextBytes).toHex()

        /** The id in `CN=OpenEmux Share <id>`, or null. */
        fun cnId(certificate: X509Certificate): String? {
            val prefix = "CN=" + DeviceIdentity.subjectCn("")
            return certificate.subjectX500Principal.name.split(',')
                .map { it.trim() }
                .firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.takeIf(::isDeviceId)
        }
    }
}

/**
 * An EC P-256 key in the Android Keystore; the Keystore self-signs its
 * certificate (CN `OpenEmux Share <id>`, random serial, 20 years).
 */
class AndroidKeyStoreBackend : KeyBackend {
    private fun store() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun load(alias: String): KeyMaterial? {
        val store = store()
        val key = store.getKey(alias, null) as? PrivateKey ?: return null
        val certificate = store.getCertificate(alias) as? X509Certificate ?: return null
        return KeyMaterial(key, certificate)
    }

    override fun generate(alias: String, subjectCn: String): KeyMaterial {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=$subjectCn"))
            .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + VALIDITY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        return load(alias) ?: throw IllegalStateException("the Keystore lost the key it just made")
    }

    override fun delete(alias: String) {
        store().deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DAY_MS = 24L * 3600 * 1000
        const val VALIDITY_MS = 20L * 365 * DAY_MS
    }
}
