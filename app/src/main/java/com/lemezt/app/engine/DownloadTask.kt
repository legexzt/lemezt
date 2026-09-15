package com.lemezt.app.engine

import android.content.Context
import android.util.Log
import com.lemezt.app.model.CaptionTrack
import com.lemezt.app.model.FormatItem
import com.lemezt.app.model.FormatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

data class DownloadResult(
    val mediaFile: File,
    val thumbnailFile: File?,
    val captionFile: File?,
    val formatType: FormatType,
    val defaultTitle: String,
    val artist: String = "lemezt"
)

class DownloadTask(
    private val context: Context,
    private val videoTitle: String,
    private val artist: String = "lemezt",
    private val selectedFormat: FormatItem,
    private val bestAudioFormat: FormatItem?,
    private val thumbnailUrl: String?,
    private val selectedCaption: CaptionTrack?,
    private val onProgress: (percent: Int, status: String, speedText: String) -> Unit
) {

    companion object {
        private const val TAG = "DownloadTask"
    }

    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun execute(): Result<DownloadResult> = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "downloads").apply { if (!exists()) mkdirs() }
        val sanitizedTitle = videoTitle.filter { it != '/' && it != '\\' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }

        var rawVideoFile: File? = null
        var rawAudioFile: File? = null
        var muxedFile: File? = null
        var finalMediaFile: File? = null
        var thumbFile: File? = null
        var captionFile: File? = null

        try {
            if (selectedFormat.type == FormatType.VIDEO) {
                val videoUrl = selectedFormat.directUrl ?: throw Exception("Video stream URL not found")

                // Case 1: Progressive video (already contains audio and video combined by YouTube)
                if (selectedFormat.hasAudio) {
                    onProgress(5, "Downloading high speed video with audio...", "")
                    val directVideoFile = File(cacheDir, "vid_${System.currentTimeMillis()}.mp4")
                    rawVideoFile = directVideoFile

                    downloadUrlAccelerated(videoUrl, directVideoFile, numWorkers = 4) { p, speed ->
                        val scaled = (5 + p * 0.85).toInt().coerceIn(5, 90)
                        onProgress(scaled, "Downloading Video ($p%)", speed)
                    }
                    finalMediaFile = directVideoFile

                } else {
                    // Case 2: Adaptive DASH video (separate video & AAC audio streams)
                    val audioUrl = bestAudioFormat?.directUrl

                    val vFile = File(cacheDir, "v_${System.currentTimeMillis()}.mp4")
                    val aFile = File(cacheDir, "a_${System.currentTimeMillis()}.m4a")
                    val mFile = File(cacheDir, "final_${System.currentTimeMillis()}.mp4")

                    rawVideoFile = vFile
                    rawAudioFile = aFile
                    muxedFile = mFile

                    onProgress(5, "Downloading video & studio audio streams...", "")

                    coroutineScope {
                        val videoJob = async {
                            downloadUrlAccelerated(videoUrl, vFile, numWorkers = 4) { p, speed ->
                                val scaled = (5 + p * 0.70).toInt().coerceIn(5, 75)
                                onProgress(scaled, "Downloading Video ($p%)", speed)
                            }
                        }

                        val audioJob = async {
                            if (!audioUrl.isNullOrEmpty()) {
                                downloadUrlAccelerated(audioUrl, aFile, numWorkers = 2) { _, _ -> }
                            }
                        }

                        videoJob.await()
                        audioJob.await()
                    }

                    // Mux video and audio into a single MP4 file with sound
                    if (aFile.exists() && aFile.length() > 0L) {
                        onProgress(78, "Merging studio audio with video...", "")
                        val muxSuccess = MediaMuxerHelper.muxVideoAndAudio(vFile, aFile, mFile)
                        if (muxSuccess && mFile.exists() && mFile.length() > 0L) {
                            finalMediaFile = mFile
                            // Clean up intermediate raw streams immediately to prevent storage leak
                            vFile.delete()
                            aFile.delete()
                        } else {
                            Log.w(TAG, "Muxing was unsuccessful, keeping video stream")
                            finalMediaFile = vFile
                            aFile.delete()
                        }
                    } else {
                        finalMediaFile = vFile
                    }
                }

            } else {
                // High-Speed Multi-Threaded Audio / MP3 Download
                val audioUrl = selectedFormat.directUrl 
                    ?: bestAudioFormat?.directUrl 
                    ?: throw Exception("Audio stream URL not found")

                val mp3File = File(cacheDir, "audio_${System.currentTimeMillis()}.mp3")
                rawAudioFile = mp3File

                onProgress(5, "Downloading high quality MP3...", "")

                downloadUrlAccelerated(audioUrl, mp3File, numWorkers = 4) { p, speed ->
                    val scaled = (5 + p * 0.85).toInt().coerceIn(5, 90)
                    onProgress(scaled, "Downloading MP3 ($p%)", speed)
                }

                finalMediaFile = mp3File
            }

            // Thumbnail download if requested
            if (!thumbnailUrl.isNullOrEmpty()) {
                onProgress(92, "Downloading HD thumbnail...", "")
                val tFile = File(cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
                try {
                    downloadUrlDirect(thumbnailUrl, tFile)
                    thumbFile = tFile
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail download failed: ${e.message}")
                }
            }

            // Captions download if requested
            if (selectedCaption != null && selectedCaption.baseUrl.isNotEmpty()) {
                onProgress(96, "Downloading captions...", "")
                val cFile = File(cacheDir, "captions_${selectedCaption.languageCode}.srt")
                try {
                    downloadUrlDirect(selectedCaption.baseUrl, cFile)
                    captionFile = cFile
                } catch (e: Exception) {
                    Log.w(TAG, "Caption download failed: ${e.message}")
                }
            }

            // Embed metadata & cover art for audio
            if (selectedFormat.type == FormatType.AUDIO && finalMediaFile != null) {
                onProgress(98, "Embedding cover art & tags...", "")
                try {
                    val lyricsText = captionFile?.let { AudioTagHelper.extractLyricsForEmbedding(it) }
                    AudioTagHelper.tagAudioFile(
                        audioFile = finalMediaFile,
                        title = videoTitle,
                        artist = artist,
                        album = "lemezt",
                        lyrics = lyricsText,
                        coverFile = thumbFile
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Audio tagging failed: ${e.message}")
                }
            }

            onProgress(100, "Processing complete!", "")

            Result.success(
                DownloadResult(
                    mediaFile = finalMediaFile ?: throw Exception("Media file creation failed"),
                    thumbnailFile = thumbFile,
                    captionFile = captionFile,
                    formatType = selectedFormat.type,
                    defaultTitle = sanitizedTitle,
                    artist = artist
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "DownloadTask execution failed", e)
            // Clean up any temporary files on failure
            try { rawVideoFile?.delete() } catch (_: Exception) {}
            try { rawAudioFile?.delete() } catch (_: Exception) {}
            try { muxedFile?.delete() } catch (_: Exception) {}
            try { thumbFile?.delete() } catch (_: Exception) {}
            try { captionFile?.delete() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    private suspend fun downloadUrlAccelerated(
        url: String,
        targetFile: File,
        numWorkers: Int = 4,
        progressCallback: (percent: Int, speedText: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        // Step 1: Probe content length and Range support using bytes=0-0
        val probeRequest = Request.Builder()
            .url(url)
            .addHeader("Range", "bytes=0-0")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        var totalBytes = -1L
        var supportsRange = false

        try {
            client.newCall(probeRequest).execute().use { response ->
                if (response.code == 206) {
                    val contentRange = response.header("Content-Range")
                    if (contentRange != null) {
                        val matcher = Pattern.compile("/(\\d+)").matcher(contentRange)
                        if (matcher.find()) {
                            totalBytes = matcher.group(1)?.toLongOrNull() ?: -1L
                            supportsRange = true
                        }
                    }
                } else if (response.isSuccessful) {
                    totalBytes = response.body?.contentLength() ?: -1L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Probe request failed: ${e.message}")
        }

        // Fallback to direct stream if range not supported or small file
        if (!supportsRange || totalBytes < 1572864L) {
            downloadUrlDirectWithRetry(url, targetFile, progressCallback)
            return@withContext
        }

        // Step 2: Multi-threaded parallel chunk download with retry & resume
        val workers = numWorkers.coerceIn(2, 6)
        val chunkSize = totalBytes / workers
        val downloadedBytes = AtomicLong(0)

        // Pre-allocate file
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val startTime = System.currentTimeMillis()
        var lastSpeedTime = startTime
        var lastSpeedBytes = 0L
        var currentSpeedText = ""

        coroutineScope {
            val jobs = (0 until workers).map { index ->
                val baseStart = index * chunkSize
                val end = if (index == workers - 1) totalBytes - 1 else (index + 1) * chunkSize - 1

                async(Dispatchers.IO) {
                    downloadChunkWithRetry(
                        url = url,
                        targetFile = targetFile,
                        chunkStart = baseStart,
                        chunkEnd = end,
                        downloadedCounter = downloadedBytes,
                        onBytesReceived = {
                            val currentTotal = downloadedBytes.get()
                            val now = System.currentTimeMillis()
                            if (now - lastSpeedTime >= 250) {
                                val bytesDiff = currentTotal - lastSpeedBytes
                                val timeDiffSec = (now - lastSpeedTime) / 1000.0
                                val speedBytesPerSec = if (timeDiffSec > 0) bytesDiff / timeDiffSec else 0.0
                                lastSpeedBytes = currentTotal
                                lastSpeedTime = now

                                currentSpeedText = formatSpeed(speedBytesPerSec)
                                val percent = ((currentTotal * 100) / totalBytes).toInt().coerceIn(0, 100)
                                progressCallback(percent, currentSpeedText)
                            }
                        }
                    )
                }
            }
            jobs.awaitAll()
        }

        progressCallback(100, currentSpeedText)
    }

    private suspend fun downloadChunkWithRetry(
        url: String,
        targetFile: File,
        chunkStart: Long,
        chunkEnd: Long,
        downloadedCounter: AtomicLong,
        onBytesReceived: () -> Unit
    ) {
        val maxRetries = 4
        var attempt = 0
        var bytesDownloadedInChunk = 0L
        val totalNeeded = chunkEnd - chunkStart + 1

        while (bytesDownloadedInChunk < totalNeeded) {
            attempt++
            val currentStart = chunkStart + bytesDownloadedInChunk
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$currentStart-$chunkEnd")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        throw IOException("HTTP ${response.code} on chunk $currentStart-$chunkEnd")
                    }

                    val body = response.body ?: throw IOException("Empty body for chunk")
                    val buffer = ByteArray(131072) // 128 KB buffer

                    RandomAccessFile(targetFile, "rw").use { raf ->
                        raf.seek(currentStart)
                        body.byteStream().use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                raf.write(buffer, 0, read)
                                bytesDownloadedInChunk += read
                                downloadedCounter.addAndGet(read.toLong())
                                onBytesReceived()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chunk attempt $attempt failed ($bytesDownloadedInChunk/$totalNeeded bytes): ${e.message}")
                if (attempt >= maxRetries) {
                    throw e
                }
                // Exponential backoff before retrying
                delay((1000L * attempt).coerceAtMost(4000L))
            }
        }
    }

    private suspend fun downloadUrlDirectWithRetry(
        url: String,
        targetFile: File,
        progressCallback: ((Int, String) -> Unit)? = null
    ) {
        val maxRetries = 4
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadUrlDirect(url, targetFile, progressCallback)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct download attempt $attempt failed: ${e.message}")
                if (attempt >= maxRetries) throw e
                delay((1000L * attempt).coerceAtMost(4000L))
            }
        }
    }

    private fun downloadUrlDirect(
        url: String,
        targetFile: File,
        progressCallback: ((Int, String) -> Unit)? = null
    ) {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to fetch stream: HTTP ${response.code}")

        val body = response.body ?: throw IOException("Empty stream body")
        val totalBytes = body.contentLength()

        var lastTime = System.currentTimeMillis()
        var lastBytes = 0L

        body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(262144) // 256 KB buffer
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    if (progressCallback != null && now - lastTime >= 300) {
                        val timeDiff = (now - lastTime) / 1000.0
                        val speed = if (timeDiff > 0) (totalRead - lastBytes) / timeDiff else 0.0
                        lastBytes = totalRead
                        lastTime = now

                        val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 50
                        progressCallback(percent, formatSpeed(speed))
                    }
                }
                output.flush()
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1048576.0 -> String.format("%.1f MB/s", bytesPerSec / 1048576.0)
            bytesPerSec >= 1024.0 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }
}
