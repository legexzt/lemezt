package com.lemezt.app.feature.player

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lemezt.app.R
import com.lemezt.app.databinding.ActivityVideoPlayerBinding
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isControlsVisible = true

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

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "lemezt Video"

        binding.tvPlayerTitle.text = title

        setupVideo(uriStr)
        setupControls()
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
            binding.videoBufferingBar.visibility = View.GONE
            val duration = binding.videoView.duration
            binding.playerSeekBar.max = duration
            binding.tvPlayerTotalTime.text = formatTime(duration)

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
        }

        binding.btnPlayerForward.setOnClickListener {
            val current = binding.videoView.currentPosition
            val max = binding.videoView.duration
            binding.videoView.seekTo((current + 10000).coerceAtMost(max))
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

        binding.videoPlayerRoot.setOnClickListener {
            toggleControls()
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
    }
}
