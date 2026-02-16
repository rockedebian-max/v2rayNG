package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityIpInfoBinding
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IpInfoActivity : BaseActivity() {
    private val binding by lazy { ActivityIpInfoBinding.inflate(layoutInflater) }
    private var scanAnimJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.ip_info_title))

        fetchIpInfo()
    }

    private fun startScanAnimation(textView: android.widget.TextView, baseText: String) {
        scanAnimJob?.cancel()
        scanAnimJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                dots = (dots % 3) + 1
                textView.text = "$baseText" + ".".repeat(dots) + " █"
                delay(400)
            }
        }
    }

    private fun stopScanAnimation() {
        scanAnimJob?.cancel()
        scanAnimJob = null
    }

    private fun fetchIpInfo() {
        binding.tvStatus.text = "> Rastreando IP"
        binding.tvStatus.visibility = View.VISIBLE
        binding.cardIp.visibility = View.GONE
        binding.cardMap.visibility = View.GONE

        startScanAnimation(binding.tvStatus, "> Rastreando IP")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ipInfo = SpeedtestManager.getRemoteIPInfoFull()

                withContext(Dispatchers.Main) {
                    stopScanAnimation()

                    if (ipInfo == null) {
                        binding.tvStatus.text = getString(R.string.ip_info_error)
                        return@withContext
                    }

                    val ip = ipInfo.resolvedIp() ?: "?"
                    val countryCode = ipInfo.country_code ?: ipInfo.countryCode ?: ipInfo.location?.country_code
                    val countryName = ipInfo.country_name ?: ipInfo.country ?: countryCode ?: ""
                    val city = ipInfo.resolvedCity() ?: ""
                    val flag = countryCodeToFlag(countryCode)
                    val timezone = ipInfo.timezone?.substringAfterLast("/")?.replace("_", " ") ?: "—"

                    binding.tvIpAddress.text = ip
                    binding.tvCountry.text = if (flag != null) "$flag $countryName" else countryName.ifBlank { "—" }
                    binding.tvCity.text = city.ifBlank { "—" }
                    binding.tvIsp.text = ipInfo.isp ?: ipInfo.organization ?: "—"
                    binding.tvTimezone.text = timezone

                    binding.tvStatus.visibility = View.GONE
                    binding.cardIp.visibility = View.VISIBLE

                    // Load map with loading indicator
                    val lat = ipInfo.resolvedLat()
                    val lon = ipInfo.resolvedLon()
                    if (lat != null && lon != null) {
                        binding.cardMap.visibility = View.VISIBLE
                        binding.tvMapLoading.visibility = View.VISIBLE
                        binding.wvMap.visibility = View.GONE
                        startScanAnimation(binding.tvMapLoading, "> Escaneando ubicación")
                        loadMap(lat, lon)
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to fetch IP info", e)
                withContext(Dispatchers.Main) {
                    stopScanAnimation()
                    binding.tvStatus.text = getString(R.string.ip_info_error)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadMap(lat: Double, lon: Double) {
        val webView = binding.wvMap

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        webView.setBackgroundColor(0xFF0A0E14.toInt())

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                stopScanAnimation()
                binding.wvMap.visibility = View.VISIBLE
                binding.tvMapLoading.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                stopScanAnimation()
                binding.tvMapLoading.text = getString(R.string.ip_info_map_unavailable)
                binding.tvMapLoading.visibility = View.VISIBLE
                binding.wvMap.visibility = View.GONE
            }
        }

        webView.webChromeClient = WebChromeClient()

        val html = buildMapHtml(lat, lon)
        webView.loadDataWithBaseURL("https://unpkg.com/", html, "text/html", "UTF-8", null)
    }

    private fun buildMapHtml(lat: Double, lon: Double): String {
        return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  * { margin: 0; padding: 0; }
  html, body { width: 100%; height: 100%; overflow: hidden; background: #0A0E14; }
  #map { width: 100%; height: 100%; }
  .leaflet-control-attribution { font-size: 8px !important; opacity: 0.6; }
</style>
</head>
<body>
<div id="map"></div>
<script>
  var map = L.map('map', {
    center: [$lat, $lon],
    zoom: 5,
    zoomControl: false,
    attributionControl: true,
    dragging: true,
    scrollWheelZoom: false
  });

  L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; OSM &copy; CARTO',
    subdomains: 'abcd',
    maxZoom: 19
  }).addTo(map);

  var pulseIcon = L.divIcon({
    className: '',
    html: '<div style="position:relative;width:24px;height:24px;">' +
          '<div style="position:absolute;top:0;left:0;width:24px;height:24px;background:rgba(0,168,255,0.25);border-radius:50%;animation:pulse 2s ease-out infinite;"></div>' +
          '<div style="position:absolute;top:6px;left:6px;width:12px;height:12px;background:#00A8FF;border:2px solid #fff;border-radius:50%;box-shadow:0 0 8px rgba(0,168,255,0.8);"></div>' +
          '</div>' +
          '<style>@keyframes pulse{0%{transform:scale(1);opacity:1}100%{transform:scale(3);opacity:0}}</style>',
    iconSize: [24, 24],
    iconAnchor: [12, 12]
  });

  L.marker([$lat, $lon], { icon: pulseIcon }).addTo(map);
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun countryCodeToFlag(code: String?): String? {
        if (code == null || code.length != 2) return null
        val upper = code.uppercase()
        val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
        return String(first) + String(second)
    }

    override fun onDestroy() {
        stopScanAnimation()
        binding.wvMap.destroy()
        super.onDestroy()
    }
}
