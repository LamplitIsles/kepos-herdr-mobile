package com.lamplitisles.herdrmobile.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.Identity
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class AndroidKeyStoreDeviceKey(
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
) : DeviceKeyIdentity, Identity {
    private val privateKey: PrivateKey
    private val publicKey: RSAPublicKey

    init {
        if (!keyStore.containsAlias(ALIAS)) generate()
        val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("Device Key is unavailable")
        privateKey = entry.privateKey
        publicKey = entry.certificate.publicKey as? RSAPublicKey
            ?: error("Device Key is not RSA")
        require(publicKey.modulus.bitLength() >= 3000) { "Device Key is not RSA-3072" }
    }

    override val publicBlob: ByteArray
        get() = OpenSshKeyCodec.rsaPublicKeyBlob(publicKey).copyOf()

    override val publicKeyText: String
        get() = OpenSshKeyCodec.rsaPublicKeyText(publicKey)

    override fun sign(data: ByteArray, algorithm: String): ByteArray {
        val signatureAlgorithm = when (algorithm) {
            "rsa-sha2-512" -> "SHA512withRSA"
            "rsa-sha2-256" -> "SHA256withRSA"
            else -> throw IllegalArgumentException("Unsupported RSA signature algorithm")
        }
        return try {
            Signature.getInstance(signatureAlgorithm).apply {
                initSign(privateKey)
                update(data)
            }.sign()
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Device Key signing failed", error)
        }
    }

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun getPublicKeyBlob(): ByteArray = publicBlob

    override fun getSignature(data: ByteArray): ByteArray = getSignature(data, "rsa-sha2-512")

    override fun getSignature(data: ByteArray, alg: String): ByteArray =
        OpenSshKeyCodec.sshSignatureBlob(alg, sign(data, alg))

    override fun getAlgName(): String = "ssh-rsa"

    override fun getName(): String = ALIAS

    override fun isEncrypted(): Boolean = false

    override fun clear() = Unit

    private fun generate() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN
            )
                .setKeySize(3072)
                .setAlgorithmParameterSpec(java.security.spec.RSAKeyGenParameterSpec(3072, java.math.BigInteger.valueOf(0x10001)))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()
        )
        generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "herdr-device-rsa3072-v1"
    }
}
