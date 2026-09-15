package com.lemezt.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lemezt.app.core.database.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY queueOrder ASC, createdAt DESC")
    fun getAllDownloadsFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY queueOrder ASC")
    fun getActiveDownloadsFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY completedAt DESC, updatedAt DESC")
    fun getCompletedDownloadsFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getDownloadFlowById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY queueOrder ASC LIMIT :limit")
    suspend fun getNextQueuedDownloads(limit: Int): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('DOWNLOADING', 'PROCESSING', 'SAVING')")
    suspend fun getRunningCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<DownloadEntity>)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, progressPercent = :percent, speedBytesPerSec = :speed, updatedAt = :time WHERE id = :id")
    suspend fun updateProgress(id: String, status: String, percent: Int, speed: Long, time: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = :status, publicUriString = :publicUri, completedAt = :time, updatedAt = :time WHERE id = :id")
    suspend fun markCompleted(id: String, status: String = "COMPLETED", publicUri: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'FAILED', errorMessage = :error, updatedAt = :time WHERE id = :id")
    suspend fun markFailed(id: String, error: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'PAUSED_BY_USER', pauseReason = 'Paused by user', updatedAt = :time WHERE id = :id")
    suspend fun markPausedByUser(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'WAITING_FOR_NETWORK', pauseReason = 'Waiting for network', updatedAt = :time WHERE id = :id")
    suspend fun markWaitingForNetwork(id: String, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETED', 'CANCELLED')")
    suspend fun clearCompletedHistory()
}
