package com.lemezt.app.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStoreHelper {

    private const val TAG = "MediaStoreHelper"

    /**
     * Saves a file into Android's public MediaStore with dedicated routing for Samsung One UI:
     * - Audio files -> MediaStore.Audio.Media (Music/lemezt) so Samsung Music indexes it immediately!
     * - Video files -> MediaStore.Video.Media (Movies/lemezt) so Samsung Gallery indexes it immediately!
     * - Lyrics / Subs / Thumbs -> MediaStore.Downloads (Downloads/lemezt)
     */
    fun saveFileToDownloads(
        context: Context,
        sourceFile: File,
        desiredFileName: String,
        mimeType: String
    ): Uri? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.e(TAG, "Cannot save missing or empty source file: ${sourceFile.absolutePath}")
            return null
        }

        val extension = when {
            desiredFileName.endsWith(".lrc", ignoreCase = true) -> ""
            desiredFileName.endsWith(".srt", ignoreCase = true) -> ""
            desiredFileName.endsWith(".mp3", ignoreCase = true) -> ""
            desiredFileName.endsWith(".mp4", ignoreCase = true) -> ""
            desiredFileName.endsWith(".jpg", ignoreCase = true) -> ""
            desiredFileName.endsWith(".jpeg", ignoreCase = true) -> ""
            mimeType.startsWith("video/") -> ".mp4"
            mimeType.startsWith("audio/") -> ".mp3"
            mimeType.startsWith("image/") -> ".jpg"
            mimeType.startsWith("text/") -> ".srt"
            else -> ""
        }

        val baseName = if (desiredFileName.endsWith(extension, ignoreCase = true)) {
            desiredFileName
        } else {
            "$desiredFileName$extension"
        }

        val cleanName = baseName.filter { it != '/' && it != '\\' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }

        var savedUri: Uri? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Samsung & Android 10+ Scoped Storage routing
            val isAudio = mimeType.startsWith("audio/")
            val isVideo = mimeType.startsWith("video/")

            val (collection, relativePath) = when {
                isAudio -> Pair(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    "${Environment.DIRECTORY_MUSIC}/lemezt"
                )
                isVideo -> Pair(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    "${Environment.DIRECTORY_MOVIES}/lemezt"
                )
                else -> Pair(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    "${Environment.DIRECTORY_DOWNLOADS}/lemezt"
                )
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(1))
            }

            var itemUri: Uri? = null
            try {
                itemUri = context.contentResolver.insert(collection, contentValues)
            } catch (e: Exception) {
                Log.w(TAG, "Primary insert failed on $relativePath, trying Downloads fallback: ${e.message}")
                // Fallback to Downloads if specific collection fails
                try {
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/lemezt")
                    val dlCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    itemUri = context.contentResolver.insert(dlCollection, contentValues)
                } catch (ex: Exception) {
                    Log.e(TAG, "Fallback insert also failed: ${ex.message}")
                }
            }

            if (itemUri != null) {
                try {
                    context.contentResolver.openOutputStream(itemUri)?.use { outputStream ->
                        FileInputStream(sourceFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(0))
                    context.contentResolver.update(itemUri, contentValues, null, null)
                    savedUri = itemUri
                    Log.d(TAG, "Successfully saved to MediaStore: $itemUri")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing stream to MediaStore URI: ${e.message}", e)
                    // Delete ghost file on failure
                    try { context.contentResolver.delete(itemUri, null, null) } catch (_: Exception) {}
                    savedUri = null
                }
            }

        } else {
            // Android 9 and below
            val targetDirName = if (mimeType.startsWith("audio/")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS
            val publicDir = File(Environment.getExternalStoragePublicDirectory(targetDirName), "lemezt").apply {
                if (!exists()) mkdirs()
            }

            val targetFile = File(publicDir, cleanName)
            try {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                savedUri = Uri.fromFile(targetFile)
            } catch (e: Exception) {
                Log.e(TAG, "Legacy file write failed: ${e.message}", e)
            }
        }

        // Trigger MediaScanner so Samsung Gallery, Samsung Music, and Xiaomi Music Player index it immediately
        try {
            val scanDir = when {
                mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            val physicalFile = File(
                Environment.getExternalStoragePublicDirectory(scanDir),
                "lemezt/$cleanName"
            )
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(physicalFile.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                Log.d(TAG, "MediaScanner indexed: $path -> $uri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner call skipped: ${e.message}")
        }

        return savedUri
    }

    /**
     * Saves synchronized lyrics (.lrc) in both Music and Downloads for maximum player compatibility.
     */
    fun saveLrcFileToDownloads(
        context: Context,
        lrcContent: String,
        baseFileName: String
    ): Uri? {
        val lrcFileName = if (baseFileName.endsWith(".lrc", ignoreCase = true)) {
            baseFileName
        } else {
            val cleanBase = baseFileName.substringBeforeLast(".")
            "$cleanBase.lrc"
        }

        val tempLrcFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.lrc")
        try {
            tempLrcFile.writeText(lrcContent, Charsets.UTF_8)
            return saveFileToDownloads(
                context = context,
                sourceFile = tempLrcFile,
                desiredFileName = lrcFileName,
                mimeType = "text/plain"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving LRC file: ${e.message}", e)
            return null
        } finally {
            try { tempLrcFile.delete() } catch (_: Exception) {}
        }
    }
}
