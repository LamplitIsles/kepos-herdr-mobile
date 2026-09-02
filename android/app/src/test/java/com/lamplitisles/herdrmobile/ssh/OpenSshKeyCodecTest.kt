package com.lamplitisles.herdrmobile.ssh

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
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
}
