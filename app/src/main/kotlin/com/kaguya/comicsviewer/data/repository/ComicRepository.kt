package com.kaguya.comicsviewer.data.repository

import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicPage
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream

interface ComicRepository {
    fun observeSources(): Flow<List<ComicSource>>
    fun observeSource(id: Long): Flow<ComicSource?>
    suspend fun listEnabledSources(): List<ComicSource>
    suspend fun upsertSource(source: ComicSource): Long
    suspend fun deleteSource(id: Long)
    suspend fun markSourceScanned(id: Long, timestamp: Long)
    /** 落库索引进度（状态 + 已处理/总数），供被杀后判断真实状态与断点续传。 */
    suspend fun updateSourceIndex(id: Long, status: String, current: Int, total: Int)

    /** 应用启动时调用：将残留的 SCANNING 状态（进程被杀导致）重置，避免 UI 误以为索引仍在进行或已完成。 */
    suspend fun resetStaleIndexingStatuses()

    fun observeComicsBySources(sourceIds: List<Long>): Flow<List<Comic>>
    fun observeAllComics(): Flow<List<Comic>>
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

    /**
     * 统一解析出可读的本地压缩包文件（本地源与 SMB 源共用，实现效果一致）：
     * - 本地源：优先用 SAF DocumentFile 的真实文件路径直读原始压缩包；
     *   不可读（如 scoped storage 无权限）则懒复制压缩包副本到缓存目录再读。
     * - SMB 源：压缩包已由下载 Worker 落到本地缓存，直接返回该副本文件。
     * 返回的 File 一定可被随机访问（seek），供 ArchiveExtractor 直接解压单页。
     * 若不存在或解析失败返回 null。
     */
    suspend fun resolveArchiveFile(comicId: Long): File?

    /**
     * 打开本地源原始压缩包的输入流（SAF 流直读，不复制压缩包）。
     * 返回 (InputStream, 原始文件名) 或 null（非本地源 / 无法打开）。
     * 调用方负责关闭 InputStream。
     */
    suspend fun openArchiveStream(comicId: Long): Pair<InputStream, String>?

    suspend fun updatePageCount(comicId: Long, count: Int)

    suspend fun clearAllProgress()

    suspend fun clearAllCoverPaths()
}
