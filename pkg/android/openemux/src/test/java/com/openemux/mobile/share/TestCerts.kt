package com.openemux.mobile.share

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Date

/** P-256 self-signed certificates like the Keystore makes, for the JVM tests. */
object TestCerts {
    fun material(cn: String): KeyMaterial {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val name = X500Name("CN=$cn")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 86_400_000L * 365 * 20), name, pair.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
        return KeyMaterial(pair.private, JcaX509CertificateConverter().getCertificate(holder))
    }

    fun identity(id: String = IdentityStore.newId(), name: String = "Test", platform: String = "phone"): DeviceIdentity {
        val material = material(DeviceIdentity.subjectCn(id))
        return DeviceIdentity(id, name, platform, material.certificate, material.privateKey)
    }
}

/** A [KeyBackend] in memory, which can be told to fail. */
class FakeKeyBackend : KeyBackend {
    val keys = mutableMapOf<String, KeyMaterial>()
    var failGenerate = false
    var failLoad = false
    var generated = 0

    override fun load(alias: String): KeyMaterial? {
        if (failLoad) throw IllegalStateException("keystore broken")
        return keys[alias]
    }

    override fun generate(alias: String, subjectCn: String): KeyMaterial {
        if (failGenerate) throw IllegalStateException("no keys today")
        generated++
        return TestCerts.material(subjectCn).also { keys[alias] = it }
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}

/** A clock the test moves by hand, in milliseconds. */
class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke() = now
}
