package com.lemezt.app.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

class MediaStoreOutputStore(private val context: Context) {

    companion object {
        private const val TAG = "MediaStoreOutputStore"
    }

    /**
     * Publishes a completed temporary file to MediaStore with atomic IS_PENDING protection.
     * Audio -> Music/lemezt
     * Video -> Movies/lemezt
     * Subtitles -> Download/lemezt
     */
    fun publishMediaFile(
        sourceFile: File,
        desiredTitle: String,
        mimeType: String,
        extension: String
    ): Uri? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.e(TAG, "Source file missing or empty: ${sourceFile.absolutePath}")
            return null
        }

        val sanitizedTitle = desiredTitle
            .filter { it != '/' && it != '\\' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }
            .trim()
            .ifEmpty { "lemezt_media" }

        val cleanName = if (sanitizedTitle.endsWith(".$extension", ignoreCase = true)) {
            sanitizedTitle
        } else {
            "$sanitizedTitle.$extension"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isAudio = mimeType.startsWith("audio/")
            val isVideo = mimeType.startsWith("video/")

            val (collectionUri, relativePath) = when {
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

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            var itemUri: Uri? = null
            try {
                itemUri = context.contentResolver.insert(collectionUri, values)
            } catch (e: Exception) {
                Log.w(TAG, "Failed inserting to $relativePath, falling back to Downloads: ${e.message}")
                try {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/lemezt")
                    itemUri = context.contentResolver.insert(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values
                    )
                } catch (ex: Exception) {
                    Log.e(TAG, "Fallback insert also failed: ${ex.message}")
                    return null
                }
            }

            if (itemUri == null) return null

            try {
                context.contentResolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
                Log.d(TAG, "Successfully published to MediaStore: $itemUri")
                return itemUri
            } catch (e: Exception) {
                Log.e(TAG, "Stream write to MediaStore failed: ${e.message}", e)
                try { context.contentResolver.delete(itemUri, null, null) } catch (_: Exception) {}
                return null
            }
        } else {
            // Legacy Storage API 26-28
            return try {
                val targetDir = when {
                    mimeType.startsWith("audio/") -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "lemezt")
                    mimeType.startsWith("video/") -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "lemezt")
                    else -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "lemezt")
                }.apply { if (!exists()) mkdirs() }

                val targetFile = File(targetDir, cleanName)
                sourceFile.copyTo(targetFile, overwrite = true)
                Uri.fromFile(targetFile)
            } catch (e: Exception) {
                Log.e(TAG, "Legacy file save failed", e)
                null
            }
        }
    }
}
