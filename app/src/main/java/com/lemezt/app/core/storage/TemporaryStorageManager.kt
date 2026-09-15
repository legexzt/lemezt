package com.lemezt.app.core.storage

import android.content.Context
import java.io.File

class TemporaryStorageManager(private val context: Context) {

    private val baseJobDir: File
        get() = File(context.filesDir, "jobs").apply { if (!exists()) mkdirs() }

    fun getJobDirectory(jobId: String): File {
        return File(baseJobDir, jobId).apply { if (!exists()) mkdirs() }
    }

    fun getRawVideoFile(jobId: String): File = File(getJobDirectory(jobId), "raw_video.mp4")
    fun getRawAudioFile(jobId: String): File = File(getJobDirectory(jobId), "raw_audio.m4a")
    fun getMuxedFile(jobId: String): File = File(getJobDirectory(jobId), "muxed_media.mp4")
    fun getThumbnailFile(jobId: String): File = File(getJobDirectory(jobId), "cover.jpg")
    fun getLyricsFile(jobId: String): File = File(getJobDirectory(jobId), "synced_lyrics.lrc")
    fun getPartFile(jobId: String, partIndex: Int): File = File(getJobDirectory(jobId), "part_$partIndex.tmp")

    fun clearJobDirectory(jobId: String) {
        try {
            val dir = File(baseJobDir, jobId)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    fun getDisposableCacheSizeMb(): Double {
        var total = 0L
        try {
            val cacheDir = File(context.cacheDir, "downloads")
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
            }
        } catch (_: Exception) {}
        return total / (1024.0 * 1024.0)
    }

    fun clearDisposableCache() {
        try {
            val cacheDir = File(context.cacheDir, "downloads")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }
}
