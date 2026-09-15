package com.lemezt.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artifacts",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["jobId"])]
)
data class ArtifactEntity(
    @PrimaryKey
    val id: String,
    val jobId: String,
    val role: String, // PRIMARY_MEDIA, COVER_ART, SUBTITLES
    val mimeType: String,
    val outputUriString: String? = null,
    val fileSize: Long = 0L,
    val isPublished: Boolean = false
)
