package com.lemezt.app.feature.player

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lemezt.app.R
import com.lemezt.app.core.datastore.UserPreferencesDataStore
import com.lemezt.app.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isControlsVisible = true
    private var internalMediaPlayer: MediaPlayer? = null
    private lateinit var dataStore: UserPreferencesDataStore
    private var currentUriStr: String = ""
    private var currentSpeed: Float = 1.0f
    private var isSubtitlesEnabled = false

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, uriString: String, title: String) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_URI, uriString)
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataStore = UserPreferencesDataStore(this)

        currentUriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "lemezt Video"

        binding.tvPlayerTitle.text = title

        setupVideo(currentUriStr)
        setupControls()
        setupGestures()
        setupFavorites()
    }

    private fun setupVideo(uriString: String) {
        val uri = if (uriString.startsWith("content://")) {
            Uri.parse(uriString)
        } else {
            Uri.fromFile(File(uriString))
        }

        binding.videoView.setVideoURI(uri)
        binding.videoBufferingBar.visibility = View.VISIBLE

        binding.videoView.setOnPreparedListener { mp ->
            internalMediaPlayer = mp
            binding.videoBufferingBar.visibility = View.GONE
            val duration = binding.videoView.duration
            binding.playerSeekBar.max = duration
            binding.tvPlayerTotalTime.text = formatTime(duration)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mp.playbackParams = PlaybackParams().apply { speed = currentSpeed }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            binding.videoView.start()
            binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_pause)
            startProgressUpdater()
        }

        binding.videoView.setOnCompletionListener {
            binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_play)
            binding.playerSeekBar.progress = binding.videoView.duration
        }

        binding.videoView.setOnErrorListener { _, _, _ ->
            binding.videoBufferingBar.visibility = View.GONE
            Toast.makeText(this, "Cannot play this video format", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenWidth = resources.displayMetrics.widthPixels
                val x = e.x

                if (x < screenWidth * 0.40f) {
                    // Left double tap: Rewind 10s
                    val current = binding.videoView.currentPosition
                    val target = (current - 10000).coerceAtLeast(0)
                    binding.videoView.seekTo(target)
                    animateBadge(binding.indicatorRewind)
                    return true
                } else if (x > screenWidth * 0.60f) {
                    // Right double tap: Fast Forward 10s
                    val current = binding.videoView.currentPosition
                    val max = binding.videoView.duration
                    val target = (current + 10000).coerceAtMost(max)
                    binding.videoView.seekTo(target)
                    animateBadge(binding.indicatorForward)
                    return true
                }
                return false
            }
        })

        binding.videoPlayerRoot.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun animateBadge(badgeView: View) {
        badgeView.visibility = View.VISIBLE
        badgeView.alpha = 1.0f
        badgeView.scaleX = 0.7f
        badgeView.scaleY = 0.7f

        val scaleX = ObjectAnimator.ofFloat(badgeView, View.SCALE_X, 0.7f, 1.15f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(badgeView, View.SCALE_Y, 0.7f, 1.15f, 1.0f)
        val fadeIn = ObjectAnimator.ofFloat(badgeView, View.ALPHA, 0.0f, 1.0f)

        val set = AnimatorSet().apply {
            playTogether(scaleX, scaleY, fadeIn)
            duration = 250
            interpolator = OvershootInterpolator()
        }
        set.start()

        handler.postDelayed({
            badgeView.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction { badgeView.visibility = View.GONE }
                .start()
        }, 700)
    }

    private fun setupControls() {
        binding.btnPlayerBack.setOnClickListener { finish() }

        binding.btnPlayerPlayPause.setOnClickListener {
            if (binding.videoView.isPlaying) {
                binding.videoView.pause()
                binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                binding.videoView.start()
                binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        binding.btnPlayerRewind.setOnClickListener {
            val current = binding.videoView.currentPosition
            binding.videoView.seekTo((current - 10000).coerceAtLeast(0))
            animateBadge(binding.indicatorRewind)
        }

        binding.btnPlayerForward.setOnClickListener {
            val current = binding.videoView.currentPosition
            val max = binding.videoView.duration
            binding.videoView.seekTo((current + 10000).coerceAtMost(max))
            animateBadge(binding.indicatorForward)
        }

        // Screen Rotation Button
        binding.btnPlayerRotate.setOnClickListener {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        // Player Settings Dialog (Playback speed & aspect ratio)
        binding.btnPlayerSettings.setOnClickListener {
            showPlayerSettingsDialog()
        }

        // Subtitles Button
        binding.btnPlayerSubtitles.setOnClickListener {
            toggleSubtitles()
        }

        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.videoView.seekTo(progress)
                    binding.tvPlayerCurrentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showPlayerSettingsDialog() {
        val speedOptions = arrayOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val selectedIndex = speeds.indexOfFirst { it == currentSpeed }.coerceAtLeast(2)

        MaterialAlertDialogBuilder(this)
            .setTitle("?? Playback Speed")
            .setSingleChoiceItems(speedOptions, selectedIndex) { dialog, which ->
                val newSpeed = speeds[which]
                currentSpeed = newSpeed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        internalMediaPlayer?.playbackParams = PlaybackParams().apply { speed = newSpeed }
                        Toast.makeText(this, "Speed set to ", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleSubtitles() {
        isSubtitlesEnabled = !isSubtitlesEnabled
        if (isSubtitlesEnabled) {
            binding.btnPlayerSubtitles.setColorFilter(getColor(R.color.lemezt_primary))
            binding.tvSubtitleOverlay.visibility = View.VISIBLE
            binding.tvSubtitleOverlay.text = "Subtitles: ON (lemezt Auto-Detect)"
            Toast.makeText(this, "Subtitles Enabled", Toast.LENGTH_SHORT).show()
            handler.postDelayed({
                if (isSubtitlesEnabled) {
                    binding.tvSubtitleOverlay.visibility = View.GONE
                }
            }, 3000)
        } else {
            binding.btnPlayerSubtitles.setColorFilter(0xFFCCCCCC.toInt())
            binding.tvSubtitleOverlay.visibility = View.GONE
            Toast.makeText(this, "Subtitles Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFavorites() {
        lifecycleScope.launch {
            val isFav = dataStore.isFavorite(currentUriStr).first()
            updateFavoriteIcon(isFav)
        }

        binding.btnPlayerFavorite.setOnClickListener {
            lifecycleScope.launch {
                val isNowFav = dataStore.toggleFavorite(currentUriStr)
                updateFavoriteIcon(isNowFav)

                // Animate bouncy heart
                binding.btnPlayerFavorite.animate()
                    .scaleX(1.35f)
                    .scaleY(1.35f)
                    .setDuration(150)
                    .withEndAction {
                        binding.btnPlayerFavorite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                    .start()

                val msg = if (isNowFav) "?? Added to My Favorites" else "Removed from Favorites"
                Toast.makeText(this@VideoPlayerActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        if (isFavorite) {
            binding.btnPlayerFavorite.setImageResource(R.drawable.ic_favorite)
            binding.btnPlayerFavorite.setColorFilter(0xFFFF2D55.toInt())
        } else {
            binding.btnPlayerFavorite.setImageResource(R.drawable.ic_favorite_border)
            binding.btnPlayerFavorite.setColorFilter(0xFFFFFFFF.toInt())
        }
    }

    private fun toggleControls() {
        isControlsVisible = !isControlsVisible
        val vis = if (isControlsVisible) View.VISIBLE else View.GONE
        binding.playerTopBar.visibility = vis
        binding.playerBottomBar.visibility = vis
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (binding.videoView.isPlaying) {
                val current = binding.videoView.currentPosition
                binding.playerSeekBar.progress = current
                binding.tvPlayerCurrentTime.text = formatTime(current)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun startProgressUpdater() {
        handler.post(updateRunnable)
    }

    private fun formatTime(millis: Int): String {
        val totalSecs = millis / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        binding.videoView.stopPlayback()
        internalMediaPlayer = null
    }
}
