package com.sonic.share.core.crypto

import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * End-to-end cryptographic engine for Sonic peer-to-peer transfers.
 *
 * Implements:
 * - Ephemeral ECDH key exchange on NIST P-256 (secp256r1) with HKDF-SHA256 key derivation.
 * - Authenticated symmetric encryption and decryption via AES-256-GCM (12-byte IV, 128-bit auth tag).
 * - Chunk and streaming SHA-256 digest computation for end-to-end file integrity verification.
 */
class SonicCryptoEngine(
    private val keyExchange: EphemeralKeyExchange = EphemeralKeyExchange(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    companion object {
        const val AES_GCM_CIPHER = "AES/GCM/NoPadding"
        const val SHA_256_ALGORITHM = "SHA-256"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8 // 16 bytes
        const val MIN_CIPHERTEXT_LENGTH_BYTES = GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES // 28 bytes
        private const val STREAM_BUFFER_SIZE = 65536
    }

    /**
     * Generates a new ephemeral EC key pair on curve secp256r1 (NIST P-256).
     */
    fun generateKeyPair(): KeyPair = keyExchange.generateKeyPair()

    /**
     * Derives a 256-bit symmetric AES key from local private key and peer's X.509-encoded public key bytes.
     *
     * @param privateKey The local ephemeral private key
     * @param peerPublicKeyBytes X.509-encoded (SubjectPublicKeyInfo) peer public key bytes
     * @return 256-bit [SecretKey] for AES-GCM
     * @throws IllegalArgumentException if the public key bytes are invalid
     * @throws GeneralSecurityException if ECDH key agreement fails
     */
    fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): SecretKey {
        return keyExchange.deriveSharedSecret(privateKey, peerPublicKeyBytes)
    }

    /**
     * Encrypts plaintext data using AES-256-GCM.
     *
     * A cryptographically secure 12-byte random IV is generated for each encryption operation
     * and prepended to the resulting ciphertext. The ciphertext concludes with the 16-byte
     * GCM authentication tag.
     *
     * Wire layout of returned ByteArray:
     * - [0..11]: 12-byte random IV
     * - [12..end]: Encrypted payload + 16-byte GCM authentication tag
     *
     * @param plaintext The unencrypted raw bytes
     * @param secretKey The 256-bit AES secret key
     * @return Byte array containing IV followed by ciphertext and authentication tag
     * @throws GeneralSecurityException on encryption failure
     */
    fun encrypt(plaintext: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val encrypted = cipher.doFinal(plaintext)
        val result = ByteArray(GCM_IV_LENGTH_BYTES + encrypted.size)
        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH_BYTES)
        System.arraycopy(encrypted, 0, result, GCM_IV_LENGTH_BYTES, encrypted.size)
        return result
    }

    /**
     * Decrypts ciphertext previously encrypted with [encrypt].
     *
     * Expects the first 12 bytes of [ciphertext] to contain the GCM IV, followed by the
     * encrypted data and the 16-byte GCM authentication tag.
     *
     * @param ciphertext Byte array containing [12-byte IV][encrypted data + 16-byte tag]
     * @param secretKey The 256-bit AES secret key
     * @return Decrypted plaintext bytes
     * @throws IllegalArgumentException if ciphertext is shorter than 28 bytes
     * @throws GeneralSecurityException if authentication tag verification fails (tampering/wrong key)
     */
    fun decrypt(ciphertext: ByteArray, secretKey: SecretKey): ByteArray {
        if (ciphertext.size < MIN_CIPHERTEXT_LENGTH_BYTES) {
            throw IllegalArgumentException(
                "Ciphertext too short: expected at least $MIN_CIPHERTEXT_LENGTH_BYTES bytes " +
                    "(12-byte IV + 16-byte tag), but got ${ciphertext.size}"
            )
        }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(ciphertext, 0, iv, 0, GCM_IV_LENGTH_BYTES)

        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(ciphertext, GCM_IV_LENGTH_BYTES, ciphertext.size - GCM_IV_LENGTH_BYTES)
    }

    /**
     * Computes the SHA-256 digest of [data] and returns it as a 64-character lowercase hexadecimal string.
     */
    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        val hashBytes = digest.digest(data)
        return bytesToHex(hashBytes)
    }

    /**
     * Computes the SHA-256 digest of data read from [inputStream] and returns it as a
     * 64-character lowercase hexadecimal string.
     */
    fun computeSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val hashBytes = digest.digest()
        return bytesToHex(hashBytes)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
