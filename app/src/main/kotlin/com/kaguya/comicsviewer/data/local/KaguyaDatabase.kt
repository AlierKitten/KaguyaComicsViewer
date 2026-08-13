package com.kaguya.comicsviewer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kaguya.comicsviewer.data.local.dao.ComicDao
import com.kaguya.comicsviewer.data.local.dao.ComicSourceDao
import com.kaguya.comicsviewer.data.local.entity.ComicCacheEntity
import com.kaguya.comicsviewer.data.local.entity.ComicEntity
import com.kaguya.comicsviewer.data.local.entity.ComicSourceEntity
import com.kaguya.comicsviewer.data.local.entity.ReadingProgressEntity

@Database(
    entities = [
        ComicSourceEntity::class,
        ComicEntity::class,
        ComicCacheEntity::class,
        ReadingProgressEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class KaguyaDatabase : RoomDatabase() {
    abstract fun sourceDao(): ComicSourceDao
    abstract fun comicDao(): ComicDao
}
