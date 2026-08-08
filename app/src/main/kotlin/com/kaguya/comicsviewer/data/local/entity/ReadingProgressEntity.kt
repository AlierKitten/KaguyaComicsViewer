package com.kaguya.comicsviewer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_progress",
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
data class ReadingProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "comic_id") val comicId: Long,
    @ColumnInfo(name = "page") val page: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "is_finished") val isFinished: Boolean = false
)
