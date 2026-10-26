package com.sonic.share.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException

class SonicCryptoEngineTest {
    private val crypto = SonicCryptoEngine()

    @Test
    fun testEcdhKeyExchangeAndAesGcmRoundtrip() {
        val aliceKeyPair = crypto.generateKeyPair()
        val bobKeyPair = crypto.generateKeyPair()

        val aliceSecret = crypto.deriveSharedSecret(aliceKeyPair.private, bobKeyPair.public.encoded)
        val bobSecret = crypto.deriveSharedSecret(bobKeyPair.private, aliceKeyPair.public.encoded)

        assertArrayEquals(aliceSecret.encoded, bobSecret.encoded)

        val message = "Top secret AirDrop file payload content".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(message, aliceSecret)
        assertFalse(message.contentEquals(encrypted))

        val decrypted = crypto.decrypt(encrypted, bobSecret)
        assertArrayEquals(message, decrypted)
    }

    @Test
    fun testSha256Checksum() {
        val data = "Sonic High Speed Stream".toByteArray(Charsets.UTF_8)
        val hash = crypto.computeSha256(data)
        assertNotNull(hash)
        assertEquals(64, hash.length) // Hex string
        // Known test vector for "Sonic High Speed Stream":
        // echo -n "Sonic High Speed Stream" | sha256sum
        val streamHash = crypto.computeSha256(ByteArrayInputStream(data))
        assertEquals(hash, streamHash)
    }

    @Test
    fun testTamperedCiphertextFailsDecryption() {
        val aliceKeyPair = crypto.generateKeyPair()
        val bobKeyPair = crypto.generateKeyPair()
        val secret = crypto.deriveSharedSecret(aliceKeyPair.private, bobKeyPair.public.encoded)

        val message = "Authenticated payload".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(message, secret)

        // Tamper with the payload (e.g. flip a bit in the ciphertext / tag)
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decrypt(tampered, secret)
        }
    }

    @Test
    fun testDecryptionWithWrongKeyFails() {
        val aliceKeyPair = crypto.generateKeyPair()
        val bobKeyPair = crypto.generateKeyPair()
        val charlieKeyPair = crypto.generateKeyPair()

        val aliceBobSecret = crypto.deriveSharedSecret(aliceKeyPair.private, bobKeyPair.public.encoded)
        val charlieBobSecret = crypto.deriveSharedSecret(charlieKeyPair.private, bobKeyPair.public.encoded)

        val message = "Top secret message".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(message, aliceBobSecret)

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decrypt(encrypted, charlieBobSecret)
        }
    }

    @Test
    fun testCiphertextTooShortThrowsException() {
        val keyPair = crypto.generateKeyPair()
        val peerKeyPair = crypto.generateKeyPair()
        val secret = crypto.deriveSharedSecret(keyPair.private, peerKeyPair.public.encoded)

        // IV is 12 bytes and GCM tag is 16 bytes, so minimum valid ciphertext is 28 bytes for empty plaintext
        val tooShort = ByteArray(10)
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decrypt(tooShort, secret)
        }
    }

    @Test
    fun testEphemeralKeyExchangeDirectUsage() {
        val kx = EphemeralKeyExchange()
        val keyPair1 = kx.generateKeyPair()
        val keyPair2 = kx.generateKeyPair()

        val secret1 = kx.deriveSharedSecret(keyPair1.private, keyPair2.public.encoded)
        val secret2 = kx.deriveSharedSecret(keyPair2.private, keyPair1.public.encoded)

        assertEquals("AES", secret1.algorithm)
        assertEquals(32, secret1.encoded.size) // 256-bit key
        assertArrayEquals(secret1.encoded, secret2.encoded)
    }

    @Test
    fun testInvalidPeerPublicKeyThrowsException() {
        val keyPair = crypto.generateKeyPair()
        val invalidKeyBytes = byteArrayOf(1, 2, 3, 4, 5)

        assertThrows(IllegalArgumentException::class.java) {
            crypto.deriveSharedSecret(keyPair.private, invalidKeyBytes)
        }
    }

    @Test
    fun testEmptyPlaintextEncryptDecrypt() {
        val aliceKeyPair = crypto.generateKeyPair()
        val bobKeyPair = crypto.generateKeyPair()
        val secret = crypto.deriveSharedSecret(aliceKeyPair.private, bobKeyPair.public.encoded)

        val empty = ByteArray(0)
        val encrypted = crypto.encrypt(empty, secret)
        // 12 bytes IV + 16 bytes tag = 28 bytes
        assertEquals(28, encrypted.size)

        val decrypted = crypto.decrypt(encrypted, secret)
        assertArrayEquals(empty, decrypted)
    }
}
