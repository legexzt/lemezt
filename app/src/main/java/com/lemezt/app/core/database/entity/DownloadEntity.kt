package com.lemezt.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["queueOrder"])
    ]
)
data class DownloadEntity(
    @PrimaryKey
    val id: String,
    val mediaId: String,
    val sourceUrl: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val formatType: String, // "VIDEO" or "AUDIO"
    val resolution: String, // "1080p", "720p", "128 kbps", etc.
    val mimeType: String,
    val container: String,  // "mp4", "m4a", etc.
    val status: String,     // QUEUED, RESOLVING, DOWNLOADING, PROCESSING, SAVING, COMPLETED, PAUSED_BY_USER, WAITING_FOR_NETWORK, FAILED, CANCELLED
    val pauseReason: String? = null,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSec: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val publicUriString: String? = null,
    val relativeTempPath: String? = null,
    val errorMessage: String? = null,
    val queueOrder: Long = 0L
)
