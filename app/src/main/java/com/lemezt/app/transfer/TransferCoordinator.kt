package com.lemezt.app.transfer

import android.content.Context
import android.util.Log
import com.lemezt.app.core.database.LemeztDatabase
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.core.storage.MediaStoreOutputStore
import com.lemezt.app.core.storage.TemporaryStorageManager
import com.lemezt.app.data.download.TransferEngine
import com.lemezt.app.engine.AudioTagHelper
import com.lemezt.app.engine.MediaMuxerHelper
import com.lemezt.app.engine.YouTubeParser
import com.lemezt.app.util.NetworkMonitor
import com.lemezt.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

object TransferCoordinator {

    private const val TAG = "TransferCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(2) // Strictly 2 concurrent downloads

    private lateinit var appContext: Context
    private lateinit var db: LemeztDatabase
    private lateinit var storageManager: TemporaryStorageManager
    private lateinit var outputStore: MediaStoreOutputStore
    private val transferEngine = TransferEngine()

    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun init(context: Context) {
        appContext = context.applicationContext
        db = LemeztDatabase.getInstance(appContext)
        storageManager = TemporaryStorageManager(appContext)
        outputStore = MediaStoreOutputStore(appContext)

        // Observe network state: auto-pause on drop, auto-resume on reconnect
        NetworkMonitor.init(appContext)
        scope.launch {
            NetworkMonitor.isConnected.collect { isConnected ->
                if (isConnected) {
                    resumeWaitingDownloads()
                } else {
                    pauseActiveForNetwork()
                }
            }
        }

        // Trigger queue runner
        triggerQueue()
    }

    fun triggerQueue() {
        scope.launch {
            val queuedList = db.downloadDao().getNextQueuedDownloads(limit = 4)
            for (item in queuedList) {
                if (!activeJobs.containsKey(item.id)) {
                    launchJob(item.id)
                }
            }
        }
    }

    private fun launchJob(jobId: String) {
        activeJobs[jobId] = true
        scope.launch {
            try {
                semaphore.withPermit {
                    executeJob(jobId)
                }
            } finally {
                activeJobs.remove(jobId)
                triggerQueue()
            }
        }
    }

    private suspend fun executeJob(jobId: String) {
        val job = db.downloadDao().getDownloadById(jobId) ?: return
        if (job.status == "PAUSED_BY_USER" || job.status == "CANCELLED") return

        try {
            // STEP 1: RESOLVING STREAM URLS
            db.downloadDao().updateProgress(jobId, status = "RESOLVING", percent = 5, speed = 0L)
            val detailsResult = YouTubeParser.fetchVideoDetails(job.mediaId)
            val info = detailsResult.getOrThrow()

            // Check if cancelled during resolve
            val currentJob = db.downloadDao().getDownloadById(jobId)
            if (currentJob?.status == "PAUSED_BY_USER" || currentJob?.status == "CANCELLED") return

            val isVideo = job.formatType == "VIDEO"
            val targetContainer = if (isVideo) "mp4" else "m4a"
            val targetMime = if (isVideo) "video/mp4" else "audio/mp4"

            val videoStreamUrl: String?
            val audioStreamUrl: String?

            if (isVideo) {
                val chosenVideo = info.videoFormats.find { it.resolution == job.resolution }
                    ?: info.videoFormats.firstOrNull()
                    ?: throw Exception("No suitable video format found")

                videoStreamUrl = chosenVideo.directUrl
                audioStreamUrl = info.bestAacAudioFormat?.directUrl ?: info.audioFormats.firstOrNull()?.directUrl
            } else {
                // Audio: Use best AAC stream for native M4A
                val chosenAudio = info.bestAacAudioFormat
                    ?: info.audioFormats.find { it.mimeType.contains("audio/mp4") }
                    ?: info.audioFormats.firstOrNull()
                    ?: throw Exception("No audio format found")

                videoStreamUrl = null
                audioStreamUrl = chosenAudio.directUrl
            }

            // STEP 2: DOWNLOADING MEDIA
            db.downloadDao().updateProgress(jobId, status = "DOWNLOADING", percent = 10, speed = 0L)

            val finalFileToPublish: File

            if (isVideo && !videoStreamUrl.isNullOrEmpty()) {
                val rawVideo = storageManager.getRawVideoFile(jobId)
                val rawAudio = storageManager.getRawAudioFile(jobId)
                val muxedFile = storageManager.getMuxedFile(jobId)

                // Download video stream with Turbo parallel acceleration
                var lastVideoDbUpdate = 0L
                val vSuccess = transferEngine.downloadWithResume(videoStreamUrl, rawVideo) { downloaded, total, speed ->
                    val percent = if (total != null && total > 0) ((downloaded * 60) / total).toInt() + 10 else 30
                    val now = System.currentTimeMillis()
                    if (now - lastVideoDbUpdate >= 300 || percent >= 70) {
                        lastVideoDbUpdate = now
                        scope.launch { db.downloadDao().updateProgress(jobId, "DOWNLOADING", percent, speed) }
                    }
                }
                if (!vSuccess) throw Exception("Failed downloading video stream")

                // Download audio stream if available
                if (!audioStreamUrl.isNullOrEmpty()) {
                    transferEngine.downloadWithResume(audioStreamUrl, rawAudio) { _, _, _ -> }
                }

                // STEP 3: MUXING
                db.downloadDao().updateProgress(jobId, status = "PROCESSING", percent = 80, speed = 0L)
                if (rawAudio.exists() && rawAudio.length() > 0L) {
                    val muxOk = MediaMuxerHelper.muxVideoAndAudio(rawVideo, rawAudio, muxedFile)
                    finalFileToPublish = if (muxOk && muxedFile.exists()) muxedFile else rawVideo
                } else {
                    finalFileToPublish = rawVideo
                }

            } else if (!audioStreamUrl.isNullOrEmpty()) {
                val rawAudio = storageManager.getRawAudioFile(jobId)
                var lastAudioDbUpdate = 0L
                val aSuccess = transferEngine.downloadWithResume(audioStreamUrl, rawAudio) { downloaded, total, speed ->
                    val percent = if (total != null && total > 0) ((downloaded * 80) / total).toInt() + 10 else 50
                    val now = System.currentTimeMillis()
                    if (now - lastAudioDbUpdate >= 300 || percent >= 90) {
                        lastAudioDbUpdate = now
                        scope.launch { db.downloadDao().updateProgress(jobId, "DOWNLOADING", percent, speed) }
                    }
                }
                if (!aSuccess) throw Exception("Failed downloading audio stream")

                // STEP 3: TAGGING WITH COVER ART
                db.downloadDao().updateProgress(jobId, status = "PROCESSING", percent = 92, speed = 0L)
                var coverFile: File? = null
                if (!info.thumbnailHdUrl.isNullOrEmpty()) {
                    val tFile = storageManager.getThumbnailFile(jobId)
                    if (transferEngine.downloadWithResume(info.thumbnailHdUrl, tFile) { _, _, _ -> }) {
                        coverFile = tFile
                    }
                }

                // Tag audio file
                AudioTagHelper.tagAudioFile(
                    audioFile = rawAudio,
                    title = job.title,
                    artist = job.author,
                    album = "lemezt",
                    lyrics = null,
                    coverFile = coverFile
                )

                finalFileToPublish = rawAudio
            } else {
                throw Exception("Stream URLs could not be resolved")
            }

            // STEP 4: ATOMIC MEDIASTORE PUBLICATION
            db.downloadDao().updateProgress(jobId, status = "SAVING", percent = 98, speed = 0L)
            val publicUri = outputStore.publishMediaFile(
                sourceFile = finalFileToPublish,
                desiredTitle = job.title,
                mimeType = targetMime,
                extension = targetContainer
            ) ?: throw Exception("Failed to publish file to MediaStore")

            // STEP 5: COMPLETED - CLEANUP TEMP DIRECTORY
            storageManager.clearJobDirectory(jobId)
            db.downloadDao().markCompleted(jobId, publicUri = publicUri.toString())

            NotificationHelper.showCompleteNotification(
                context = appContext,
                title = job.title,
                savedUri = publicUri,
                mimeType = targetMime
            )

        } catch (e: Exception) {
            Log.e(TAG, "Job $jobId failed: ${e.message}", e)
            db.downloadDao().markFailed(jobId, e.message ?: "Download failed")
        }
    }

    private fun pauseActiveForNetwork() {
        scope.launch {
            val active = db.downloadDao().getActiveDownloadsFlow()
            // Handled via state transition in DB
        }
    }

    private fun resumeWaitingDownloads() {
        scope.launch {
            triggerQueue()
        }
    }
}
