package com.sonic.share.core.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Manages ephemeral Elliptic Curve Diffie-Hellman (ECDH) key pair generation and
 * shared secret derivation using the NIST P-256 (secp256r1) curve and RFC 5869 HKDF-SHA256.
 */
class EphemeralKeyExchange(
    private val secureRandom: SecureRandom = SecureRandom()
) {
    companion object {
        const val EC_ALGORITHM = "EC"
        const val CURVE_NAME = "secp256r1"
        const val ECDH_ALGORITHM = "ECDH"
        const val HMAC_SHA256 = "HmacSHA256"
        const val AES_ALGORITHM = "AES"
        const val DERIVED_KEY_LENGTH_BYTES = 32 // 256 bits
        val DEFAULT_HKDF_INFO = "Sonic-AES-256-GCM".toByteArray(Charsets.UTF_8)
    }

    /**
     * Generates an ephemeral EC key pair on curve secp256r1 (NIST P-256).
     */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(EC_ALGORITHM)
        val ecSpec = ECGenParameterSpec(CURVE_NAME)
        kpg.initialize(ecSpec, secureRandom)
        return kpg.generateKeyPair()
    }

    /**
     * Derives a 256-bit symmetric AES key from local private key and peer's X.509 encoded public key bytes
     * using ECDH and RFC 5869 HKDF-SHA256.
     *
     * @param privateKey The local private EC key
     * @param peerPublicKeyBytes X.509-encoded (SubjectPublicKeyInfo) peer public key bytes
     * @param salt Optional HKDF salt bytes. Defaults to 32 zero bytes if empty or null.
     * @param info Optional context and application-specific info string. Defaults to "Sonic-AES-256-GCM".
     * @return 256-bit [SecretKey] suitable for AES-GCM encryption/decryption
     * @throws IllegalArgumentException if the peer public key bytes cannot be parsed
     * @throws GeneralSecurityException if cryptographic key agreement fails
     */
    fun deriveSharedSecret(
        privateKey: PrivateKey,
        peerPublicKeyBytes: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = DEFAULT_HKDF_INFO
    ): SecretKey {
        val peerPublicKey = try {
            val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
            val keySpec = X509EncodedKeySpec(peerPublicKeyBytes)
            keyFactory.generatePublic(keySpec)
        } catch (e: InvalidKeySpecException) {
            throw IllegalArgumentException("Invalid peer public key bytes: failed to decode X509EncodedKeySpec", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid peer public key bytes", e)
        }

        val keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val rawSharedSecret = keyAgreement.generateSecret()

        val derivedKeyBytes = hkdfSha256(
            ikm = rawSharedSecret,
            salt = salt,
            info = info,
            length = DERIVED_KEY_LENGTH_BYTES
        )

        return SecretKeySpec(derivedKeyBytes, AES_ALGORITHM)
    }

    /**
     * Implements RFC 5869 HKDF (HMAC-based Extract-and-Expand Key Derivation Function) using SHA-256.
     *
     * @param ikm Input Keying Material
     * @param salt Optional salt value (a non-secret random value); if not provided, defaults to 32 zero octets
     * @param info Optional context and application-specific information
     * @param length Length of output keying material in octets (must be <= 255 * 32)
     */
    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int
    ): ByteArray {
        require(length in 1..(255 * 32)) { "Length must be between 1 and ${255 * 32} bytes" }

        // Step 1: HKDF-Extract(salt, IKM) -> PRK
        val effectiveSalt = if (salt == null || salt.isEmpty()) {
            ByteArray(32) // HashLen zeros for SHA-256
        } else {
            salt
        }

        val extractMac = Mac.getInstance(HMAC_SHA256)
        extractMac.init(SecretKeySpec(effectiveSalt, HMAC_SHA256))
        val prk = extractMac.doFinal(ikm)

        // Step 2: HKDF-Expand(PRK, info, L) -> OKM
        val expandMac = Mac.getInstance(HMAC_SHA256)
        expandMac.init(SecretKeySpec(prk, HMAC_SHA256))

        val n = (length + 31) / 32 // ceil(length / 32)
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var bytesWritten = 0

        for (i in 1..n) {
            expandMac.reset()
            if (i > 1) {
                expandMac.update(t)
            }
            expandMac.update(info)
            expandMac.update(i.toByte())
            t = expandMac.doFinal()

            val toCopy = minOf(t.size, length - bytesWritten)
            System.arraycopy(t, 0, okm, bytesWritten, toCopy)
            bytesWritten += toCopy
        }

        return okm
    }
}
