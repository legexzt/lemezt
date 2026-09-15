package com.lemezt.app.feature.player

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lemezt.app.R
import com.lemezt.app.ui.MainActivity
import com.lemezt.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

data class AudioTrack(
    val uriString: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null
)

class AudioPlayerService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var currentArtworkBitmap: Bitmap? = null

    companion object {
        const val ACTION_PLAY = "com.lemezt.app.action.PLAY"
        const val ACTION_TOGGLE = "com.lemezt.app.action.TOGGLE"
        const val ACTION_SEEK = "com.lemezt.app.action.SEEK"
        const val ACTION_REWIND = "com.lemezt.app.action.REWIND"
        const val ACTION_FORWARD = "com.lemezt.app.action.FORWARD"
        const val ACTION_SPEED = "com.lemezt.app.action.SPEED"
        const val ACTION_STOP = "com.lemezt.app.action.STOP"

        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ARTWORK = "extra_artwork"
        const val EXTRA_SEEK_MS = "extra_seek_ms"
        const val EXTRA_SPEED = "extra_speed"

        private const val NOTIFICATION_ID = 4001

        private val _currentTrack = MutableStateFlow<AudioTrack?>(null)
        val currentTrack: StateFlow<AudioTrack?> = _currentTrack.asStateFlow()

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        private val _currentPosition = MutableStateFlow(0)
        val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

        private val _duration = MutableStateFlow(0)
        val duration: StateFlow<Int> = _duration.asStateFlow()

        private val _playbackSpeed = MutableStateFlow(1.0f)
        val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

        fun playTrack(context: Context, track: AudioTrack) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_URI, track.uriString)
                putExtra(EXTRA_TITLE, track.title)
                putExtra(EXTRA_ARTIST, track.artist)
                putExtra(EXTRA_ARTWORK, track.artworkUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun toggle(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply { action = ACTION_TOGGLE }
            context.startService(intent)
        }

        fun seekTo(context: Context, ms: Int) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_SEEK_MS, ms)
            }
            context.startService(intent)
        }

        fun rewind10(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply { action = ACTION_REWIND }
            context.startService(intent)
        }

        fun forward10(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply { action = ACTION_FORWARD }
            context.startService(intent)
        }

        fun setSpeed(context: Context, speed: Float) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_SPEED
                putExtra(EXTRA_SPEED, speed)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val uri = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "lemezt Audio"
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "Unknown Artist"
                val artwork = intent.getStringExtra(EXTRA_ARTWORK)
                val track = AudioTrack(uri, title, artist, artwork)
                handlePlay(track)
            }
            ACTION_TOGGLE -> handleToggle()
            ACTION_SEEK -> {
                val ms = intent.getIntExtra(EXTRA_SEEK_MS, 0)
                handleSeek(ms)
            }
            ACTION_REWIND -> handleRewind()
            ACTION_FORWARD -> handleForward()
            ACTION_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                handleSpeed(speed)
            }
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handlePlay(track: AudioTrack) {
        _currentTrack.value = track
        mediaPlayer?.release()
        mediaPlayer = null

        try {
            val uri = if (track.uriString.startsWith("content://")) {
                Uri.parse(track.uriString)
            } else {
                Uri.fromFile(File(track.uriString))
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(applicationContext, uri)
                prepare()
                start()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    playbackParams = PlaybackParams().apply { speed = _playbackSpeed.value }
                }

                _duration.value = duration
                _isPlaying.value = true

                setOnCompletionListener {
                    _isPlaying.value = false
                    updateNotification()
                }
            }

            startProgressTicker()
            loadArtworkAndShowNotification(track)

        } catch (e: Exception) {
            e.printStackTrace()
            _isPlaying.value = false
            stopSelf()
        }
    }

    private fun handleToggle() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                _isPlaying.value = false
            } else {
                mp.start()
                _isPlaying.value = true
                startProgressTicker()
            }
            updateNotification()
        }
    }

    private fun handleSeek(ms: Int) {
        mediaPlayer?.let { mp ->
            mp.seekTo(ms.coerceIn(0, mp.duration))
            _currentPosition.value = mp.currentPosition
        }
    }

    private fun handleRewind() {
        mediaPlayer?.let { mp ->
            val target = (mp.currentPosition - 10000).coerceAtLeast(0)
            mp.seekTo(target)
            _currentPosition.value = target
        }
    }

    private fun handleForward() {
        mediaPlayer?.let { mp ->
            val target = (mp.currentPosition + 10000).coerceAtMost(mp.duration)
            mp.seekTo(target)
            _currentPosition.value = target
        }
    }

    private fun handleSpeed(speed: Float) {
        _playbackSpeed.value = speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer?.let { mp ->
                try {
                    mp.playbackParams = PlaybackParams().apply { this.speed = speed }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleStop() {
        _isPlaying.value = false
        _currentTrack.value = null
        progressJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        _currentPosition.value = mp.currentPosition
                    }
                }
                delay(500)
            }
        }
    }

    private fun loadArtworkAndShowNotification(track: AudioTrack) {
        serviceScope.launch {
            if (!track.artworkUrl.isNullOrBlank()) {
                currentArtworkBitmap = withContext(Dispatchers.IO) {
                    try {
                        val input = URL(track.artworkUrl).openStream()
                        BitmapFactory.decodeStream(input)
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                currentArtworkBitmap = null
            }
            updateNotification()
        }
    }

    private fun updateNotification() {
        val track = _currentTrack.value ?: return

        // PendingIntent to launch app
        val contentIntent = PendingIntent.getActivity(
            this,
            3001,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action intents
        val rewindIntent = PendingIntent.getService(
            this, 3002,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_REWIND },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 3003,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val forwardIntent = PendingIntent.getService(
            this, 3004,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_FORWARD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 3005,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (_isPlaying.value) R.drawable.ic_pause else R.drawable.ic_play

        val builder = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText("lemezt Player")
            .setSmallIcon(R.drawable.ic_audio)
            .setLargeIcon(currentArtworkBitmap)
            .setContentIntent(contentIntent)
            .setOngoing(_isPlaying.value)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_replay_10, "-10s", rewindIntent)
            .addAction(playPauseIcon, if (_isPlaying.value) "Pause" else "Play", toggleIntent)
            .addAction(R.drawable.ic_forward_10, "+10s", forwardIntent)
            .addAction(R.drawable.ic_close, "Close", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )

        startForeground(NOTIFICATION_ID, builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
        _currentTrack.value = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
