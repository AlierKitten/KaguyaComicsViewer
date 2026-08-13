package com.kaguya.comicsviewer.data.cache

import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.util.CacheDirectories
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 页级 LRU 磁盘缓存。
 *
 * ZIP/CBZ 源不整包解压，阅读时按需把当前页及附近页解压到该缓存目录，
 * Coil 直接读取缓存文件；最多保留 [maxPagesPerComic] 个缓存页，超出按
 * 最久未访问淘汰。同一页并发请求只解压一次（[Mutex] 去重）。
 *
 * [ComicPage.archivePath] 一定是可读的本地压缩包文件路径（本地源经真实路径直读或
 * 懒缓存副本、SMB 源为下载副本），因此本地源与 SMB 源完全共用同一解压逻辑，
 * 无需区分数据来源。
 */
@Singleton
class PageImageCache @Inject constructor(
    private val cacheDirs: CacheDirectories,
    private val extractor: ArchiveExtractor
) {
    /** 每本漫画保留的最大缓存页数。 */
    private val maxPagesPerComic = 12

    /** 预取范围：当前页前后各 [prefetchRadius] 页。 */
    private val prefetchRadius = 3

    /** 每本漫画的解压去重锁，避免同一页重复解压。 */
    private val locks = mutableMapOf<Long, Mutex>()
    private val locksGuard = Mutex()

    private suspend fun lockFor(comicId: Long): Mutex = locksGuard.withLock {
        locks.getOrPut(comicId) { Mutex() }
    }

    /** 缓存文件名（entry 名可能含非法字符，做安全化）。 */
    private fun cacheFileName(index: Int, entryName: String): String {
        val ext = entryName.substringAfterLast('.', "jpg").takeIf { it.isNotBlank() }?.lowercase() ?: "jpg"
        val safe = entryName
            .substringAfterLast('/')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(80)
        return "${index}_${safe}.${ext}"
    }

    /**
     * 获取（或解压）指定页的缓存文件。已存在则直接返回；否则解压并写入。
     */
    suspend fun getOrCreate(comicId: Long, page: com.kaguya.comicsviewer.domain.model.ComicPage): File? {
        val archivePath = page.archivePath ?: return null
        val entryName = page.entryName ?: return null
        val dir = cacheDirs.pageCacheDir(comicId)
        val target = File(dir, cacheFileName(page.index, entryName))
        if (target.exists() && target.length() > 0) {
            touch(target)
            return target
        }
        val lock = lockFor(comicId)
        return lock.withLock {
            // double-check
            if (target.exists() && target.length() > 0) {
                touch(target)
                return@withLock target
            }
            val ok = extractor.extractImageTo(File(archivePath), entryName, target)
            if (ok) {
                evictIfNeeded(comicId)
                target
            } else {
                null
            }
        }
    }

    /** 预取当前页附近页面（后台执行，不阻塞）。 */
    suspend fun prefetch(comicId: Long, currentIndex: Int, pages: List<com.kaguya.comicsviewer.domain.model.ComicPage>) {
        val from = max(0, currentIndex - prefetchRadius)
        val to = minOf(pages.lastIndex, currentIndex + prefetchRadius)
        for (i in from..to) {
            val p = pages.getOrNull(i) ?: continue
            if (p.isArchive) getOrCreate(comicId, p)
        }
    }

    /** 按访问时间淘汰最久未用的页面，保留最近 [maxPagesPerComic] 个。 */
    fun evictIfNeeded(comicId: Long) {
        val dir = File(cacheDirs.pageCache, "comic_$comicId")
        if (!dir.exists()) return
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        val excess = files.size - maxPagesPerComic
        if (excess > 0) {
            files.take(excess).forEach { it.delete() }
        }
    }

    /** 更新文件访问时间（不修改内容），用于 LRU 排序。 */
    private fun touch(file: File) {
        val now = System.currentTimeMillis()
        file.setLastModified(now)
    }

    /** 删除某本漫画的全部页缓存。 */
    fun clear(comicId: Long) = cacheDirs.clearPageCache(comicId)
}
