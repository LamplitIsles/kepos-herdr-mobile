package com.lamplitisles.herdrmobile.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

object OpenSshKeyCodec {
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    fun rsaPublicKeyBlob(publicKey: RSAPublicKey): ByteArray {
        val out = ByteArrayOutputStream()
        putString(out, "ssh-rsa".toByteArray(Charsets.US_ASCII))
        putString(out, mpInt(publicKey.publicExponent))
        putString(out, mpInt(publicKey.modulus))
        return out.toByteArray()
    }

    fun rsaPublicKeyText(publicKey: RSAPublicKey): String =
        "ssh-rsa ${base64(rsaPublicKeyBlob(publicKey))} herdr-device-key"

    /** SSH signature wire value: string algorithm + string raw signature. */
    fun sshSignatureBlob(algorithm: String, rawSignature: ByteArray): ByteArray {
        require(algorithm == "rsa-sha2-256" || algorithm == "rsa-sha2-512") {
            "Unsupported RSA signature algorithm"
        }
        val out = ByteArrayOutputStream()
        putString(out, algorithm.toByteArray(Charsets.US_ASCII))
        putString(out, rawSignature)
        return out.toByteArray()
    }

    fun sha256Fingerprint(keyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
        return "SHA256:${base64(digest).trimEnd('=')}"
    }

    fun base64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val result = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            result.append(alphabet[first ushr 2])
            result.append(alphabet[((first and 0x03) shl 4) or if (second >= 0) second ushr 4 else 0])
            result.append(if (second >= 0) alphabet[((second and 0x0f) shl 2) or if (third >= 0) third ushr 6 else 0] else '=')
            result.append(if (third >= 0) alphabet[third and 0x3f] else '=')
        }
        return result.toString()
    }

    private fun mpInt(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        while (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1].toInt() and 0x80 == 0) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        if (bytes.isNotEmpty() && bytes[0].toInt() and 0x80 != 0) {
            bytes = byteArrayOf(0) + bytes
        }
        return bytes
    }

    private fun putString(out: ByteArrayOutputStream, value: ByteArray) {
        out.write((value.size ushr 24) and 0xff)
        out.write((value.size ushr 16) and 0xff)
        out.write((value.size ushr 8) and 0xff)
        out.write(value.size and 0xff)
        out.write(value)
    }
}
