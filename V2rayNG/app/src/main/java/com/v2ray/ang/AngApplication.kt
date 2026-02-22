package com.v2ray.ang

import android.content.Context
import androidx.multidex.MultiDexApplication
import com.tencent.mmkv.MMKV
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.CryptoHelper
import com.v2ray.ang.util.DeviceManager

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)

        // Phase 3: Initialize encryption with device-specific seed
        CryptoHelper.init(DeviceManager.getDeviceSeed(this))

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.ensureDefaultSettings()
        SettingsManager.setNightMode()
        SettingsManager.initRoutingRulesets(this)
        SettingsManager.migrateHysteria2PinSHA256()

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 200)
            .apply()
    }
}
