package com.lemezt.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.lemezt.app.R
import com.lemezt.app.engine.YouTubeParser
import com.lemezt.app.ui.BatchReviewActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service that assists batch downloading of multiple YouTube videos.
 * Displays a persistent shutter notification with link count, Done button, and
 * a floating draggable overlay pill on screen with an [+ Add] button and live counter.
 */
class BatchCollectorService : Service() {

    companion object {
        const val CHANNEL_ID = "lemezt_batch_collector"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.lemezt.app.action.START_BATCH_COLLECTOR"
        const val ACTION_STOP = "com.lemezt.app.action.STOP_BATCH_COLLECTOR"
        const val ACTION_OPEN_YOUTUBE = "com.lemezt.app.action.OPEN_YOUTUBE"
        const val ACTION_TRIGGER_ADD = "com.lemezt.app.action.TRIGGER_ADD"

        var isRunning = false
            private set

        private val _collectedLinks = MutableStateFlow<List<String>>(emptyList())
        val collectedLinks: StateFlow<List<String>> = _collectedLinks.asStateFlow()

        var instance: BatchCollectorService? = null
            private set

        fun addVideoId(videoId: String, context: Context): Boolean {
            val current = _collectedLinks.value.toMutableList()
            if (!current.contains(videoId)) {
                current.add(videoId)
                _collectedLinks.value = current
                instance?.updateNotificationAndPill(current.size)

                // Haptic feedback
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(60)
                    }
                } catch (_: Exception) {}
                return true
            }
            return false
        }

        fun removeVideoId(videoId: String) {
            val current = _collectedLinks.value.toMutableList()
            current.remove(videoId)
            _collectedLinks.value = current
            instance?.updateNotificationAndPill(current.size)
        }

        fun clearCollected() {
            _collectedLinks.value = emptyList()
            instance?.updateNotificationAndPill(0)
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingPillView: View? = null
    private var clipboardManager: ClipboardManager? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.d("BatchCollector", "Clipboard changed detected! Triggering capture activity...")
        triggerClipboardCapture()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(_collectedLinks.value.size))

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.e("BatchCollector", "Could not attach clip listener", e)
        }

        setupFloatingPill()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_YOUTUBE -> {
                openYouTubeApp()
            }
            ACTION_TRIGGER_ADD -> {
                triggerClipboardCapture()
            }
        }
        return START_STICKY
    }

    fun triggerClipboardCapture() {
        try {
            val intent = Intent(this, ClipboardReaderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("BatchCollector", "Failed to launch ClipboardReaderActivity", e)
            checkClipboardDirect()
        }
    }

    private fun checkClipboardDirect() {
        try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                val videoId = YouTubeParser.extractVideoId(text)
                if (videoId != null) {
                    addVideoId(videoId, this)
                }
            }
        } catch (e: Exception) {
            Log.e("BatchCollector", "Direct clipboard read failed", e)
        }
    }

    private fun setupFloatingPill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            floatingPillView = inflater.inflate(R.layout.layout_batch_collector, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 30
                y = 300
            }

            val tvCount = floatingPillView?.findViewById<TextView>(R.id.tvPillCount)
            val btnAdd = floatingPillView?.findViewById<TextView>(R.id.btnPillAdd)
            val btnDone = floatingPillView?.findViewById<TextView>(R.id.btnPillDone)

            tvCount?.text = "${_collectedLinks.value.size} Links"

            btnAdd?.setOnClickListener {
                triggerClipboardCapture()
            }

            btnDone?.setOnClickListener {
                openBatchReviewScreen()
            }

            // Draggable touch listener with coordinate detection for child buttons
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            floatingPillView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (initialTouchX - event.rawX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingPillView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 15 && diffY < 15) {
                            // Check if tapped inside Done or Add or Pill Body
                            val doneRect = Rect()
                            btnDone?.getGlobalVisibleRect(doneRect)
                            val addRect = Rect()
                            btnAdd?.getGlobalVisibleRect(addRect)

                            val tapX = event.rawX.toInt()
                            val tapY = event.rawY.toInt()

                            if (doneRect.contains(tapX, tapY)) {
                                openBatchReviewScreen()
                            } else if (addRect.contains(tapX, tapY)) {
                                triggerClipboardCapture()
                            } else {
                                // Tapped pill body: grab clipboard!
                                triggerClipboardCapture()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(floatingPillView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateNotificationAndPill(count: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(count))

        floatingPillView?.findViewById<TextView>(R.id.tvPillCount)?.text = "$count Links"
    }

    private fun buildNotification(count: Int): Notification {
        val reviewIntent = Intent(this, BatchReviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val reviewPending = PendingIntent.getActivity(
            this, 10, reviewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val addIntent = Intent(this, ClipboardReaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        val addPending = PendingIntent.getActivity(
            this, 13, addIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ytIntent = Intent(this, BatchCollectorService::class.java).apply {
            action = ACTION_OPEN_YOUTUBE
        }
        val ytPending = PendingIntent.getService(
            this, 11, ytIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BatchCollectorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 12, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "lemezt Batch Collector Active"
        val message = if (count == 0) {
            "Copy links in YouTube to collect. Tap '+ Add' or 'Done'."
        } else {
            "Captured $count links! Tap 'Done & Download' to pick formats."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_lemezt_logo)
            .setContentIntent(reviewPending)
            .setOngoing(true)
            .addAction(R.drawable.ic_download, "+ Add Link", addPending)
            .addAction(R.drawable.ic_download, "Done ($count)", reviewPending)
            .addAction(R.drawable.ic_video, "Open YouTube", ytPending)
            .addAction(R.drawable.ic_close, "Stop", stopPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun openYouTubeApp() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.youtube.com")
                setPackage("com.google.android.youtube")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(webIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not open YouTube", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBatchReviewScreen() {
        val count = _collectedLinks.value.size
        if (count == 0) {
            Toast.makeText(this, "No links captured yet! Copy links in YouTube first.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, BatchReviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Batch Link Collector",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors copied YouTube links for batch downloading"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null

        clipboardManager?.removePrimaryClipChangedListener(clipListener)

        if (floatingPillView != null && windowManager != null) {
            try {
                windowManager?.removeView(floatingPillView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingPillView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
