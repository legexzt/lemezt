package com.lemezt.app.feature.player

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lemezt.app.R
import com.lemezt.app.core.util.ImageLoader
import com.lemezt.app.databinding.BottomSheetAudioPlayerBinding
import java.io.File

class AudioPlayerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAudioPlayerBinding? = null
    private val binding get() = _binding!!

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var uriString: String = ""
    private var songTitle: String = ""
    private var artist: String = ""
    private var artworkUrl: String? = null

    companion object {
        fun newInstance(uri: String, title: String, artist: String, artwork: String?): AudioPlayerBottomSheet {
            val sheet = AudioPlayerBottomSheet()
            sheet.uriString = uri
            sheet.songTitle = title
            sheet.artist = artist
            sheet.artworkUrl = artwork
            return sheet
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAudioPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvAudioTitle.text = songTitle
        binding.tvAudioArtist.text = artist

        if (!artworkUrl.isNullOrEmpty()) {
            ImageLoader.load(artworkUrl, binding.ivAudioArtwork, cornerRadiusDp = 16f)
        }

        setupAudio()
        setupControls()
    }

    private fun setupAudio() {
        try {
            val uri = if (uriString.startsWith("content://")) {
                Uri.parse(uriString)
            } else {
                Uri.fromFile(File(uriString))
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(requireContext(), uri)
                prepare()
                start()
                binding.btnAudioPlayPause.setImageResource(R.drawable.ic_pause)

                val duration = this.duration
                binding.audioSeekBar.max = duration
                binding.tvAudioTotalTime.text = formatTime(duration)

                setOnCompletionListener {
                    binding.btnAudioPlayPause.setImageResource(R.drawable.ic_play)
                    binding.audioSeekBar.progress = duration
                }
            }

            handler.post(progressUpdater)

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Cannot play audio file", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun setupControls() {
        binding.btnAudioPlayPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                binding.btnAudioPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                mp.start()
                binding.btnAudioPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        binding.btnAudioRewind.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            mp.seekTo((mp.currentPosition - 10000).coerceAtLeast(0))
        }

        binding.btnAudioForward.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            mp.seekTo((mp.currentPosition + 10000).coerceAtMost(mp.duration))
        }

        binding.audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    binding.tvAudioCurrentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    val cur = mp.currentPosition
                    binding.audioSeekBar.progress = cur
                    binding.tvAudioCurrentTime.text = formatTime(cur)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSecs = millis / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(progressUpdater)
        mediaPlayer?.release()
        mediaPlayer = null
        _binding = null
    }
}
