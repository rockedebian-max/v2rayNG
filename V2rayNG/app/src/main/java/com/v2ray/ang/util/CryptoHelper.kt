package com.v2ray.ang.util

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption/decryption helper.
 * Used to encrypt server configurations at rest in MMKV storage.
 *
 * Architecture:
 * - Uses AES-256-GCM (authenticated encryption)
 * - Key derived from device-specific seed via PBKDF2
 * - Each encryption uses a random 12-byte IV (prepended to ciphertext)
 * - Salt is generated once and stored
 */
object CryptoHelper {

    private const val TAG = "CryptoHelper"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_DERIVATION = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH = 256
    private const val IV_LENGTH = 12 // GCM standard
    private const val TAG_LENGTH = 128 // GCM auth tag bits
    private const val ITERATION_COUNT = 10000
    private const val SALT_LENGTH = 16

    // Default seed - will be combined with device ID in Phase 3
    private const val DEFAULT_SEED = "v2rayNG-custom-vpn-2024"

    private var cachedKey: SecretKeySpec? = null
    private var deviceSeed: String = DEFAULT_SEED

    /**
     * Sets the device-specific seed for key derivation.
     * Call this during app initialization with the Android ID.
     * In Phase 3, this will be called with the actual device ID.
     */
    fun init(seed: String = DEFAULT_SEED) {
        deviceSeed = seed
        cachedKey = null // Force key regeneration
    }

    /**
     * Derives an AES-256 key from the device seed using PBKDF2.
     */
    private fun getKey(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(deviceSeed.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    /**
     * Encrypts a plaintext string.
     * Output format: Base64(salt + iv + ciphertext)
     *
     * @param plaintext The string to encrypt
     * @return Base64-encoded encrypted string, or original string on failure
     */
    fun encrypt(plaintext: String): String {
        return try {
            val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val key = getKey(salt)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Concatenate: salt + iv + ciphertext
            val result = salt + iv + ciphertext
            Base64.encodeToString(result, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            plaintext // Return original on failure (graceful degradation)
        }
    }

    /**
     * Decrypts a Base64-encoded encrypted string.
     *
     * @param encrypted The Base64-encoded encrypted string
     * @return Decrypted plaintext string, or null on failure
     */
    fun decrypt(encrypted: String): String? {
        return try {
            val data = Base64.decode(encrypted, Base64.NO_WRAP)
            if (data.size < SALT_LENGTH + IV_LENGTH + 1) {
                return null // Too short to be valid
            }

            val salt = data.copyOfRange(0, SALT_LENGTH)
            val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)
            val key = getKey(salt)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    /**
     * Checks if a string looks like it's encrypted (Base64 + minimum length).
     */
    fun isEncrypted(data: String): Boolean {
        return try {
            val decoded = Base64.decode(data, Base64.NO_WRAP)
            decoded.size > SALT_LENGTH + IV_LENGTH + TAG_LENGTH / 8
        } catch (e: Exception) {
            false
        }
    }
}
