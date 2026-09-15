package com.lemezt.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.lemezt.app.engine.DownloadResult
import com.lemezt.app.engine.DownloadTask
import com.lemezt.app.engine.MediaStoreHelper
import com.lemezt.app.model.FormatType
import com.lemezt.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val percent: Int, val status: String, val speedText: String) : DownloadState()
    data class Completed(val result: DownloadResult) : DownloadState()
    data class Failed(val error: String) : DownloadState()
}

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TITLE = "EXTRA_TITLE"

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        var isOverlayVisible: Boolean = false
        var pendingTaskFactory: ((onProgress: (Int, String, String) -> Unit) -> DownloadTask)? = null

        fun startDownload(
            context: Context,
            videoTitle: String,
            taskFactory: (onProgress: (Int, String, String) -> Unit) -> DownloadTask
        ) {
            pendingTaskFactory = taskFactory
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, videoTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Media Download"

        if (action == ACTION_START) {
            val notification = NotificationHelper.buildProgressNotification(
                context = this,
                title = title,
                progress = 0,
                speedText = "",
                statusText = "Starting download...",
                indeterminate = true
            ).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }

            val factory = pendingTaskFactory
            if (factory != null) {
                runDownload(factory, title)
            }
        } else if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun runDownload(
        factory: (onProgress: (Int, String, String) -> Unit) -> DownloadTask,
        title: String
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            val notificationManager = getSystemService(NotificationManager::class.java)
            var lastNotifTime = 0L

            _downloadState.value = DownloadState.Downloading(0, "Connecting...", "")

            val task = factory { percent, status, speedText ->
                _downloadState.value = DownloadState.Downloading(percent, status, speedText)

                val now = System.currentTimeMillis()
                if (now - lastNotifTime >= 350 || percent == 100) {
                    lastNotifTime = now
                    val notif = NotificationHelper.buildProgressNotification(
                        context = this@DownloadService,
                        title = title,
                        progress = percent,
                        speedText = speedText,
                        statusText = status,
                        indeterminate = false
                    ).build()
                    notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notif)
                }
            }

            val result = task.execute()

            result.onSuccess { downloadResult ->
                _downloadState.value = DownloadState.Completed(downloadResult)

                // If user hid the overlay (isOverlayVisible is false), automatically save to public storage and notify
                if (!isOverlayVisible) {
                    val mimeType = if (downloadResult.formatType == FormatType.AUDIO) "audio/mpeg" else "video/mp4"
                    val savedUri = MediaStoreHelper.saveFileToDownloads(
                        context = this@DownloadService,
                        sourceFile = downloadResult.mediaFile,
                        desiredFileName = downloadResult.defaultTitle,
                        mimeType = mimeType
                    )

                    // Also save thumbnail if available
                    if (downloadResult.thumbnailFile != null && downloadResult.thumbnailFile.exists()) {
                        MediaStoreHelper.saveFileToDownloads(
                            context = this@DownloadService,
                            sourceFile = downloadResult.thumbnailFile,
                            desiredFileName = "${downloadResult.defaultTitle}_thumb",
                            mimeType = "image/jpeg"
                        )
                    }

                    // Save captions if available
                    if (downloadResult.captionFile != null && downloadResult.captionFile.exists()) {
                        MediaStoreHelper.saveFileToDownloads(
                            context = this@DownloadService,
                            sourceFile = downloadResult.captionFile,
                            desiredFileName = "${downloadResult.defaultTitle}_sub",
                            mimeType = "text/plain"
                        )
                    }

                    NotificationHelper.showCompleteNotification(
                        context = this@DownloadService,
                        title = downloadResult.defaultTitle,
                        savedUri = savedUri,
                        mimeType = mimeType
                    )

                    // Immediately delete temp files to eliminate internal storage leaks
                    try { downloadResult.mediaFile.delete() } catch (_: Exception) {}
                    try { downloadResult.thumbnailFile?.delete() } catch (_: Exception) {}
                    try { downloadResult.captionFile?.delete() } catch (_: Exception) {}
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()

            }.onFailure { err ->
                _downloadState.value = DownloadState.Failed(err.message ?: "Download failed")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
