package com.v2ray.ang.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.res.ColorStateList
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
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var connectingAnimator: AnimatorSet? = null
    private var terminalJob: Job? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
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

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
            if (isRunning) {
                scheduleAutoCheck()
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

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
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
            // service not running: keep existing no-op (could show a message if desired)
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
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
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
        hideTerminal()

        if (isRunning) {
            binding.fab.setIconResource(R.drawable.ic_stop_24dp)
            binding.fab.text = getString(R.string.btn_disconnect)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.btn_disconnect)
            binding.fab.elevation = 12f
            binding.fab.outlineAmbientShadowColor = ContextCompat.getColor(this, R.color.color_fab_active)
            binding.fab.outlineSpotShadowColor = ContextCompat.getColor(this, R.color.color_fab_active)
            binding.progressBar.visibility = View.INVISIBLE
            setTestState(getString(R.string.connection_connected))
            binding.tvToolbarSubtitle.text = getString(R.string.toolbar_subtitle_on)
            binding.tvToolbarSubtitle.setTextColor(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setIconResource(R.drawable.ic_play_24dp)
            binding.fab.text = getString(R.string.btn_connect)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.btn_connect)
            binding.fab.elevation = 6f
            binding.fab.outlineAmbientShadowColor = ContextCompat.getColor(this, android.R.color.transparent)
            binding.fab.outlineSpotShadowColor = ContextCompat.getColor(this, android.R.color.transparent)
            binding.progressBar.visibility = View.INVISIBLE
            setTestState(getString(R.string.connection_not_connected))
            binding.tvToolbarSubtitle.text = getString(R.string.toolbar_subtitle_off)
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            binding.tvToolbarSubtitle.setTextColor(typedValue.data)
            binding.layoutTest.isFocusable = false
        }
    }

    private fun startConnectingAnimation() {
        // Phase 1: Scale up + fade
        val pulseUp = ObjectAnimator.ofPropertyValuesHolder(
            binding.fab,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.12f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.12f),
            PropertyValuesHolder.ofFloat("alpha", 1f, 0.6f)
        ).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Phase 2: Scale back + restore
        val pulseDown = ObjectAnimator.ofPropertyValuesHolder(
            binding.fab,
            PropertyValuesHolder.ofFloat("scaleX", 1.12f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1.12f, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0.6f, 1f)
        ).apply {
            duration = 600
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
    }

    private fun startTerminal(isConnecting: Boolean) {
        terminalJob?.cancel()
        val lines = resources.getStringArray(
            if (isConnecting) R.array.terminal_connecting else R.array.terminal_disconnecting
        )
        binding.tvTerminal.text = ""
        binding.cardTerminal.visibility = View.VISIBLE

        terminalJob = lifecycleScope.launch {
            val sb = StringBuilder()
            for (line in lines) {
                // Type each character
                for (ch in line) {
                    sb.append(ch)
                    binding.tvTerminal.text = sb.toString() + "█"
                    delay(if (ch == '…' || ch == '—') 60L else 18L)
                }
                // End line
                sb.append("\n")
                binding.tvTerminal.text = sb.toString()
                delay(350L)
            }
            // Keep visible briefly then fade
            delay(1500L)
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

    override fun onResume() {
        super.onResume()
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
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        stopConnectingAnimation()
        terminalJob?.cancel()
        tabMediator?.detach()
        super.onDestroy()
    }
}