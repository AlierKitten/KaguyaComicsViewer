package com.kaguya.comicsviewer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "comic_sources")
data class ComicSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    @ColumnInfo(name = "host") val host: String?,
    @ColumnInfo(name = "share") val share: String?,
    @ColumnInfo(name = "path") val path: String?,
    @ColumnInfo(name = "username") val username: String?,
    @ColumnInfo(name = "password") val password: String?,
    @ColumnInfo(name = "domain") val domain: String?,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long?,
    /** 索引进度状态：idle / scanning / done / cancelled / failed */
    @ColumnInfo(name = "index_status") val indexStatus: String?,
    /** 已处理的漫画数（用于断点续传/被杀后判断） */
    @ColumnInfo(name = "index_current") val indexCurrent: Int,
    /** 本次扫描预计处理的漫画总数 */
    @ColumnInfo(name = "index_total") val indexTotal: Int
)
