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
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long?
)
