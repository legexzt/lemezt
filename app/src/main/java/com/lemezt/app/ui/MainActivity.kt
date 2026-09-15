package com.lemezt.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lemezt.app.R
import com.lemezt.app.core.database.LemeztDatabase
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.core.datastore.UserPreferencesDataStore
import com.lemezt.app.core.util.ImageLoader
import com.lemezt.app.databinding.ActivityMainBinding
import com.lemezt.app.engine.YouTubeParser
import com.lemezt.app.feature.downloadoptions.DownloadOptionsBottomSheet
import com.lemezt.app.feature.downloads.DownloadsFragment
import com.lemezt.app.feature.favorites.FavoritesDrawerAdapter
import com.lemezt.app.feature.floating.FloatingCollectorService
import com.lemezt.app.feature.home.HomeFragment
import com.lemezt.app.feature.player.AudioPlayerBottomSheet
import com.lemezt.app.feature.player.AudioPlayerService
import com.lemezt.app.feature.player.VideoPlayerActivity
import com.lemezt.app.feature.settings.SettingsFragment
import com.lemezt.app.transfer.TransferCoordinator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val homeFragment by lazy { HomeFragment() }
    private val downloadsFragment by lazy { DownloadsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    private var vinylAnimator: ObjectAnimator? = null
    private lateinit var favoritesAdapter: FavoritesDrawerAdapter

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

        setupDrawer()
        setupDynamicIsland()
        handleIncomingIntent(intent)

        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                closeDrawer()
            } else {
                finish()
            }
        }
    }

    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun setupDrawer() {
        favoritesAdapter = FavoritesDrawerAdapter { item ->
            closeDrawer()
            playMediaItem(item)
        }
        binding.drawerContent.rvDrawerFavorites.layoutManager = LinearLayoutManager(this)
        binding.drawerContent.rvDrawerFavorites.adapter = favoritesAdapter

        binding.drawerContent.btnDrawerHome.setOnClickListener {
            closeDrawer()
            selectTab(R.id.navigation_home)
        }

        binding.drawerContent.btnDrawerDownloads.setOnClickListener {
            closeDrawer()
            selectTab(R.id.navigation_downloads)
        }

        binding.drawerContent.btnDrawerMulti.setOnClickListener {
            closeDrawer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission needed for Floating Collector. Opening Settings...", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                FloatingCollectorService.start(this)
                Toast.makeText(this, "Floating Collector active! Open YouTube and copy links.", Toast.LENGTH_LONG).show()
            }
        }

        binding.drawerContent.btnDrawerSettings.setOnClickListener {
            closeDrawer()
            selectTab(R.id.navigation_settings)
        }

        binding.drawerContent.btnDrawerUpdate.setOnClickListener {
            closeDrawer()
            com.lemezt.app.feature.update.UpdateManager.checkForUpdate(this, isManualCheck = true)
        }

        // Live observe favorite tracks and completed downloads
        val database = LemeztDatabase.getInstance(this)
        val dataStore = UserPreferencesDataStore(this)
        lifecycleScope.launch {
            combine(
                database.downloadDao().getCompletedDownloadsFlow(),
                dataStore.favoriteUris
            ) { completedList, favSet ->
                completedList.filter { item ->
                    val path = item.publicUriString ?: item.relativeTempPath
                    path != null && favSet.contains(path)
                }
            }.collect { favorites ->
                binding.drawerContent.tvDrawerFavCount.text = favorites.size.toString()
                if (favorites.isEmpty()) {
                    binding.drawerContent.tvDrawerEmptyFav.visibility = View.VISIBLE
                    binding.drawerContent.rvDrawerFavorites.visibility = View.GONE
                } else {
                    binding.drawerContent.tvDrawerEmptyFav.visibility = View.GONE
                    binding.drawerContent.rvDrawerFavorites.visibility = View.VISIBLE
                    favoritesAdapter.submitList(favorites)
                }
            }
        }
    }

    private fun playMediaItem(item: DownloadEntity) {
        val mediaPath = item.publicUriString ?: item.relativeTempPath
        if (mediaPath.isNullOrEmpty()) {
            Toast.makeText(this, "Media file not found", Toast.LENGTH_SHORT).show()
            return
        }
        val isVideo = item.formatType == "VIDEO" || item.container.lowercase() == "mp4"
        if (isVideo) {
            VideoPlayerActivity.start(this, mediaPath, item.title)
        } else {
            AudioPlayerBottomSheet.newInstance(
                uri = mediaPath,
                title = item.title,
                artist = item.author ?: "lemezt",
                artwork = item.thumbnailUrl
            ).show(supportFragmentManager, "AudioPlayerSheet")
        }
    }

    private fun setupDynamicIsland() {
        vinylAnimator = ObjectAnimator.ofFloat(binding.ivIslandArtwork, View.ROTATION, 0f, 360f).apply {
            duration = 10000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        // Tap pill to open full AudioPlayerBottomSheet
        binding.dynamicIslandPill.setOnClickListener {
            val sheet = AudioPlayerBottomSheet.showExisting()
            sheet.show(supportFragmentManager, "AudioPlayerSheet")
        }

        binding.btnIslandPlayPause.setOnClickListener {
            AudioPlayerService.toggle(this)
        }

        binding.btnIslandRewind.setOnClickListener {
            AudioPlayerService.rewind10(this)
        }

        binding.btnIslandClose.setOnClickListener {
            AudioPlayerService.stop(this)
        }

        lifecycleScope.launch {
            AudioPlayerService.currentTrack.collect { track ->
                if (track != null) {
                    showDynamicIsland()
                    binding.tvIslandTitle.text = track.title
                    binding.tvIslandTitle.isSelected = true
                    binding.tvIslandArtist.text = track.artist

                    if (!track.artworkUrl.isNullOrBlank()) {
                        ImageLoader.load(track.artworkUrl, binding.ivIslandArtwork, cornerRadiusDp = 100f)
                    } else {
                        binding.ivIslandArtwork.setImageResource(R.drawable.ic_download)
                    }
                } else {
                    hideDynamicIsland()
                }
            }
        }

        lifecycleScope.launch {
            AudioPlayerService.isPlaying.collect { isPlaying ->
                binding.btnIslandPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) {
                    if (vinylAnimator?.isPaused == true) {
                        vinylAnimator?.resume()
                    } else if (vinylAnimator?.isStarted != true) {
                        vinylAnimator?.start()
                    }
                } else {
                    vinylAnimator?.pause()
                }
            }
        }
    }

    private fun showDynamicIsland() {
        if (binding.dynamicIslandContainer.visibility != View.VISIBLE) {
            binding.dynamicIslandContainer.apply {
                alpha = 0f
                translationY = 40f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun hideDynamicIsland() {
        if (binding.dynamicIslandContainer.visibility == View.VISIBLE) {
            binding.dynamicIslandContainer.animate()
                .alpha(0f)
                .translationY(40f)
                .setDuration(250)
                .withEndAction {
                    binding.dynamicIslandContainer.visibility = View.GONE
                    vinylAnimator?.pause()
                }
                .start()
        }
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

    override fun onDestroy() {
        super.onDestroy()
        vinylAnimator?.cancel()
        vinylAnimator = null
    }
}
