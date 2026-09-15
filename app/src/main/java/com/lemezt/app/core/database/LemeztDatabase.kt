package com.lemezt.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lemezt.app.core.database.dao.ArtifactDao
import com.lemezt.app.core.database.dao.BatchDraftDao
import com.lemezt.app.core.database.dao.DownloadDao
import com.lemezt.app.core.database.dao.TransferPartDao
import com.lemezt.app.core.database.entity.ArtifactEntity
import com.lemezt.app.core.database.entity.BatchDraftEntity
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.core.database.entity.TransferPartEntity

@Database(
    entities = [
        DownloadEntity::class,
        TransferPartEntity::class,
        ArtifactEntity::class,
        BatchDraftEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LemeztDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao
    abstract fun transferPartDao(): TransferPartDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun batchDraftDao(): BatchDraftDao

    companion object {
        private const val DATABASE_NAME = "lemezt_db"

        @Volatile
        private var INSTANCE: LemeztDatabase? = null

        fun getInstance(context: Context): LemeztDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LemeztDatabase::class.java,
                    DATABASE_NAME
                )
                .fallbackToDestructiveMigration() // Default initial development policy
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
