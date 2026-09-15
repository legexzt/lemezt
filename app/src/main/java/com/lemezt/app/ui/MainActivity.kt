package com.lemezt.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lemezt.app.R
import com.lemezt.app.databinding.ActivityMainBinding
import com.lemezt.app.engine.YouTubeParser
import com.lemezt.app.feature.downloadoptions.DownloadOptionsBottomSheet
import com.lemezt.app.feature.downloads.DownloadsFragment
import com.lemezt.app.feature.home.HomeFragment
import com.lemezt.app.feature.settings.SettingsFragment
import com.lemezt.app.transfer.TransferCoordinator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val homeFragment by lazy { HomeFragment() }
    private val downloadsFragment by lazy { DownloadsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize core background transfer coordinator
        TransferCoordinator.init(this)

        if (savedInstanceState == null) {
            replaceFragment(homeFragment)
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    replaceFragment(homeFragment)
                    true
                }
                R.id.navigation_downloads -> {
                    replaceFragment(downloadsFragment)
                    true
                }
                R.id.navigation_settings -> {
                    replaceFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Automatic background check for OTA updates via Firebase Remote Config
        com.lemezt.app.feature.update.UpdateManager.checkForUpdate(this, isManualCheck = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        if (incomingIntent?.getBooleanExtra("EXTRA_TRIGGER_UPDATE", false) == true) {
            val versionName = incomingIntent.getStringExtra("EXTRA_UPDATE_VERSION") ?: "Latest"
            val updateUrl = incomingIntent.getStringExtra("EXTRA_UPDATE_URL") ?: ""
            val notes = incomingIntent.getStringExtra("EXTRA_UPDATE_NOTES") ?: "Performance improvements & bug fixes."
            if (updateUrl.isNotBlank()) {
                com.lemezt.app.feature.update.UpdateManager.showUpdateDialog(
                    this,
                    versionName,
                    updateUrl,
                    notes,
                    false
                )
            }
            return
        }

        val sharedText = when (incomingIntent?.action) {
            Intent.ACTION_SEND -> incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> incomingIntent.dataString
            else -> incomingIntent?.getStringExtra("url")
        }

        if (sharedText.isNullOrBlank()) return

        val videoId = YouTubeParser.extractVideoId(sharedText)
        if (videoId == null) return

        selectTab(R.id.navigation_home)

        lifecycleScope.launch {
            val result = YouTubeParser.fetchVideoDetails(videoId)
            result.onSuccess { info ->
                val sheet = DownloadOptionsBottomSheet.newInstance(info, sharedText)
                sheet.show(supportFragmentManager, "DownloadOptionsSheet")
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, err.message ?: "Failed to resolve video", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun selectTab(itemId: Int) {
        binding.bottomNavigation.selectedItemId = itemId
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHostContainer, fragment)
            .commit()
    }
}
