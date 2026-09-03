package com.lamplitisles.herdrmobile.ssh

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.PrivateKey
import java.security.Provider
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSshKeyCodecTest {
    @Test
    fun rsaPublicKeyIsOpenSshTextAndFingerprintIsStableWithoutPrivateBytes() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        val publicKey = generator.generateKeyPair().public as RSAPublicKey

        val blob = OpenSshKeyCodec.rsaPublicKeyBlob(publicKey)
        val text = OpenSshKeyCodec.rsaPublicKeyText(publicKey)
        val fingerprint = OpenSshKeyCodec.sha256Fingerprint(blob)

        assertTrue(text.startsWith("ssh-rsa "))
        assertTrue(text.endsWith(" herdr-device-key"))
        assertTrue(fingerprint.startsWith("SHA256:"))
        assertFalse(text.contains("PRIVATE"))
        assertEquals(fingerprint, OpenSshKeyCodec.sha256Fingerprint(blob.copyOf()))
    }

    @Test
    fun deviceKeyIdentityWrapsRsaSha2SignaturesForWireAndVerification() {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(3072)
        val keyPair = keyPairGenerator.generateKeyPair()
        val certificate = FixtureCertificate(keyPair.public)
        val keyStore = FixtureKeyStore(FixtureKeyStoreSpi(keyPair.private, certificate)).apply {
            load(null, null)
        }
        val identity = AndroidKeyStoreDeviceKey(keyStore)
        val data = "herdr-session-transcript".toByteArray(Charsets.UTF_8)

        assertEquals("ssh-rsa", identity.getAlgName())
        listOf(
            "rsa-sha2-256" to "SHA256withRSA",
            "rsa-sha2-512" to "SHA512withRSA",
        ).forEach { (algorithm, jcaAlgorithm) ->
            val wireSignature = identity.getSignature(data, algorithm)
            val reader = SshWireReader(wireSignature)

            assertEquals(algorithm, reader.readString().toString(Charsets.US_ASCII))
            val rawSignature = reader.readString()
            assertTrue(reader.isAtEnd())

            val verifier = Signature.getInstance(jcaAlgorithm).apply {
                initVerify(keyPair.public)
                update(data)
            }
            assertTrue(verifier.verify(rawSignature))
        }
    }
}

private class SshWireReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readString(): ByteArray {
        require(offset + 4 <= bytes.size) { "Missing SSH string length" }
        val length = ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        offset += 4
        require(length >= 0 && offset + length <= bytes.size) { "Invalid SSH string length" }
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    fun isAtEnd(): Boolean = offset == bytes.size
}

private class FixtureKeyStore(spi: KeyStoreSpi) : KeyStore(spi, FIXTURE_PROVIDER, "fixture") {
    private companion object {
        val FIXTURE_PROVIDER: Provider = object : Provider("herdr-fixture", 1.0, "test") {}
    }
}

private class FixtureKeyStoreSpi(
    private val privateKey: PrivateKey,
    private val certificate: Certificate,
) : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? =
        if (alias == DEVICE_KEY_ALIAS) privateKey else null

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? =
        if (alias == DEVICE_KEY_ALIAS) arrayOf(certificate) else null

    override fun engineGetCertificate(alias: String): Certificate? =
        if (alias == DEVICE_KEY_ALIAS) certificate else null

    override fun engineGetCreationDate(alias: String): Date = Date(0)

    override fun engineSetKeyEntry(
        alias: String,
        key: Key,
        password: CharArray?,
        chain: Array<out Certificate>?,
    ) = Unit

    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = Unit

    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit

    override fun engineDeleteEntry(alias: String) = Unit

    override fun engineAliases() = Collections.enumeration(listOf(DEVICE_KEY_ALIAS))

    override fun engineContainsAlias(alias: String): Boolean = alias == DEVICE_KEY_ALIAS

    override fun engineSize(): Int = 1

    override fun engineIsKeyEntry(alias: String): Boolean = alias == DEVICE_KEY_ALIAS

    override fun engineIsCertificateEntry(alias: String): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate): String? =
        if (cert === certificate) DEVICE_KEY_ALIAS else null

    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

    override fun engineGetEntry(
        alias: String,
        protectionParameter: KeyStore.ProtectionParameter?,
    ): KeyStore.Entry? =
        if (alias == DEVICE_KEY_ALIAS) {
            KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate))
        } else {
            null
        }
}

private class FixtureCertificate(private val publicKey: java.security.PublicKey) : Certificate("fixture") {
    override fun getEncoded(): ByteArray = byteArrayOf()

    override fun verify(key: java.security.PublicKey) = Unit

    override fun verify(key: java.security.PublicKey, sigProvider: String) = Unit

    override fun toString(): String = "fixture certificate"

    override fun getPublicKey(): java.security.PublicKey = publicKey
}

private const val DEVICE_KEY_ALIAS = "herdr-device-rsa3072-v1"
