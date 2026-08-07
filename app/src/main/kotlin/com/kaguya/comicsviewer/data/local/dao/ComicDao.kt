package com.kaguya.comicsviewer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaguya.comicsviewer.data.local.entity.ComicCacheEntity
import com.kaguya.comicsviewer.data.local.entity.ComicEntity
import com.kaguya.comicsviewer.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {

    @Query(
        """
        SELECT c.* FROM comics c
        LEFT JOIN comic_cache k ON k.comic_id = c.id
        LEFT JOIN reading_progress p ON p.comic_id = c.id
        WHERE c.source_id IN (:sourceIds)
        GROUP BY c.id
        ORDER BY c.title COLLATE NOCASE
        """
    )
    fun observeBySources(sourceIds: List<Long>): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comics WHERE id = :id")
    fun observeById(id: Long): Flow<ComicEntity?>

    @Query("SELECT * FROM comics WHERE id = :id")
    suspend fun findById(id: Long): ComicEntity?

    @Query("SELECT * FROM comics WHERE source_id = :sourceId AND file_path = :path")
    suspend fun findByPath(sourceId: Long, path: String): ComicEntity?

    @Query("SELECT * FROM comics WHERE source_id = :sourceId")
    suspend fun listBySource(sourceId: Long): List<ComicEntity>

    @Query(
        """
        SELECT c.* FROM comics c
        INNER JOIN reading_progress p ON p.comic_id = c.id
        ORDER BY p.updated_at DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<ComicEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComic(entity: ComicEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComics(entities: List<ComicEntity>)

    @Query("DELETE FROM comics WHERE source_id = :sourceId AND id NOT IN (:keepIds)")
    suspend fun deleteMissing(sourceId: Long, keepIds: List<Long>)

    @Query("DELETE FROM comics WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM comic_cache WHERE comic_id = :comicId")
    fun observeCache(comicId: Long): Flow<ComicCacheEntity?>

    @Query("SELECT * FROM comic_cache WHERE comic_id = :comicId")
    suspend fun findCache(comicId: Long): ComicCacheEntity?

    @Query("SELECT * FROM comic_cache")
    fun observeAllCaches(): Flow<List<ComicCacheEntity>>

    @Query("SELECT * FROM comic_cache")
    suspend fun listAllCaches(): List<ComicCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(entity: ComicCacheEntity)

    @Query("DELETE FROM comic_cache WHERE comic_id = :comicId")
    suspend fun deleteCache(comicId: Long)

    @Query("SELECT * FROM reading_progress WHERE comic_id = :comicId")
    suspend fun findProgress(comicId: Long): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE comic_id = :comicId")
    fun observeProgress(comicId: Long): Flow<ReadingProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(entity: ReadingProgressEntity)
}
