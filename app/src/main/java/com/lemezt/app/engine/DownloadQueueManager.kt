package com.lemezt.app.engine

import android.content.Context
import android.util.Log
import com.lemezt.app.model.FormatItem
import com.lemezt.app.model.FormatType
import com.lemezt.app.util.NetworkMonitor
import com.lemezt.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

enum class QueueStatus {
    QUEUED,
    FETCHING_INFO,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val videoId: String,
    val desiredType: FormatType,
    var title: String = "YouTube Video",
    var artist: String = "lemezt",
    var thumbnailUrl: String? = null,
    var resolution: String = "Auto",
    var progressPercent: Int = 0,
    var speedText: String = "",
    var status: QueueStatus = QueueStatus.QUEUED,
    var statusMessage: String = "Queued",
    var errorMessage: String? = null,
    var downloadedFile: File? = null
)

object DownloadQueueManager {

    private const val TAG = "DownloadQueueManager"

    // Strictly 2 concurrent downloads at a time to prevent throttling and device overheating
    private val downloadSemaphore = Semaphore(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueItems: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()

    private var networkObserverStarted = false

    fun init(context: Context) {
        cleanOrphanedCache(context)
        if (!networkObserverStarted) {
            networkObserverStarted = true
            NetworkMonitor.init(context)
            scope.launch {
                NetworkMonitor.isConnected.collect { online ->
                    Log.d(TAG, "Network status changed: online=$online")
                    if (online) {
                        val hasQueued = _queueItems.value.any { it.status == QueueStatus.QUEUED || it.status == QueueStatus.PAUSED }
                        if (hasQueued) {
                            processQueue(context)
                        }
                    }
                }
            }
        }
    }

    fun addToQueue(context: Context, videoId: String, desiredType: FormatType): QueueItem {
        val item = QueueItem(
            videoId = videoId,
            desiredType = desiredType,
            title = "Loading $videoId..."
        )
        val list = _queueItems.value.toMutableList()
        list.add(0, item)
        _queueItems.value = list

        processQueue(context)
        return item
    }

    fun enqueue(items: List<QueueItem>, context: Context) {
        val list = _queueItems.value.toMutableList()
        list.addAll(0, items)
        _queueItems.value = list
        processQueue(context)
    }

    fun addBatchToQueue(context: Context, items: List<Pair<String, FormatType>>) {
        val newItems = items.map { (vId, type) ->
            QueueItem(
                videoId = vId,
                desiredType = type,
                title = "Video $vId"
            )
        }
        val list = _queueItems.value.toMutableList()
        list.addAll(0, newItems)
        _queueItems.value = list

        processQueue(context)
    }

    fun cancelItem(itemId: String) {
        updateItem(itemId) {
            it.status = QueueStatus.CANCELLED
            it.statusMessage = "Cancelled"
        }
    }

    fun retryItem(context: Context, itemId: String) {
        updateItem(itemId) {
            it.status = QueueStatus.QUEUED
            it.statusMessage = "Queued"
            it.progressPercent = 0
            it.errorMessage = null
        }
        processQueue(context)
    }

    fun clearCompleted() {
        val list = _queueItems.value.filter { it.status != QueueStatus.COMPLETED && it.status != QueueStatus.CANCELLED }
        _queueItems.value = list
    }

    fun processQueue(context: Context) {
        val pending = _queueItems.value.filter { it.status == QueueStatus.QUEUED || it.status == QueueStatus.PAUSED }
        for (item in pending) {
            scope.launch {
                downloadSemaphore.withPermit {
                    val currentItem = _queueItems.value.find { it.id == item.id }
                    if (currentItem == null || currentItem.status == QueueStatus.CANCELLED) {
                        return@withPermit
                    }

                    executeSingleDownload(context.applicationContext, item.id, item.videoId, item.desiredType)
                }
            }
        }
    }

    private suspend fun executeSingleDownload(
        appContext: Context,
        itemId: String,
        videoId: String,
        desiredType: FormatType
    ) {
        try {
            updateItem(itemId) {
                it.status = QueueStatus.FETCHING_INFO
                it.statusMessage = "Resolving stream..."
            }

            // Step 1: Fetch Video Details & Format
            val detailsResult = YouTubeParser.fetchVideoDetails(videoId)
            val info = detailsResult.getOrThrow()

            val selectedFormat: FormatItem
            val bestAudio: FormatItem?

            if (desiredType == FormatType.VIDEO) {
                selectedFormat = info.videoFormats.firstOrNull() ?: throw Exception("No video format found")
                // For video, use guaranteed AAC audio format for native MediaMuxer compatibility
                bestAudio = info.bestAacAudioFormat ?: info.audioFormats.firstOrNull()
            } else {
                selectedFormat = info.audioFormats.firstOrNull() ?: throw Exception("No audio format found")
                bestAudio = selectedFormat
            }

            updateItem(itemId) {
                it.title = info.title
                it.artist = info.author
                it.thumbnailUrl = info.thumbnailHdUrl
                it.resolution = selectedFormat.resolution
                it.status = QueueStatus.DOWNLOADING
                it.statusMessage = "Downloading..."
            }

            // Step 2: Download Task with auto-retry and chunking
            val task = DownloadTask(
                context = appContext,
                videoTitle = info.title,
                artist = info.author,
                selectedFormat = selectedFormat,
                bestAudioFormat = bestAudio,
                thumbnailUrl = info.thumbnailHdUrl,
                selectedCaption = info.captions.firstOrNull(),
                onProgress = { percent, statusMsg, speed ->
                    updateItem(itemId) {
                        it.progressPercent = percent
                        it.speedText = speed
                        it.statusMessage = statusMsg
                    }
                }
            )

            val downloadResult = task.execute().getOrThrow()

            // Step 3: Save to MediaStore (Samsung Music/Movies or Downloads)
            val isAudio = desiredType == FormatType.AUDIO
            val mimeType = if (isAudio) "audio/mpeg" else "video/mp4"
            val sanitized = info.title.filter { it != '/' && it != '\\' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }

            val savedUri = MediaStoreHelper.saveFileToDownloads(
                context = appContext,
                sourceFile = downloadResult.mediaFile,
                desiredFileName = sanitized,
                mimeType = mimeType
            )

            // Save thumbnail and lrc if present
            downloadResult.thumbnailFile?.let { tFile ->
                if (tFile.exists()) {
                    MediaStoreHelper.saveFileToDownloads(
                        context = appContext,
                        sourceFile = tFile,
                        desiredFileName = "${sanitized}_thumb",
                        mimeType = "image/jpeg"
                    )
                }
            }
            downloadResult.captionFile?.let { cFile ->
                if (cFile.exists()) {
                    MediaStoreHelper.saveFileToDownloads(
                        context = appContext,
                        sourceFile = cFile,
                        desiredFileName = "$sanitized.lrc",
                        mimeType = "text/plain"
                    )
                }
            }

            // CRITICAL: Delete temporary files immediately to prevent app internal storage leak!
            try { downloadResult.mediaFile.delete() } catch (_: Exception) {}
            try { downloadResult.thumbnailFile?.delete() } catch (_: Exception) {}
            try { downloadResult.captionFile?.delete() } catch (_: Exception) {}

            updateItem(itemId) {
                it.status = QueueStatus.COMPLETED
                it.progressPercent = 100
                it.statusMessage = if (isAudio) "Saved to Music/lemezt" else "Saved to Movies/lemezt"
                it.downloadedFile = null // Freed from memory
            }

            // Notification
            NotificationHelper.showCompleteNotification(
                context = appContext,
                title = info.title,
                savedUri = savedUri,
                mimeType = mimeType
            )

        } catch (e: Exception) {
            Log.e(TAG, "Queue item $itemId failed: ${e.message}", e)
            updateItem(itemId) {
                it.status = QueueStatus.FAILED
                it.statusMessage = "Failed"
                it.errorMessage = e.message ?: "Download error"
            }
        }
    }

    private fun updateItem(id: String, block: (QueueItem) -> Unit) {
        val list = _queueItems.value.map { item ->
            if (item.id == id) {
                item.copy().also(block)
            } else {
                item
            }
        }
        _queueItems.value = list
    }

    /**
     * Purges orphaned partial files older than 20 minutes from cacheDir/downloads
     */
    fun cleanOrphanedCache(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, "downloads")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                val now = System.currentTimeMillis()
                cacheDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > 20 * 60 * 1000) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun getCacheSizeMb(context: Context): Double {
        return try {
            val cacheDir = File(context.cacheDir, "downloads")
            var size = 0L
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.listFiles()?.forEach { size += it.length() }
            }
            size / (1024.0 * 1024.0)
        } catch (_: Exception) {
            0.0
        }
    }

    fun clearAllTempFiles(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, "downloads")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }
}
