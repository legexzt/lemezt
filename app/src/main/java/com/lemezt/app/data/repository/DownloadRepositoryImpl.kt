package com.lemezt.app.data.repository

import android.content.Context
import com.lemezt.app.core.database.LemeztDatabase
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.core.storage.TemporaryStorageManager
import com.lemezt.app.domain.model.OutputMediaType
import com.lemezt.app.domain.model.OutputPlan
import com.lemezt.app.domain.repository.DownloadRepository
import com.lemezt.app.transfer.TransferCoordinator
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class DownloadRepositoryImpl(
    private val context: Context,
    private val database: LemeztDatabase = LemeztDatabase.getInstance(context)
) : DownloadRepository {

    private val storageManager = TemporaryStorageManager(context)

    override fun getAllDownloads(): Flow<List<DownloadEntity>> {
        return database.downloadDao().getAllDownloadsFlow()
    }

    override fun getActiveDownloads(): Flow<List<DownloadEntity>> {
        return database.downloadDao().getActiveDownloadsFlow()
    }

    override fun getCompletedDownloads(): Flow<List<DownloadEntity>> {
        return database.downloadDao().getCompletedDownloadsFlow()
    }

    override suspend fun getDownloadById(id: String): DownloadEntity? {
        return database.downloadDao().getDownloadById(id)
    }

    override suspend fun enqueueDownload(
        mediaId: String,
        sourceUrl: String,
        title: String,
        author: String,
        thumbnailUrl: String?,
        plan: OutputPlan
    ): String {
        val id = UUID.randomUUID().toString()
        val isVideo = plan.mediaType == OutputMediaType.VIDEO
        val container = if (isVideo) "mp4" else "m4a"
        val mimeType = if (isVideo) "video/mp4" else "audio/mp4"

        val entity = DownloadEntity(
            id = id,
            mediaId = mediaId,
            sourceUrl = sourceUrl,
            title = title,
            author = author,
            thumbnailUrl = thumbnailUrl,
            formatType = if (isVideo) "VIDEO" else "AUDIO",
            resolution = plan.qualityLabel,
            mimeType = mimeType,
            container = container,
            status = "QUEUED",
            queueOrder = System.currentTimeMillis()
        )

        database.downloadDao().insert(entity)
        TransferCoordinator.triggerQueue()
        return id
    }

    override suspend fun pauseDownload(id: String) {
        database.downloadDao().markPausedByUser(id)
    }

    override suspend fun resumeDownload(id: String) {
        database.downloadDao().updateProgress(id, status = "QUEUED", percent = 0, speed = 0L)
        TransferCoordinator.triggerQueue()
    }

    override suspend fun cancelDownload(id: String) {
        database.downloadDao().updateProgress(id, status = "CANCELLED", percent = 0, speed = 0L)
        storageManager.clearJobDirectory(id)
    }

    override suspend fun retryDownload(id: String) {
        database.downloadDao().updateProgress(id, status = "QUEUED", percent = 0, speed = 0L)
        TransferCoordinator.triggerQueue()
    }

    override suspend fun deleteDownload(id: String) {
        database.downloadDao().deleteById(id)
        storageManager.clearJobDirectory(id)
    }

    override suspend fun clearCompletedHistory() {
        database.downloadDao().clearCompletedHistory()
    }
}
