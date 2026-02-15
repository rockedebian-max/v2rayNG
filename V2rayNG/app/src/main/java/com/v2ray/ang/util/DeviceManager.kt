package com.v2ray.ang.util

import android.content.Context
import android.provider.Settings

/**
 * Manages device identification for encryption seed derivation.
 * Phase 3: Binds config encryption to the physical device.
 */
object DeviceManager {

    private const val FALLBACK_SEED = "v2rayNG-custom-vpn-2024"
    // Known bad Android ID value present on some emulators/old devices
    private const val BAD_ANDROID_ID = "9774d56d682e549c"

    /**
     * Returns a stable device-specific seed for use in CryptoHelper.
     * Uses Android ID which is unique per (device + app) combination.
     * Falls back to default seed if Android ID is unavailable or invalid.
     *
     * @param context Application context
     * @return Device-specific seed string
     */
    fun getDeviceSeed(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (androidId.isNullOrBlank() || androidId == BAD_ANDROID_ID) {
                FALLBACK_SEED
            } else {
                androidId
            }
        } catch (e: Exception) {
            FALLBACK_SEED
        }
    }
}
