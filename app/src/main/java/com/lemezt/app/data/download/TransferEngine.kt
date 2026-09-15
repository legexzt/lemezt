package com.lemezt.app.data.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * High-performance, fault-tolerant media transfer engine.
 *
 * Implements:
 * 1. Parallel Multi-Segment HTTP Range Chunking (IDM / Aria2 turbo algorithm).
 *    Bypasses YouTube single-stream CDN bitrate throttling by opening concurrent
 *    Range streams simultaneously, maximizing device bandwidth.
 * 2. Zero-Copy FileChannel Pre-allocation: Pre-allocates target size on flash storage
 *    and writes segments concurrently without copying or post-assembly delay.
 * 3. Dynamic Exponential Backoff with Jitter per segment.
 * 4. HTTP/2 Connection Multiplexing with modern browser User-Agent headers.
 * 5. Robust Fallback: Automatically falls back to single-stream resume if server
 *    does not support segmented ranges.
 */
class TransferEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build()
) {

    companion object {
        private const val TAG = "TransferEngine"
        private const val MIN_SEGMENT_SIZE = 2L * 1024 * 1024 // 2 MB minimum to enable chunking
        private const val BUFFER_SIZE = 128 * 1024 // 128 KB high-throughput buffer
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    private data class StreamMeta(
        val totalBytes: Long?,
        val supportsRanges: Boolean
    )

    private data class DownloadSegment(
        val index: Int,
        val startByte: Long,
        val endByte: Long
    )

    /**
     * Downloads remote media to targetFile at maximum possible network throughput.
     */
    suspend fun downloadWithResume(
        url: String,
        targetFile: File,
        expectedLength: Long? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?, speedBytesPerSec: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {

        // Step 1: Probe stream capabilities (Content-Length and Range support)
        val meta = probeStream(url, expectedLength)
        val totalBytes = meta.totalBytes

        // Check if already completed
        if (totalBytes != null && totalBytes > 0 && targetFile.exists() && targetFile.length() >= totalBytes) {
            onProgress(totalBytes, totalBytes, 0L)
            return@withContext true
        }

        // Determine if Turbo Multi-Segment download is viable
        val canUseTurbo = meta.supportsRanges && totalBytes != null && totalBytes >= MIN_SEGMENT_SIZE

        if (canUseTurbo && totalBytes != null) {
            try {
                Log.d(TAG, "Initiating Turbo Parallel Range download for ${totalBytes / (1024 * 1024)} MB stream")
                val success = downloadParallelTurbo(url, targetFile, totalBytes, onProgress)
                if (success) return@withContext true
                Log.w(TAG, "Turbo download failed or interrupted; falling back to sequential stream")
            } catch (e: Exception) {
                Log.w(TAG, "Turbo download exception: ${e.message}; falling back", e)
            }
        }

        // Fallback: Robust single-stream download with resume
        return@withContext downloadSingleStream(url, targetFile, totalBytes, onProgress)
    }

    /**
     * Performs a lightweight probe to inspect Range support and total stream length.
     */
    private fun probeStream(url: String, fallbackLength: Long?): StreamMeta {
        if (fallbackLength != null && fallbackLength > 0) {
            return StreamMeta(totalBytes = fallbackLength, supportsRanges = true)
        }

        try {
            val probeReq = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Range", "bytes=0-0")
                .addHeader("Connection", "keep-alive")
                .build()

            client.newCall(probeReq).execute().use { response ->
                val code = response.code
                if (code == 206) {
                    val cr = response.header("Content-Range")
                    val total = cr?.substringAfter("/")?.trim()?.toLongOrNull()
                    return StreamMeta(totalBytes = total, supportsRanges = true)
                } else if (code == 200) {
                    val len = response.body?.contentLength()
                    val acceptRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                    return StreamMeta(totalBytes = if (len != null && len > 0) len else null, supportsRanges = acceptRanges)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Probe error: ${e.message}")
        }

        return StreamMeta(totalBytes = fallbackLength, supportsRanges = false)
    }

    /**
     * Executes parallel segmented range download with zero-copy pre-allocated FileChannel.
     */
    private suspend fun downloadParallelTurbo(
        url: String,
        targetFile: File,
        totalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long?, speedBytesPerSec: Long) -> Unit
    ): Boolean = coroutineScope {

        // Determine optimal number of parallel workers based on file size
        val numWorkers = when {
            totalBytes >= 30L * 1024 * 1024 -> 6 // 6 workers for large videos (>30MB)
            totalBytes >= 10L * 1024 * 1024 -> 4 // 4 workers for medium videos
            else -> 3                            // 3 workers for audio/small videos
        }

        val segmentSize = (totalBytes + numWorkers - 1) / numWorkers
        val segments = mutableListOf<DownloadSegment>()
        for (i in 0 until numWorkers) {
            val start = i * segmentSize
            val end = min(start + segmentSize - 1, totalBytes - 1)
            if (start <= end) {
                segments.add(DownloadSegment(i, start, end))
            }
        }

        // Ensure target directory exists and pre-allocate file length
        targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        RandomAccessFile(targetFile, "rw").use { raf ->
            if (raf.length() < totalBytes) {
                raf.setLength(totalBytes)
            }
        }

        val fileChannel = FileChannel.open(
            targetFile.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        )

        val totalDownloaded = AtomicLong(0L)
        val hasFatalError = AtomicBoolean(false)

        var lastSpeedCalcTime = System.currentTimeMillis()
        var bytesAtLastCalc = 0L
        val speedMutex = Mutex()

        val tasks = segments.map { segment ->
            async(Dispatchers.IO) {
                if (hasFatalError.get()) return@async false

                var workerDownloaded = 0L
                val segmentLength = segment.endByte - segment.startByte + 1
                var attempt = 0
                val maxRetries = 5

                while (workerDownloaded < segmentLength && attempt < maxRetries && !hasFatalError.get()) {
                    try {
                        val currentRangeStart = segment.startByte + workerDownloaded
                        val req = Request.Builder()
                            .url(url)
                            .addHeader("User-Agent", USER_AGENT)
                            .addHeader("Range", "bytes=$currentRangeStart-${segment.endByte}")
                            .addHeader("Connection", "keep-alive")
                            .build()

                        val response = client.newCall(req).execute()
                        val code = response.code
                        if (code != 206 && code != 200) {
                            response.close()
                            throw Exception("HTTP status $code on segment ${segment.index}")
                        }

                        val body = response.body ?: throw Exception("Empty body on segment ${segment.index}")
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int

                        body.byteStream().use { input ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (hasFatalError.get()) return@use

                                val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                                fileChannel.write(byteBuffer, segment.startByte + workerDownloaded)
                                workerDownloaded += bytesRead
                                val total = totalDownloaded.addAndGet(bytesRead.toLong())

                                // Speed calculation & progress throttling (every 300ms)
                                speedMutex.withLock {
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastSpeedCalcTime
                                    if (elapsed >= 300) {
                                        val delta = total - bytesAtLastCalc
                                        val speed = (delta * 1000) / max(elapsed, 1L)
                                        onProgress(total, totalBytes, speed)
                                        lastSpeedCalcTime = now
                                        bytesAtLastCalc = total
                                    }
                                }
                            }
                        }

                    } catch (e: Exception) {
                        attempt++
                        Log.w(TAG, "Segment ${segment.index} failure (attempt $attempt/$maxRetries): ${e.message}")
                        if (attempt >= maxRetries) {
                            hasFatalError.set(true)
                            return@async false
                        }
                        val delayMs = 150L * (1L shl min(attempt, 4)) + Random.nextLong(50, 200)
                        delay(delayMs)
                    }
                }

                workerDownloaded >= segmentLength
            }
        }

        val results = tasks.awaitAll()
        fileChannel.force(true)
        fileChannel.close()

        val allSuccess = results.all { it }
        if (allSuccess) {
            onProgress(totalBytes, totalBytes, 0L)
            Log.d(TAG, "Turbo download complete: ${targetFile.length()} bytes written successfully")
            return@coroutineScope true
        } else {
            return@coroutineScope false
        }
    }

    /**
     * Fallback single-stream download with Range resumption and exponential backoff.
     */
    private suspend fun downloadSingleStream(
        url: String,
        targetFile: File,
        expectedLength: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?, speedBytesPerSec: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var downloadedBytes = if (targetFile.exists()) targetFile.length() else 0L
        var totalBytes = expectedLength
        var attempt = 0
        var isSuccess = false
        val maxRetries = 4

        while (attempt < maxRetries && !isSuccess) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Connection", "keep-alive")

                if (downloadedBytes > 0) {
                    requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val code = response.code

                if (code == 416) {
                    response.close()
                    isSuccess = true
                    break
                }

                if (code != 200 && code != 206) {
                    response.close()
                    throw Exception("Unexpected HTTP response: $code")
                }

                val body = response.body ?: throw Exception("Empty response body")
                if (totalBytes == null || totalBytes <= 0) {
                    val bodyLength = body.contentLength()
                    if (bodyLength > 0) {
                        totalBytes = if (code == 206) downloadedBytes + bodyLength else bodyLength
                    }
                }

                val append = (code == 206)
                val outputStream = FileOutputStream(targetFile, append)
                val buffer = ByteArray(BUFFER_SIZE)

                var bytesRead: Int
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastCalc = 0L

                outputStream.use { out ->
                    body.byteStream().use { input ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            bytesSinceLastCalc += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastSpeedCalcTime
                            if (elapsed >= 350) {
                                val speed = (bytesSinceLastCalc * 1000) / max(elapsed, 1L)
                                onProgress(downloadedBytes, totalBytes, speed)
                                lastSpeedCalcTime = now
                                bytesSinceLastCalc = 0L
                            }
                        }
                        out.flush()
                    }
                }

                onProgress(downloadedBytes, totalBytes, 0L)
                isSuccess = true

            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "Single-stream attempt $attempt failed: ${e.message}")
                if (attempt >= maxRetries) {
                    return@withContext false
                }
                val delayMs = min(1200L * (1L shl (attempt - 1)) + Random.nextLong(0, 400), 15000L)
                delay(delayMs)
                if (targetFile.exists()) {
                    downloadedBytes = targetFile.length()
                }
            }
        }

        return@withContext isSuccess
    }
}
