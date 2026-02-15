package com.v2ray.ang.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityIpInfoBinding
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IpInfoActivity : BaseActivity() {
    private val binding by lazy { ActivityIpInfoBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.ip_info_title))

        fetchIpInfo()
    }

    private fun fetchIpInfo() {
        binding.tvStatus.text = getString(R.string.ip_info_loading)
        binding.tvStatus.visibility = View.VISIBLE
        binding.cardIp.visibility = View.GONE
        binding.cardMap.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ipInfo = SpeedtestManager.getRemoteIPInfoFull()

                withContext(Dispatchers.Main) {
                    if (ipInfo == null) {
                        binding.tvStatus.text = getString(R.string.ip_info_error)
                        return@withContext
                    }

                    val ip = ipInfo.resolvedIp() ?: "?"
                    val country = ipInfo.resolvedCountry() ?: ""
                    val city = ipInfo.resolvedCity() ?: ""
                    val location = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")

                    binding.tvIpAddress.text = ip
                    binding.tvLocation.text = location.ifBlank { "—" }
                    binding.tvIsp.text = ipInfo.isp ?: ipInfo.organization ?: "—"
                    binding.tvTimezone.text = ipInfo.timezone ?: "—"

                    binding.tvStatus.visibility = View.GONE
                    binding.cardIp.visibility = View.VISIBLE

                    // Load map
                    val lat = ipInfo.resolvedLat()
                    val lon = ipInfo.resolvedLon()
                    if (lat != null && lon != null) {
                        loadMap(lat, lon)
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to fetch IP info", e)
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = getString(R.string.ip_info_error)
                }
            }
        }
    }

    private fun loadMap(lat: Double, lon: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://staticmap.openstreetmap.de/staticmap.php?center=$lat,$lon&zoom=4&size=600x300&markers=$lat,$lon,red-pushpin"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.connect()
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                connection.disconnect()

                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        binding.ivMap.setImageBitmap(bitmap)
                        binding.cardMap.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to load static map", e)
            }
        }
    }
}
