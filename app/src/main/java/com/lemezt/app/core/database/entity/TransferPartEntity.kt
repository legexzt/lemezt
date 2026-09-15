package com.lemezt.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transfer_parts",
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
data class TransferPartEntity(
    @PrimaryKey
    val id: String,
    val jobId: String,
    val partIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val relativeTempPath: String,
    val isCompleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
