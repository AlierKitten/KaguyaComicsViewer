package com.kaguya.comicsviewer.data.repository

import com.kaguya.comicsviewer.data.local.dao.ComicDao
import com.kaguya.comicsviewer.data.local.dao.ComicSourceDao
import com.kaguya.comicsviewer.data.local.entity.toDomain
import com.kaguya.comicsviewer.data.local.entity.toEntity
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicPage
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ReadingProgress
import com.kaguya.comicsviewer.util.CacheDirectories
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComicRepositoryImpl @Inject constructor(
    private val sourceDao: ComicSourceDao,
    private val comicDao: ComicDao,
    private val cacheDirs: CacheDirectories
) : ComicRepository {

    override fun observeSources(): Flow<List<ComicSource>> =
        sourceDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeSource(id: Long): Flow<ComicSource?> =
        sourceDao.observeById(id).map { it?.toDomain() }

    override suspend fun listEnabledSources(): List<ComicSource> =
        sourceDao.listEnabled().map { it.toDomain() }

    override suspend fun upsertSource(source: ComicSource): Long = sourceDao.upsert(source.toEntity())

    override suspend fun deleteSource(id: Long) = sourceDao.deleteById(id)

    override suspend fun markSourceScanned(id: Long, timestamp: Long) =
        sourceDao.updateLastScanned(id, timestamp)

    override fun observeComicsBySources(sourceIds: List<Long>): Flow<List<Comic>> =
        comicDao.observeBySources(sourceIds).map { list -> list.map { it.toDomain() } }

    override fun observeComicCountBySources(sourceIds: List<Long>): Flow<Map<Long, Int>> =
        comicDao.countBySources(sourceIds).map { list -> list.associate { it.sourceId to it.count } }

    override suspend fun listComicsBySources(sourceIds: List<Long>): List<Comic> =
        comicDao.observeBySources(sourceIds).first().map { it.toDomain() }

    override suspend fun deleteComic(id: Long) = comicDao.deleteById(id)

    override fun observeComic(id: Long): Flow<Comic?> =
        comicDao.observeById(id).map { it?.toDomain() }

    override fun observeRecent(limit: Int): Flow<List<Comic>> =
        comicDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override fun observeLoading(): Flow<List<Comic>> =
        comicDao.observeLoading().map { list -> list.map { it.toDomain() } }

    override suspend fun findComic(id: Long): Comic? = comicDao.findById(id)?.toDomain()

    override suspend fun upsertComic(comic: Comic): Long = comicDao.upsertComic(comic.toEntity())

    override suspend fun upsertComics(comics: List<Comic>) =
        comicDao.upsertComics(comics.map { it.toEntity() })

    override suspend fun removeComicsNotIn(sourceId: Long, keepIds: List<Long>) {
        if (keepIds.isEmpty()) {
            comicDao.deleteMissing(sourceId, listOf(-1))
        } else {
            comicDao.deleteMissing(sourceId, keepIds)
        }
    }

    override fun observeCache(comicId: Long): Flow<ComicCache?> =
        comicDao.observeCache(comicId).map { it?.toDomain() }

    override fun observeAllCaches(): Flow<List<ComicCache>> =
        comicDao.observeAllCaches().map { list -> list.map { it.toDomain() } }

    override suspend fun listAllCaches(): List<ComicCache> =
        comicDao.listAllCaches().map { it.toDomain() }

    override suspend fun findCache(comicId: Long): ComicCache? = comicDao.findCache(comicId)?.toDomain()

    override suspend fun upsertCache(cache: ComicCache) {
        comicDao.upsertCache(
            ComicCache(
                comicId = cache.comicId,
                state = cache.state,
                archiveFile = cache.archiveFile,
                extractedDir = cache.extractedDir,
                totalBytes = cache.totalBytes,
                downloadedBytes = cache.downloadedBytes,
                lastError = cache.lastError
            ).toEntity(cache.comicId)
        )
    }

    override suspend fun deleteCache(comicId: Long) {
        // Remove on-disk files first.
        comicDao.findCache(comicId)?.let { entity ->
            entity.archiveFile?.let { p -> runCatching { java.io.File(p).delete() } }
            entity.extractedDir?.let { p -> runCatching { java.io.File(p).deleteRecursively() } }
        }
        comicDao.deleteCache(comicId)
    }

    override fun observeProgress(comicId: Long): Flow<ReadingProgress?> =
        comicDao.observeProgress(comicId).map { it?.toDomain() }

    override suspend fun saveProgress(progress: ReadingProgress) =
        comicDao.upsertProgress(progress.toEntity())

    override suspend fun listPages(comicId: Long): List<ComicPage> {
        val cache = comicDao.findCache(comicId)?.toDomain() ?: return emptyList()
        if (cache.state != CacheState.READY || cache.extractedDir.isNullOrBlank()) return emptyList()
        val dir = java.io.File(cache.extractedDir)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.isImageFile() }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return files.mapIndexed { index, file ->
            ComicPage(comicId = comicId, index = index, path = file.absolutePath)
        }
    }

    override suspend fun updatePageCount(comicId: Long, count: Int) {
        comicDao.updatePageCount(comicId, count)
    }

    override suspend fun clearAllProgress() {
        comicDao.clearAllProgress()
    }

    private fun java.io.File.isImageFile(): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp") ||
            n.endsWith(".gif") || n.endsWith(".bmp") ||
            n.endsWith(".avif") || n.endsWith(".heic")
    }
}
