package com.v2ray.ang.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.res.ColorStateList
import androidx.appcompat.app.AlertDialog
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.NotificationManager as AppNotificationManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.DeviceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var connectingAnimator: AnimatorSet? = null
    private var terminalJob: Job? = null
    private var timerJob: Job? = null
    private var connectionStartTime: Long = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkDebounceJob: Job? = null
    private var isRestarting = false
    private var restartJob: Job? = null
    private var lastNetworkRestartTime = 0L
    private var hadNetworkBefore = true
    private var isNetworkRestart = false
    private var adminTapCount = 0
    private var adminTapJob: Job? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        } else {
            // User denied VPN permission — reset UI from "Connecting" back to idle
            applyRunningState(isLoading = false, isRunning = false)
            toast(R.string.toast_vpn_permission_denied)
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, "")

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        setupNavHeaderDeviceId()
        setupAdminTapDetector()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.btnTestAll.setOnClickListener { mainViewModel.testAllRealPing() }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        // Anti-tamper: update lastSeenTime + clean expired servers
        MmkvManager.updateLastSeenTime(System.currentTimeMillis())
        lifecycleScope.launch {
            verifyNetworkTime()
            val selectedBefore = MmkvManager.getSelectServer()
            val removed = withContext(Dispatchers.IO) { MmkvManager.removeExpiredServers() }
            if (removed > 0) {
                if (mainViewModel.isRunning.value == true) {
                    val selectedAfter = MmkvManager.getSelectServer()
                    if (selectedAfter.isNullOrEmpty() || selectedAfter != selectedBefore) {
                        V2RayServiceManager.stopVService(this@MainActivity)
                    }
                }
                mainViewModel.reloadServerList()
            }
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            if (isRestarting && !isRunning) {
                // During restart: service stopped briefly, skip the idle flicker
                return@observe
            }
            if (isRestarting && isRunning) {
                isRestarting = false
            }
            applyRunningState(false, isRunning)
            if (isRunning) {
                hadNetworkBefore = true
                scheduleAutoCheck()
                registerNetworkCallback()
            } else {
                unregisterNetworkCallback()
            }
        }
        mainViewModel.isTesting.observe(this) { isTesting ->
            binding.progressBar.visibility = if (isTesting) View.VISIBLE else View.INVISIBLE
            binding.btnTestAll.isEnabled = !isTesting
            binding.btnTestAll.text = getString(if (isTesting) R.string.btn_testing_servers else R.string.btn_test_all_servers)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        if (groups.isNotEmpty()) {
            val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
            binding.viewPager.setCurrentItem(targetIndex, false)
        }

        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        if (mainViewModel.isRunning.value == true) {
            // Cancel any pending restart (network switch, settings change, etc.)
            restartJob?.cancel()
            restartJob = null
            isRestarting = false
            isNetworkRestart = false
            applyRunningState(isLoading = true, isRunning = false)
            V2RayServiceManager.stopVService(this)
            return
        }

        // Fix #1: No servers — show descriptive dialog instead of brief toast
        val selectedServer = MmkvManager.getSelectServer()
        if (selectedServer.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_no_servers_title)
                .setMessage(R.string.dialog_no_servers_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // CHECK 3: Clock tampering detected
        if (MmkvManager.isClockTampered()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clock_tampered_title)
                .setMessage(R.string.dialog_clock_tampered_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // CHECK 4: Server expired
        val config = MmkvManager.decodeServerConfig(selectedServer)
        if (config?.expiresAt?.let { System.currentTimeMillis() > it } == true) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_server_expired_title)
                .setMessage(R.string.dialog_server_expired_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeServer(selectedServer)
                    mainViewModel.reloadServerList()
                }
                .setCancelable(false)
                .show()
            return
        }

        // Fix #2: Server not tested or failed — warn user and offer to test first
        val aff = MmkvManager.decodeServerAffiliationInfo(selectedServer)
        if (aff == null || aff.testDelayMillis <= 0L) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_untested_server_title)
                .setMessage(R.string.dialog_untested_server_message)
                .setPositiveButton(R.string.dialog_btn_connect_anyway) { _, _ ->
                    proceedWithConnection()
                }
                .setNegativeButton(R.string.dialog_btn_test_first) { _, _ ->
                    mainViewModel.testAllRealPing()
                }
                .show()
            return
        }

        proceedWithConnection()
    }

    private fun proceedWithConnection() {
        applyRunningState(isLoading = true, isRunning = false)

        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // Fix #4: Give feedback when tapping bottom bar while disconnected
            toast(R.string.toast_tap_to_test_disconnected)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        // Cancel any previous restart
        restartJob?.cancel()
        // Flag prevents the isRunning observer from flickering to idle during restart
        isRestarting = true

        // Only update FAB/progress — don't replace terminal if network restart is showing
        if (!isNetworkRestart) {
            applyRunningState(isLoading = true, isRunning = false)
        } else {
            // Just show loading indicators without replacing the network switch terminal
            binding.fab.isClickable = false
            binding.progressBar.visibility = View.VISIBLE
            binding.progressConnecting.visibility = View.VISIBLE
            startConnectingAnimation()
        }

        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        restartJob = lifecycleScope.launch {
            delay(500)
            startV2Ray()
            // Safety: reset flag after timeout in case service never starts
            delay(5000)
            if (isRestarting) {
                isRestarting = false
                isNetworkRestart = false
                applyRunningState(false, mainViewModel.isRunning.value == true)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // Check all networks, skipping VPN interfaces — we want underlying connectivity
        val allNetworks = cm.allNetworks
        for (network in allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            // Skip VPN transport — that's us
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (hasTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true
            }
        }
        return false
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network came back — debounce to avoid rapid WiFi↔cellular switches
                scheduleNetworkChange(hasNetwork = true)
            }

            override fun onLost(network: Network) {
                // Network lost — debounce before showing error
                scheduleNetworkChange(hasNetwork = false)
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun scheduleNetworkChange(hasNetwork: Boolean) {
        networkDebounceJob?.cancel()
        networkDebounceJob = lifecycleScope.launch {
            // Wait for network to stabilize (WiFi↔cellular switches fire multiple events)
            delay(if (hasNetwork) 2000L else 2500L)
            if (mainViewModel.isRunning.value != true) return@launch
            if (isRestarting) return@launch

            val reallyHasNetwork = isNetworkAvailable()
            val now = System.currentTimeMillis()
            val cooldownMs = 30_000L // 30s cooldown between network restarts

            if (!reallyHasNetwork) {
                // Network lost — show warning UI, keep VPN tunnel state
                hadNetworkBefore = false
                applyRunningState(false, true) // VPN is still running, just no network
                return@launch
            }

            if (!hadNetworkBefore && (now - lastNetworkRestartTime > cooldownMs)) {
                // Network came BACK after being lost — restart VPN once
                hadNetworkBefore = true
                lastNetworkRestartTime = now
                startNetworkSwitchRestart()
            } else {
                // Network is fine, just update UI
                hadNetworkBefore = true
                applyRunningState(false, true)
            }
        }
    }

    private fun startNetworkSwitchRestart() {
        isNetworkRestart = true

        // Show reconnecting terminal animation
        setTestState(getString(R.string.connection_reconnecting))
        binding.tvToolbarSubtitle.text = getString(R.string.toolbar_subtitle_reconnecting)
        binding.tvToolbarSubtitle.setTextColor(
            ContextCompat.getColor(this, R.color.color_fab_connecting)
        )
        AppNotificationManager.updateContentText(getString(R.string.notification_reconnecting))

        // Show terminal with network switch messages
        startTerminalNetworkSwitch()

        // Perform the restart
        restartV2Ray()
    }

    private fun unregisterNetworkCallback() {
        networkDebounceJob?.cancel()
        networkDebounceJob = null
        networkCallback?.let {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private fun scheduleAutoCheck() {
        setTestState(getString(R.string.connection_test_testing))
        mainViewModel.testCurrentServerRealPing()
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        // Always stop previous animation and reset FAB
        stopConnectingAnimation()

        if (isLoading) {
            val wasRunning = mainViewModel.isRunning.value == true
            binding.fab.isClickable = false

            if (wasRunning) {
                // Disconnecting state
                binding.fab.setIconResource(R.drawable.ic_stop_24dp)
                binding.fab.text = getString(R.string.btn_disconnecting)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_connecting))
                setTestState(getString(R.string.connection_disconnecting))
            } else {
                // Connecting state
                binding.fab.setIconResource(R.drawable.ic_fab_check)
                binding.fab.text = getString(R.string.btn_connecting)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_connecting))
                setTestState(getString(R.string.connection_establishing))
            }

            // Show visual indicators
            binding.progressBar.visibility = View.VISIBLE
            binding.progressConnecting.visibility = View.VISIBLE
            binding.tvToolbarSubtitle.text = getString(R.string.toolbar_subtitle_connecting)
            startConnectingAnimation()
            startTerminal(!wasRunning)
            return
        }

        // Hide loading indicators
        binding.fab.isClickable = true
        binding.progressConnecting.visibility = View.GONE

        if (isRunning) {
            val hasNetwork = isNetworkAvailable()

            binding.fab.setIconResource(R.drawable.ic_stop_24dp)
            binding.fab.text = getString(R.string.btn_disconnect)
            binding.fab.contentDescription = getString(R.string.btn_disconnect)
            binding.progressBar.visibility = View.INVISIBLE
            binding.layoutTest.isFocusable = true
            startConnectionTimer()

            if (hasNetwork) {
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                binding.fab.elevation = 12f
                binding.fab.outlineAmbientShadowColor = ContextCompat.getColor(this, R.color.color_fab_active)
                binding.fab.outlineSpotShadowColor = ContextCompat.getColor(this, R.color.color_fab_active)
                setTestState(getString(R.string.connection_connected))
                binding.tvTestState.setTextColor(0xFF00B4FF.toInt()) // restore cyan
                binding.tvToolbarSubtitle.text = getString(R.string.toolbar_subtitle_on)
                binding.tvToolbarSubtitle.setTextColor(ContextCompat.getColor(this, R.color.color_fab_active))
                AppNotificationManager.updateContentText(getString(R.string.connection_connected))
                // Real terminal result: show "network restored" if reconnecting, else normal
                val terminalMsg = if (isNetworkRestart) {
                    isNetworkRestart = false
                    getString(R.string.terminal_result_network_back)
                } else {
                    getString(R.string.terminal_result_connected)
                }
                finishTerminalWithResult(terminalMsg, 0xFF00C853.toInt())
            } else {
                // Tunnel active but no network — warn the user
                val noNetColor = ContextCompat.getColor(this, R.color.color_fab_no_network)
                binding.fab.backgroundTintList = ColorStateList.valueOf(noNetColor)
                binding.fab.elevation = 12f
                binding.fab.outlineAmbientShadowColor = noNetColor
                binding.fab.outlineSpotShadowColor = noNetColor
                setTestState(getString(R.string.connection_connected_no_network))
                binding.tvToolbarSubtitle.text = getString(R.string.toolbar_subtitle_no_network)
                binding.tvToolbarSubtitle.setTextColor(noNetColor)
                binding.tvTestState.setTextColor(noNetColor)
                AppNotificationManager.updateContentText(getString(R.string.notification_no_network))
                // Real terminal result: append no-network line then show warning
                finishTerminalWithNoNetwork()
            }
        } else {
            isNetworkRestart = false
            binding.fab.setIconResource(R.drawable.ic_play_24dp)
            binding.fab.text = getString(R.string.btn_connect)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.btn_connect)
            binding.fab.elevation = 6f
            binding.fab.outlineAmbientShadowColor = ContextCompat.getColor(this, android.R.color.transparent)
            binding.fab.outlineSpotShadowColor = ContextCompat.getColor(this, android.R.color.transparent)
            binding.progressBar.visibility = View.INVISIBLE
            setTestState(getString(R.string.connection_not_connected))
            binding.tvTestState.setTextColor(0xFF00B4FF.toInt()) // restore cyan
            binding.tvToolbarSubtitle.text = getString(R.string.toolbar_subtitle_off)
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            binding.tvToolbarSubtitle.setTextColor(typedValue.data)
            binding.layoutTest.isFocusable = false
            stopConnectionTimer()
            // Real terminal result: disconnected
            finishTerminalWithResult(getString(R.string.terminal_result_disconnected), 0xFF00B4FF.toInt())
        }
    }

    private fun startConnectionTimer() {
        connectionStartTime = System.currentTimeMillis()
        binding.tvToolbarTimer.visibility = View.VISIBLE
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - connectionStartTime) / 1000
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                binding.tvToolbarTimer.text = "[ %02d:%02d:%02d ]".format(h, m, s)
                delay(1000)
            }
        }
    }

    private fun stopConnectionTimer() {
        timerJob?.cancel()
        timerJob = null
        binding.tvToolbarTimer.visibility = View.GONE
    }

    private fun startConnectingAnimation() {
        // Shrink FAB to icon-only during connecting for a cleaner look
        binding.fab.shrink()

        // Subtle pulse animation — no big scaling, no heavy opacity
        val pulseUp = ObjectAnimator.ofPropertyValuesHolder(
            binding.fab,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.04f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.04f),
            PropertyValuesHolder.ofFloat("alpha", 1f, 0.85f)
        ).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
        }

        val pulseDown = ObjectAnimator.ofPropertyValuesHolder(
            binding.fab,
            PropertyValuesHolder.ofFloat("scaleX", 1.04f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1.04f, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0.85f, 1f)
        ).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
        }

        connectingAnimator = AnimatorSet().apply {
            playSequentially(pulseUp, pulseDown)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (connectingAnimator != null) {
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun stopConnectingAnimation() {
        connectingAnimator?.cancel()
        connectingAnimator = null
        binding.fab.scaleX = 1f
        binding.fab.scaleY = 1f
        binding.fab.alpha = 1f
        binding.fab.extend() // Restore FAB to full size with text
    }

    private var terminalBuffer = StringBuilder()
    private var pendingTerminalResult: Pair<String, Int>? = null
    private var terminalStartTime = 0L

    private fun startTerminal(isConnecting: Boolean) {
        terminalJob?.cancel()
        pendingTerminalResult = null
        terminalStartTime = System.currentTimeMillis()
        val lines = resources.getStringArray(
            if (isConnecting) R.array.terminal_connecting else R.array.terminal_disconnecting
        )
        terminalBuffer = StringBuilder()
        binding.tvTerminal.text = ""
        binding.tvTerminal.setTextColor(0xFF00B4FF.toInt()) // cyan
        binding.cardTerminal.visibility = View.VISIBLE

        terminalJob = lifecycleScope.launch {
            for ((index, line) in lines.withIndex()) {
                // If real result arrived, fast-forward remaining lines
                if (pendingTerminalResult != null) {
                    for (remaining in index until lines.size) {
                        terminalBuffer.append(lines[remaining]).append("\n")
                    }
                    binding.tvTerminal.text = terminalBuffer.toString()
                    delay(150L)
                    showTerminalResult(pendingTerminalResult!!)
                    return@launch
                }

                // Type each character
                for (ch in line) {
                    terminalBuffer.append(ch)
                    binding.tvTerminal.text = terminalBuffer.toString() + "█"
                    delay(if (ch == '…' || ch == '—') 50L else 15L)
                }
                terminalBuffer.append("\n")
                binding.tvTerminal.text = terminalBuffer.toString()
                delay(250L)
            }

            // All decorative lines done — check if result is already waiting
            if (pendingTerminalResult != null) {
                showTerminalResult(pendingTerminalResult!!)
                return@launch
            }

            // Show slow connection warning after 8s
            var slowShown = false
            var blink = true
            while (true) {
                if (!slowShown && System.currentTimeMillis() - terminalStartTime > 8000L) {
                    slowShown = true
                    val slowMsg = getString(R.string.terminal_slow_connection)
                    terminalBuffer.append(slowMsg).append("\n")
                    binding.tvTerminal.setTextColor(0xFFFFAB00.toInt()) // amber
                }
                // Check for pending result each blink cycle
                if (pendingTerminalResult != null) {
                    showTerminalResult(pendingTerminalResult!!)
                    return@launch
                }
                binding.tvTerminal.text = terminalBuffer.toString() + if (blink) "█" else " "
                blink = !blink
                delay(500L)
            }
        }
    }

    private fun startTerminalNetworkSwitch() {
        terminalJob?.cancel()
        pendingTerminalResult = null
        terminalStartTime = System.currentTimeMillis()
        val lines = resources.getStringArray(R.array.terminal_network_switch)
        terminalBuffer = StringBuilder()
        binding.tvTerminal.text = ""
        binding.tvTerminal.setTextColor(0xFFFFAB00.toInt()) // amber
        binding.cardTerminal.visibility = View.VISIBLE

        terminalJob = lifecycleScope.launch {
            for ((index, line) in lines.withIndex()) {
                if (pendingTerminalResult != null) {
                    for (remaining in index until lines.size) {
                        terminalBuffer.append(lines[remaining]).append("\n")
                    }
                    binding.tvTerminal.text = terminalBuffer.toString()
                    delay(150L)
                    showTerminalResult(pendingTerminalResult!!)
                    return@launch
                }
                for (ch in line) {
                    terminalBuffer.append(ch)
                    binding.tvTerminal.text = terminalBuffer.toString() + "█"
                    delay(if (ch == '…' || ch == '—') 50L else 15L)
                }
                terminalBuffer.append("\n")
                binding.tvTerminal.text = terminalBuffer.toString()
                delay(250L)
            }
            // Wait for result
            var blink = true
            while (true) {
                if (pendingTerminalResult != null) {
                    showTerminalResult(pendingTerminalResult!!)
                    return@launch
                }
                binding.tvTerminal.text = terminalBuffer.toString() + if (blink) "█" else " "
                blink = !blink
                delay(500L)
            }
        }
    }

    private fun finishTerminalWithResult(resultText: String, color: Int) {
        if (binding.cardTerminal.visibility != View.VISIBLE && terminalBuffer.isEmpty()) return

        // Store the result — the running terminal coroutine will pick it up
        pendingTerminalResult = Pair(resultText, color)

        // If terminal job already finished (or was never started), show immediately
        if (terminalJob?.isActive != true) {
            binding.cardTerminal.visibility = View.VISIBLE
            terminalJob = lifecycleScope.launch {
                showTerminalResult(Pair(resultText, color))
            }
        }
    }

    private suspend fun showTerminalResult(result: Pair<String, Int>) {
        pendingTerminalResult = null
        val (text, color) = result
        binding.tvTerminal.setTextColor(color)

        // Type the result line quickly
        for (ch in text) {
            terminalBuffer.append(ch)
            binding.tvTerminal.text = terminalBuffer.toString() + "█"
            delay(12L)
        }
        terminalBuffer.append("\n")
        binding.tvTerminal.text = terminalBuffer.toString()

        delay(3000L)
        hideTerminal()
    }

    private fun finishTerminalWithNoNetwork() {
        if (binding.cardTerminal.visibility != View.VISIBLE && terminalBuffer.isEmpty()) {
            startTerminalNoNetworkStandalone()
            return
        }
        pendingTerminalResult = null
        terminalJob?.cancel()
        binding.cardTerminal.visibility = View.VISIBLE
        val resultLine = getString(R.string.terminal_result_no_network)
        val warningLines = resources.getStringArray(R.array.terminal_no_network)

        terminalJob = lifecycleScope.launch {
            binding.tvTerminal.setTextColor(0xFFE53E6B.toInt())
            for (ch in resultLine) {
                terminalBuffer.append(ch)
                binding.tvTerminal.text = terminalBuffer.toString() + "█"
                delay(12L)
            }
            terminalBuffer.append("\n")
            binding.tvTerminal.text = terminalBuffer.toString()
            delay(300L)

            for (line in warningLines) {
                for (ch in line) {
                    terminalBuffer.append(ch)
                    binding.tvTerminal.text = terminalBuffer.toString() + "█"
                    delay(15L)
                }
                terminalBuffer.append("\n")
                binding.tvTerminal.text = terminalBuffer.toString()
                delay(250L)
            }
            delay(4000L)
            hideTerminal()
        }
    }

    private fun startTerminalNoNetworkStandalone() {
        terminalJob?.cancel()
        pendingTerminalResult = null
        val lines = resources.getStringArray(R.array.terminal_no_network)
        terminalBuffer = StringBuilder()
        binding.tvTerminal.text = ""
        binding.tvTerminal.setTextColor(0xFFE53E6B.toInt())
        binding.cardTerminal.visibility = View.VISIBLE

        terminalJob = lifecycleScope.launch {
            for (line in lines) {
                for (ch in line) {
                    terminalBuffer.append(ch)
                    binding.tvTerminal.text = terminalBuffer.toString() + "█"
                    delay(15L)
                }
                terminalBuffer.append("\n")
                binding.tvTerminal.text = terminalBuffer.toString()
                delay(250L)
            }
            delay(4000L)
            hideTerminal()
        }
    }

    private fun hideTerminal() {
        terminalJob?.cancel()
        terminalJob = null
        binding.cardTerminal.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                binding.cardTerminal.visibility = View.GONE
                binding.cardTerminal.alpha = 1f
            }
            .start()
    }

    // region Admin Panel Access

    private fun setupNavHeaderDeviceId() {
        val headerView = binding.navView.getHeaderView(0)
        val tvDeviceId = headerView.findViewById<TextView>(R.id.tv_device_id)
        val btnCopy = headerView.findViewById<ImageView>(R.id.btn_copy_device_id)

        val displayId = DeviceManager.getDisplayDeviceId(this)
        tvDeviceId.text = displayId

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("device_id", displayId))
            toast(R.string.device_id_copied)
        }
    }

    private fun setupAdminTapDetector() {
        val headerView = binding.navView.getHeaderView(0)
        headerView.setOnClickListener {
            adminTapCount++
            adminTapJob?.cancel()
            if (adminTapCount >= 7) {
                adminTapCount = 0
                promptAdminPassword()
            } else {
                adminTapJob = lifecycleScope.launch {
                    delay(3000L)
                    adminTapCount = 0
                }
            }
        }
    }

    private fun promptAdminPassword() {
        val prefs = getSharedPreferences("admin_prefs", Context.MODE_PRIVATE)
        val storedHash = prefs.getString("admin_pw_hash", null)

        if (storedHash == null) {
            showSetPasswordDialog(prefs)
        } else {
            showVerifyPasswordDialog(storedHash)
        }
    }

    private fun showSetPasswordDialog(prefs: android.content.SharedPreferences) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.admin_set_password_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_set_password_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pw = input.text.toString()
                if (pw.length >= 6) {
                    val hash = hashPassword(pw)
                    prefs.edit().putString("admin_pw_hash", hash).apply()
                    openAdminPanel()
                } else {
                    toast(R.string.admin_password_too_short)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVerifyPasswordDialog(storedHash: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.admin_enter_password_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_verify_password_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text.toString()
                if (hashPassword(entered) == storedHash) {
                    openAdminPanel()
                } else {
                    toastError(R.string.admin_wrong_password)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hashPassword(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun openAdminPanel() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        startActivity(Intent(this, AdminActivity::class.java))
    }

    // endregion

    // region Anti-tamper: Network Time Verification

    private suspend fun verifyNetworkTime() {
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("https://www.google.com").openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 5000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = false
                conn.connect()
                val serverDate = conn.headerFields["Date"]?.firstOrNull()
                if (serverDate != null) {
                    val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    val networkTime = sdf.parse(serverDate)?.time
                    if (networkTime != null) {
                        MmkvManager.updateLastSeenTime(networkTime)
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // No network available — local lastSeenTime still protects
            }
        }
    }

    // endregion

    override fun onResume() {
        super.onResume()
        MmkvManager.updateLastSeenTime(System.currentTimeMillis())
        lifecycleScope.launch {
            val selectedBefore = MmkvManager.getSelectServer()
            val removed = withContext(Dispatchers.IO) { MmkvManager.removeExpiredServers() }
            if (removed > 0) {
                if (mainViewModel.isRunning.value == true) {
                    val selectedAfter = MmkvManager.getSelectServer()
                    if (selectedAfter.isNullOrEmpty() || selectedAfter != selectedBefore) {
                        V2RayServiceManager.stopVService(this@MainActivity)
                    }
                }
                mainViewModel.reloadServerList()
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.ip_info -> startActivity(Intent(this, IpInfoActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.user_manual -> startActivity(Intent(this, ManualActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        stopConnectingAnimation()
        restartJob?.cancel()
        terminalJob?.cancel()
        tabMediator?.detach()
        super.onDestroy()
    }
}