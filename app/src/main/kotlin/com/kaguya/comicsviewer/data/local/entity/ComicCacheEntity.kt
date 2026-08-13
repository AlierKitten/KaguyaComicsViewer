package com.kaguya.comicsviewer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "comic_cache",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comic_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("comic_id", unique = true)]
)
data class ComicCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "comic_id") val comicId: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "archive_file") val archiveFile: String?,
    @ColumnInfo(name = "extracted_dir") val extractedDir: String?,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "is_external_archive") val isExternalArchive: Boolean = false
)
