package com.kaguya.comicsviewer.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kaguya.comicsviewer.data.local.dao.ComicDao
import com.kaguya.comicsviewer.data.local.dao.ComicSourceDao
import com.kaguya.comicsviewer.data.local.entity.toDomain
import com.kaguya.comicsviewer.data.local.entity.toEntity
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicPage
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.domain.model.ReadingProgress
import com.kaguya.comicsviewer.util.CacheDirectories
import com.kaguya.comicsviewer.util.FileDescriptorCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: ComicSourceDao,
    private val comicDao: ComicDao,
    private val cacheDirs: CacheDirectories,
    private val extractor: ArchiveExtractor
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

    override fun observeAllComics(): Flow<List<Comic>> =
        comicDao.observeAll().map { list -> list.map { it.toDomain() } }

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
            // 仅删除缓存副本；外部存储上的原始压缩包（本地源直接读取）不得删除。
            if (!entity.isExternalArchive) {
                entity.archiveFile?.let { p -> runCatching { java.io.File(p).delete() } }
            } else {
                // 本地源：我们在 archives/ 下懒缓存了压缩包副本（comic_<id>.*），此处应一并清理，
                // 但绝不删除用户原始文件（archiveFile 为 "saf:<id>" 标识，非真实路径）。
                cacheDirs.archiveFile(comicId)?.let { f ->
                    runCatching { if (f.exists() && f.name.startsWith("comic_$comicId")) f.delete() }
                }
            }
            entity.extractedDir?.let { p -> runCatching { java.io.File(p).deleteRecursively() } }
        }
        cacheDirs.clearPageCache(comicId)
        comicDao.deleteCache(comicId)
    }

    override fun observeProgress(comicId: Long): Flow<ReadingProgress?> =
        comicDao.observeProgress(comicId).map { it?.toDomain() }

    override suspend fun saveProgress(progress: ReadingProgress) =
        comicDao.upsertProgress(progress.toEntity())

    /**
     * 统一解析出可读的本地压缩包文件（本地源与 SMB 源共用，实现效果一致）。
     * - 本地源：优先用 SAF DocumentFile 的真实文件路径 [FileDescriptorCompat.path] 直读原始压缩包；
     *   不可读（scoped storage 无权限）则懒复制压缩包副本到缓存目录再读。
     * - SMB 源：压缩包已由下载 Worker 落到本地缓存（archiveFile 即本地副本路径），直接返回。
     * 返回的 File 一定可被随机访问（seek），供 ArchiveExtractor 直接解压单页。
     */
    override suspend fun resolveArchiveFile(comicId: Long): java.io.File? {
        val cache = comicDao.findCache(comicId) ?: return null
        val comic = findComic(comicId) ?: return null
        val source = sourceDao.findById(comic.sourceId)?.toDomain() ?: return null

        return if (source.type == ComicSourceType.LOCAL) {
            resolveLocalArchive(comic, source)
        } else {
            // SMB 源：archiveFile 已是本地下载副本路径
            val path = cache.archiveFile
            if (path.isNullOrBlank()) null else java.io.File(path).takeIf { it.isFile }
        }
    }

    /**
     * 本地源解析：优先真实路径直读；不可读则懒复制副本到 archives/。
     * 返回的 File 若为原始文件（直读），调用方在删除缓存时不得删除它。
     */
    private suspend fun resolveLocalArchive(
        comic: Comic,
        source: ComicSource
    ): java.io.File? {
        val localUri = source.localUri ?: return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(localUri)) ?: return null
        val parts = comic.filePath.split('/').filter { it.isNotBlank() }
        var cur: DocumentFile? = root
        for (p in parts) {
            cur = cur?.listFiles()?.firstOrNull { it.name == p } ?: return null
        }
        val doc = cur ?: return null

        // 1) 优先真实路径直读（外部存储上的真实文件，可被 libarchive seek）
        val realPath = FileDescriptorCompat.path(doc)
        if (realPath != null) {
            val f = java.io.File(realPath)
            if (f.isFile && f.canRead()) return f
        }
        // 2) 回退：懒复制压缩包副本到 archives/ 目录（仅副本，非解压全部图片）
        val fileName = comic.filePath.substringAfterLast('/')
        val cacheFile = cacheDirs.archiveFile(comic.id, fileName)
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile
        return try {
            cacheFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                java.io.FileOutputStream(cacheFile).use { out -> input.copyTo(out, 256 * 1024) }
            }
            if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
        } catch (e: Exception) {
            android.util.Log.w("ComicRepositoryImpl", "本地源复制压缩包失败: ${e.message}")
            null
        }
    }

    override suspend fun listPages(comicId: Long): List<ComicPage> {
        val cache = comicDao.findCache(comicId)?.toDomain() ?: return emptyList()
        if (cache.state != CacheState.READY) return emptyList()

        // 本地源与 SMB 源共用同一路径：解析出可读的本地压缩包文件后按需列举图片条目。
        val archiveFile = resolveArchiveFile(comicId) ?: return emptyList()
        val entries = extractor.listImageEntries(archiveFile)
        if (entries.isEmpty()) return emptyList()
        return entries.mapIndexed { index, entryName ->
            ComicPage(
                comicId = comicId,
                index = index,
                archivePath = archiveFile.absolutePath,
                entryName = entryName
            )
        }
    }

    override suspend fun updatePageCount(comicId: Long, count: Int) {
        comicDao.updatePageCount(comicId, count)
    }

    override suspend fun clearAllProgress() {
        comicDao.clearAllProgress()
    }

    override suspend fun clearAllCoverPaths() {
        comicDao.clearAllCoverPaths()
    }

    private fun java.io.File.isImageFile(): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp") ||
            n.endsWith(".gif") || n.endsWith(".bmp") ||
            n.endsWith(".avif") || n.endsWith(".heic")
    }
}
