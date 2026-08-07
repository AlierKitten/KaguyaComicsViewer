package com.kaguya.comicsviewer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kaguya.comicsviewer.data.local.entity.ComicSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicSourceDao {
    @Query("SELECT * FROM comic_sources ORDER BY name")
    fun observeAll(): Flow<List<ComicSourceEntity>>

    @Query("SELECT * FROM comic_sources ORDER BY name")
    suspend fun listAll(): List<ComicSourceEntity>

    @Query("SELECT * FROM comic_sources WHERE enabled = 1 ORDER BY name")
    suspend fun listEnabled(): List<ComicSourceEntity>

    @Query("SELECT * FROM comic_sources WHERE id = :id")
    suspend fun findById(id: Long): ComicSourceEntity?

    @Query("SELECT * FROM comic_sources WHERE id = :id")
    fun observeById(id: Long): Flow<ComicSourceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ComicSourceEntity): Long

    @Update
    suspend fun update(entity: ComicSourceEntity)

    @Query("UPDATE comic_sources SET last_scanned_at = :timestamp WHERE id = :id")
    suspend fun updateLastScanned(id: Long, timestamp: Long)

    @Query("DELETE FROM comic_sources WHERE id = :id")
    suspend fun deleteById(id: Long)
}
