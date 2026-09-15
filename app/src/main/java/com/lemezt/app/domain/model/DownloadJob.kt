package com.lemezt.app.domain.model

data class DownloadJob(
    val id: String,
    val mediaId: String,
    val sourceUrl: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val formatType: OutputMediaType,
    val resolution: String,
    val mimeType: String,
    val container: String,
    val status: DownloadStatus,
    val pauseReason: String? = null,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSec: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val publicUriString: String? = null,
    val errorMessage: String? = null
)
