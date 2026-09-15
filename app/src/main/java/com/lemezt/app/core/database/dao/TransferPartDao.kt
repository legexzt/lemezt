package com.lemezt.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lemezt.app.core.database.entity.TransferPartEntity

@Dao
interface TransferPartDao {

    @Query("SELECT * FROM transfer_parts WHERE jobId = :jobId ORDER BY partIndex ASC")
    suspend fun getPartsForJob(jobId: String): List<TransferPartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(parts: List<TransferPartEntity>)

    @Update
    suspend fun update(part: TransferPartEntity)

    @Query("UPDATE transfer_parts SET downloadedBytes = :bytes, isCompleted = :completed, updatedAt = :time WHERE id = :id")
    suspend fun updatePartProgress(id: String, bytes: Long, completed: Boolean, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM transfer_parts WHERE jobId = :jobId")
    suspend fun deletePartsForJob(jobId: String)
}
