package com.lemezt.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lemezt.app.core.database.entity.ArtifactEntity

@Dao
interface ArtifactDao {

    @Query("SELECT * FROM artifacts WHERE jobId = :jobId")
    suspend fun getArtifactsForJob(jobId: String): List<ArtifactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artifact: ArtifactEntity)

    @Update
    suspend fun update(artifact: ArtifactEntity)

    @Query("DELETE FROM artifacts WHERE jobId = :jobId")
    suspend fun deleteArtifactsForJob(jobId: String)
}
