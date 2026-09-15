package com.lemezt.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lemezt.app.core.database.entity.BatchDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDraftDao {

    @Query("SELECT * FROM batch_drafts ORDER BY createdAt DESC")
    fun getAllDraftsFlow(): Flow<List<BatchDraftEntity>>

    @Query("SELECT * FROM batch_drafts ORDER BY createdAt DESC")
    suspend fun getAllDrafts(): List<BatchDraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: BatchDraftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(drafts: List<BatchDraftEntity>)

    @Update
    suspend fun update(draft: BatchDraftEntity)

    @Query("UPDATE batch_drafts SET formatType = :formatType")
    suspend fun updateAllFormatType(formatType: String)

    @Query("DELETE FROM batch_drafts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM batch_drafts")
    suspend fun clearAll()
}
