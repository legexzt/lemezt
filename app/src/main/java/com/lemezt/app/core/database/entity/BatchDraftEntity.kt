package com.lemezt.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "batch_drafts")
data class BatchDraftEntity(
    @PrimaryKey
    val id: String,
    val sourceUrl: String,
    val mediaId: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String? = null,
    val formatType: String = "AUDIO", // "AUDIO" or "VIDEO"
    val quality: String = "Best",
    val isSelected: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
