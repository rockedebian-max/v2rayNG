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
 * Transport-layer encryption for device-locked config links.
 * Separate from CryptoHelper (which handles local storage at rest).
 *
 * Key derivation: PBKDF2(targetDeviceId + APP_SECRET_SALT, randomSalt)
 * Only the matching device can decrypt.
 */
object DeviceLockCrypto {

    private const val TAG = "DeviceLockCrypto"
    private const val APP_SECRET_SALT = "CG-2025-xK9#mP@vL3"
    const val PUBLIC_KEY_ID = "PUBLIC-0000-0000"

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_DERIVATION = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH = 256
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val SALT_LENGTH = 16
    private const val ITERATIONS = 10000

    private fun deriveKey(targetDeviceId: String, salt: ByteArray): SecretKeySpec {
        val password = (targetDeviceId + APP_SECRET_SALT).toCharArray()
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    /**
     * Encrypts a standard protocol link for a specific target device.
     * Called by admin panel.
     *
     * @param configLink     Raw protocol link (e.g. "vmess://...")
     * @param targetDeviceId Recipient's Display Device ID (e.g. "A3F7-B2C1-D9E4")
     * @return Base64 URL-safe encoded blob, or null on failure
     */
    fun encrypt(configLink: String, targetDeviceId: String): String? {
        return try {
            val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(targetDeviceId, salt)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val ciphertext = cipher.doFinal(configLink.toByteArray(Charsets.UTF_8))

            val blob = salt + iv + ciphertext
            Base64.encodeToString(blob, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    /**
     * Decrypts an encrypted blob using the local device's Display Device ID.
     * Called during import on client device.
     *
     * @param encryptedData Base64 blob from cyberguard:// link
     * @param localDeviceId Current device's Display Device ID
     * @return Original protocol link, or null if wrong device or corrupt
     */
    fun decrypt(encryptedData: String, localDeviceId: String): String? {
        return try {
            val blob = Base64.decode(encryptedData, Base64.URL_SAFE or Base64.NO_WRAP)
            if (blob.size < SALT_LENGTH + IV_LENGTH + TAG_LENGTH_BITS / 8) return null

            val salt = blob.copyOfRange(0, SALT_LENGTH)
            val iv = blob.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = blob.copyOfRange(SALT_LENGTH + IV_LENGTH, blob.size)
            val key = deriveKey(localDeviceId, salt)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(TAG, "Decryption failed (likely wrong device)")
            null
        }
    }
}
