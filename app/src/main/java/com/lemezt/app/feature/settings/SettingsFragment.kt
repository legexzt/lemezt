package com.lemezt.app.feature.settings
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lemezt.app.BuildConfig
import com.lemezt.app.R
import com.lemezt.app.core.datastore.UserPreferencesDataStore
import com.lemezt.app.core.storage.TemporaryStorageManager
import com.lemezt.app.databinding.FragmentSettingsBinding
import com.lemezt.app.feature.update.UpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var dataStore: UserPreferencesDataStore
    private lateinit var storageManager: TemporaryStorageManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dataStore = UserPreferencesDataStore(requireContext())
        storageManager = TemporaryStorageManager(requireContext())

        setupThemeSelector()
        setupNetworkSwitches()
        setupCacheControls()
        setupPermissionsHub()
        setupAboutSection()
    }

    private fun setupAboutSection() {
        binding.tvCurrentVersion.text = "lemezt v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.btnCheckUpdates.setOnClickListener {
            UpdateManager.checkForUpdate(requireActivity(), isManualCheck = true)
        }
    }

    private fun setupThemeSelector() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentMode = dataStore.themeMode.first()
            when (currentMode) {
                "LIGHT" -> binding.rbThemeLight.isChecked = true
                "DARK" -> binding.rbThemeDark.isChecked = true
                else -> binding.rbThemeSystem.isChecked = true
            }
        }

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val modeStr = when (checkedId) {
                R.id.rbThemeLight -> "LIGHT"
                R.id.rbThemeDark -> "DARK"
                else -> "SYSTEM"
            }

            viewLifecycleOwner.lifecycleScope.launch {
                dataStore.setThemeMode(modeStr)
                when (modeStr) {
                    "LIGHT" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    "DARK" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
        }
    }

    private fun setupNetworkSwitches() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.switchWifiOnly.isChecked = dataStore.wifiOnly.first()
            binding.switchAutoResume.isChecked = dataStore.autoResume.first()
        }

        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch { dataStore.setWifiOnly(isChecked) }
        }

        binding.switchAutoResume.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch { dataStore.setAutoResume(isChecked) }
        }
    }

    private fun setupCacheControls() {
        updateCacheSizeDisplay()

        binding.btnClearDisposableCache.setOnClickListener {
            storageManager.clearDisposableCache()
            updateCacheSizeDisplay()
            Toast.makeText(requireContext(), "Disposable cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCacheSizeDisplay() {
        val mb = storageManager.getDisposableCacheSizeMb()
        binding.tvCacheSizeSettings.text = String.format("%.1f MB", mb)
    }

    
    private fun setupPermissionsHub() {
        updatePermissionStatuses()

        binding.btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().packageName)
                )
                startActivity(intent)
            }
        }

        binding.btnPermNotification.setOnClickListener {
            val intent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                } else {
                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    putExtra("app_package", requireContext().packageName)
                    putExtra("app_uid", requireContext().applicationInfo.uid)
                }
            }
            startActivity(intent)
        }

        binding.btnPermBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:" + requireContext().packageName)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallback)
                }
            }
        }
    }

    private fun updatePermissionStatuses() {
        // Overlay
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(requireContext())
        } else true
        binding.tvPermOverlayStatus.text = if (hasOverlay) "Active (Granted)" else "Permission needed for Floating Bubble"
        binding.btnPermOverlay.text = if (hasOverlay) "Granted" else "Grant"
        binding.btnPermOverlay.isEnabled = !hasOverlay

        // Notifications
        val hasNotif = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        binding.tvPermNotificationStatus.text = if (hasNotif) "Active (Granted)" else "Notifications are disabled"
        binding.btnPermNotification.text = if (hasNotif) "Granted" else "Grant"

        // Battery
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
        } else true
        binding.tvPermBatteryStatus.text = if (isIgnoringBattery) "Active (Unrestricted)" else "Optimized (May be paused)"
        binding.btnPermBattery.text = if (isIgnoringBattery) "Granted" else "Grant"
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
