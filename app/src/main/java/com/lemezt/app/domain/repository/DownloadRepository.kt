package com.lemezt.app.domain.repository

import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.domain.model.DownloadJob
import com.lemezt.app.domain.model.OutputPlan
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun getAllDownloads(): Flow<List<DownloadEntity>>
    fun getActiveDownloads(): Flow<List<DownloadEntity>>
    fun getCompletedDownloads(): Flow<List<DownloadEntity>>
    suspend fun getDownloadById(id: String): DownloadEntity?
    suspend fun enqueueDownload(
        mediaId: String,
        sourceUrl: String,
        title: String,
        author: String,
        thumbnailUrl: String?,
        plan: OutputPlan
    ): String
    suspend fun pauseDownload(id: String)
    suspend fun resumeDownload(id: String)
    suspend fun cancelDownload(id: String)
    suspend fun retryDownload(id: String)
    suspend fun deleteDownload(id: String)
    suspend fun clearCompletedHistory()
}
