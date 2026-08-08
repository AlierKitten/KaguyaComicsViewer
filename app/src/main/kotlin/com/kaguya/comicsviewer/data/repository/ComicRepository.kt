package com.kaguya.comicsviewer.data.repository

import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicPage
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow

interface ComicRepository {
    fun observeSources(): Flow<List<ComicSource>>
    fun observeSource(id: Long): Flow<ComicSource?>
    suspend fun listEnabledSources(): List<ComicSource>
    suspend fun upsertSource(source: ComicSource): Long
    suspend fun deleteSource(id: Long)
    suspend fun markSourceScanned(id: Long, timestamp: Long)

    fun observeComicsBySources(sourceIds: List<Long>): Flow<List<Comic>>
    fun observeComicCountBySources(sourceIds: List<Long>): Flow<Map<Long, Int>>
    suspend fun listComicsBySources(sourceIds: List<Long>): List<Comic>
    fun observeComic(id: Long): Flow<Comic?>
    fun observeRecent(limit: Int): Flow<List<Comic>>
    fun observeLoading(): Flow<List<Comic>>
    suspend fun findComic(id: Long): Comic?
    suspend fun upsertComic(comic: Comic): Long
    suspend fun upsertComics(comics: List<Comic>)
    suspend fun removeComicsNotIn(sourceId: Long, keepIds: List<Long>)
    suspend fun deleteComic(id: Long)

    fun observeCache(comicId: Long): Flow<ComicCache?>
    fun observeAllCaches(): Flow<List<ComicCache>>
    suspend fun listAllCaches(): List<ComicCache>
    suspend fun findCache(comicId: Long): ComicCache?
    suspend fun upsertCache(cache: ComicCache)
    suspend fun deleteCache(comicId: Long)

    fun observeProgress(comicId: Long): Flow<ReadingProgress?>
    suspend fun saveProgress(progress: ReadingProgress)

    suspend fun listPages(comicId: Long): List<ComicPage>

    suspend fun updatePageCount(comicId: Long, count: Int)

    suspend fun clearAllProgress()
}
