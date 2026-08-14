package com.kaguya.comicsviewer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kaguya.comicsviewer.data.local.entity.ComicSourceEntity
import kotlinx.coroutines.flow.Flow

/** 索引状态落库常量（与 domain.model.IndexStatus 对应）。 */
private const val INDEX_STATUS_IDLE = "IDLE"
private const val INDEX_STATUS_SCANNING = "SCANNING"

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

    @Query(
        "UPDATE comic_sources " +
        "SET index_status = :status, index_current = :current, index_total = :total " +
        "WHERE id = :id"
    )
    suspend fun updateIndexProgress(
        id: Long,
        status: String,
        current: Int,
        total: Int
    )

    /** 应用启动时清理：将残留的 SCANNING 状态（进程被杀）重置为 IDLE，避免 UI 误报。 */
    @Query("UPDATE comic_sources SET index_status = '${INDEX_STATUS_IDLE}' WHERE index_status = '${INDEX_STATUS_SCANNING}'")
    suspend fun resetStaleIndexingStatuses()

    @Query("DELETE FROM comic_sources WHERE id = :id")
    suspend fun deleteById(id: Long)
}
