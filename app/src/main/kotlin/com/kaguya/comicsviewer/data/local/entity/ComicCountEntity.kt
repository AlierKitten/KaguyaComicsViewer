package com.kaguya.comicsviewer.data.local.entity

import androidx.room.ColumnInfo

/** 用于 COUNT 查询结果映射。 */
data class ComicCountEntity(
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "COUNT(*)") val count: Int
)
