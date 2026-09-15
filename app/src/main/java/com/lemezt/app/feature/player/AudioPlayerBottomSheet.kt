package com.lemezt.app.feature.player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lemezt.app.R
import com.lemezt.app.core.datastore.UserPreferencesDataStore
import com.lemezt.app.core.util.ImageLoader
import com.lemezt.app.databinding.BottomSheetAudioPlayerBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AudioPlayerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAudioPlayerBinding? = null
    private val binding get() = _binding!!

    private var vinylAnimator: ObjectAnimator? = null
    private lateinit var dataStore: UserPreferencesDataStore

    private var inputUri: String = ""
    private var inputTitle: String = ""
    private var inputArtist: String = ""
    private var inputArtwork: String? = null

    companion object {
        fun newInstance(uri: String, title: String, artist: String, artwork: String?): AudioPlayerBottomSheet {
            val sheet = AudioPlayerBottomSheet()
            sheet.inputUri = uri
            sheet.inputTitle = title
            sheet.inputArtist = artist
            sheet.artworkUrl = artwork
            return sheet
        }

        fun showExisting(): AudioPlayerBottomSheet {
            return AudioPlayerBottomSheet()
        }
    }

    private var artworkUrl: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAudioPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataStore = UserPreferencesDataStore(requireContext())

        setupVinylAnimation()

        // If a new track was passed, start playing it via background service
        if (inputUri.isNotBlank()) {
            val track = AudioTrack(inputUri, inputTitle, inputArtist, artworkUrl)
            AudioPlayerService.playTrack(requireContext(), track)
        }

        observeServiceState()
        setupControls()
    }

    private fun setupVinylAnimation() {
        vinylAnimator = ObjectAnimator.ofFloat(binding.ivAudioArtwork, View.ROTATION, 0f, 360f).apply {
            duration = 10000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun observeServiceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            AudioPlayerService.currentTrack.collect { track ->
                if (track != null) {
                    binding.tvAudioTitle.text = track.title
                    binding.tvAudioArtist.text = track.artist
                    if (!track.artworkUrl.isNullOrEmpty()) {
                        ImageLoader.load(track.artworkUrl, binding.ivAudioArtwork, cornerRadiusDp = 100f)
                    } else {
                        binding.ivAudioArtwork.setImageResource(R.drawable.ic_download)
                    }
                    observeFavorite(track.uriString)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AudioPlayerService.isPlaying.collect { playing ->
                binding.btnAudioPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                if (playing) {
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

        viewLifecycleOwner.lifecycleScope.launch {
            AudioPlayerService.currentPosition.collect { cur ->
                binding.audioSeekBar.progress = cur
                binding.tvAudioCurrentTime.text = formatTime(cur)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AudioPlayerService.duration.collect { dur ->
                binding.audioSeekBar.max = dur
                binding.tvAudioTotalTime.text = formatTime(dur)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AudioPlayerService.playbackSpeed.collect { speed ->
                binding.btnAudioSpeed.text = String.format("%.1fx", speed)
            }
        }
    }

    private fun observeFavorite(uri: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val isFav = dataStore.isFavorite(uri).first()
            updateFavoriteIcon(isFav)
        }

        binding.btnAudioFavorite.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val isNowFav = dataStore.toggleFavorite(uri)
                updateFavoriteIcon(isNowFav)

                binding.btnAudioFavorite.animate()
                    .scaleX(1.35f)
                    .scaleY(1.35f)
                    .setDuration(150)
                    .withEndAction {
                        binding.btnAudioFavorite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                    .start()

                val msg = if (isNowFav) "?? Added to My Favorites" else "Removed from Favorites"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        if (isFavorite) {
            binding.btnAudioFavorite.setImageResource(R.drawable.ic_favorite)
            binding.btnAudioFavorite.setColorFilter(0xFFFF2D55.toInt())
        } else {
            binding.btnAudioFavorite.setImageResource(R.drawable.ic_favorite_border)
            binding.btnAudioFavorite.clearColorFilter()
        }
    }

    private fun setupControls() {
        binding.btnAudioPlayPause.setOnClickListener {
            AudioPlayerService.toggle(requireContext())
        }

        binding.btnAudioRewind.setOnClickListener {
            AudioPlayerService.rewind10(requireContext())
        }

        binding.btnAudioForward.setOnClickListener {
            AudioPlayerService.forward10(requireContext())
        }

        // Cycle through speed options: 1.0x -> 1.25x -> 1.5x -> 2.0x -> 0.75x -> 1.0x
        binding.btnAudioSpeed.setOnClickListener {
            val current = AudioPlayerService.playbackSpeed.value
            val nextSpeed = when (current) {
                1.0f -> 1.25f
                1.25f -> 1.5f
                1.5f -> 2.0f
                2.0f -> 0.75f
                else -> 1.0f
            }
            AudioPlayerService.setSpeed(requireContext(), nextSpeed)
            Toast.makeText(requireContext(), "Speed: x", Toast.LENGTH_SHORT).show()
        }

        binding.audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    AudioPlayerService.seekTo(requireContext(), progress)
                    binding.tvAudioCurrentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun formatTime(millis: Int): String {
        val totalSecs = millis / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        vinylAnimator?.cancel()
        vinylAnimator = null
        _binding = null
    }
}
