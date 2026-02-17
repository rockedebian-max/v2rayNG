package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.DeviceLockCrypto
import com.v2ray.ang.util.DeviceManager
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        try {
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            parseUri(it, null)
                        }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    val uri: Uri? = intent.data

                    when (uri?.scheme) {
                        "cyberguard" -> {
                            handleCyberGuardImport(uri)
                            return // Don't start MainActivity yet — handled in callback
                        }
                        else -> {
                            when (data?.host) {
                                "install-config" -> {
                                    val shareUrl = uri?.getQueryParameter("url").orEmpty()
                                    parseUri(shareUrl, uri?.fragment)
                                }
                                "install-sub" -> {
                                    val shareUrl = uri?.getQueryParameter("url").orEmpty()
                                    parseUri(shareUrl, uri?.fragment)
                                }
                                else -> {
                                    toastError(R.string.toast_failure)
                                }
                            }
                        }
                    }
                }
            }

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error processing URL scheme", e)
        }
    }

    private fun handleCyberGuardImport(uri: Uri) {
        val encryptedData = uri.getQueryParameter("data")
        if (encryptedData.isNullOrBlank()) {
            toastError(R.string.cyberguard_error_no_data)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val localDeviceId = DeviceManager.getDisplayDeviceId(this)
        val decrypted = DeviceLockCrypto.decrypt(encryptedData, localDeviceId)

        if (decrypted == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.cyberguard_error_wrong_device_title)
                .setMessage(R.string.cyberguard_error_wrong_device_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                .setCancelable(false)
                .show()
            return
        }

        // Parse JSON envelope (new format) or treat as raw link (legacy)
        var linkToImport = decrypted
        var expiresAt: Long? = null

        if (decrypted.trimStart().startsWith("{")) {
            try {
                val json = JSONObject(decrypted)
                linkToImport = json.optString("l", decrypted)
                val exp = json.optLong("e", 0L)
                if (exp > 0) expiresAt = exp
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse envelope", e)
            }
        }

        // Check if already expired BEFORE importing
        if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_config_already_expired_title)
                .setMessage(R.string.dialog_config_already_expired_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                .setCancelable(false)
                .show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val (count, countSub) = AngConfigManager.importBatchConfig(linkToImport, "", false, expiresAt = expiresAt)
            withContext(Dispatchers.Main) {
                if (count + countSub > 0) {
                    toast(R.string.cyberguard_import_success)
                } else {
                    toastError(R.string.cyberguard_error_import_failed)
                }
                startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            return
        }
        Log.i(AppConfig.TAG, uriString)

        var decodedUrl = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decodedUrl)
        if (uri != null) {
            if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
                decodedUrl += "#${fragment}"
            }
            Log.i(AppConfig.TAG, decodedUrl)
            lifecycleScope.launch(Dispatchers.IO) {
                val (count, countSub) = AngConfigManager.importBatchConfig(decodedUrl, "", false)
                withContext(Dispatchers.Main) {
                    if (count + countSub > 0) {
                        toast(R.string.import_subscription_success)
                    } else {
                        toast(R.string.import_subscription_failure)
                    }
                }
            }
        }
    }
}
