package com.kaguya.comicsviewer.data.cache

import android.util.Log
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.util.CacheDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * [ComicPage.archivePath] 有两种语义：
 * - 以 "saf:" 开头：本地源 SAF 流直读（不复制压缩包）。解压时由 [extractNearbyFromStream]
 *   串行开一个 SAF 流，一次顺序扫描整包把"当前页 + 附近页"批量解出，避免 Compose 同时展示
 *   多页时并发开数十个流、各自重扫全包导致的 "Job was cancelled" 取消风暴。
 * - 否则为可读的本地压缩包绝对路径（SMB 已下载副本），走随机访问解压单页。
 */
@Singleton
class PageImageCache @Inject constructor(
    private val cacheDirs: CacheDirectories,
    private val extractor: ArchiveExtractor,
    private val repository: ComicRepository
) {
    /**
     * SAF 流解压专用作用域：绑定到 App 生命周期（SupervisorJob + IO），
     * 不继承调用方（Coil fetch / 阅读器 UI）的可取消上下文。
     * 这样页滑出可视区被 Coil 取消时，正在顺序扫描整包的解压不会被中断 —— 解完落盘，
     * 下次直接命中，从根本上消除 "Job was cancelled" 取消风暴与重复扫描。
     * App 退出时该 Job 自动取消，无泄漏。
     */
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每本漫画保留的最大缓存页数。 */
    private val maxPagesPerComic = 12

    /** 预取范围：当前页前后各 [prefetchRadius] 页。 */
    private val prefetchRadius = 3

    /**
     * 当前正在阅读漫画的全部页列表（由 [prefetch] 注入），供 SAF 批量解压确定页范围。
     * 非线程安全但仅由阅读器的主/IO 协程顺序赋值，读取在 withLock 内发生，足够安全。
     */
    @Volatile
    private var pageList: List<com.kaguya.comicsviewer.domain.model.ComicPage>? = null

    /** 每本漫画的解压去重锁，避免同一页重复解压（非 SAF 分支用）。 */
    private val locks = mutableMapOf<Long, Mutex>()
    private val locksGuard = Mutex()

    /**
     * SAF 流直读分支的串行锁：保证同一漫画同一时刻只有一个 SAF 流在扫描解压。
     * 否则 Compose 同时展示多页时会对超大压缩包开数十个并发流、各自顺序重扫全包，
     * 互相取消导致 "Job was cancelled" 风暴且全失败。
     */
    private val safLocks = mutableMapOf<Long, Mutex>()
    private val safLocksGuard = Mutex()

    private suspend fun lockFor(comicId: Long): Mutex = locksGuard.withLock {
        locks.getOrPut(comicId) { Mutex() }
    }

    private suspend fun safLockFor(comicId: Long): Mutex = safLocksGuard.withLock {
        safLocks.getOrPut(comicId) { Mutex() }
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
        if (archivePath.startsWith("saf:")) {
            // 本地源 SAF 流直读：串行开一个流，批次解压"自身 + 附近页"，避免并发重扫全包。
            extractNearbyFromStream(comicId, archivePath, page)
            return target.takeIf { it.exists() && it.length() > 0 }?.also { touch(it) }
        }
        // 非 SAF（SMB/本地可直读副本）：随机访问解压单页。
        val lock = lockFor(comicId)
        return lock.withLock {
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

    /**
     * SAF 流直读：以 [page] 为中心、半径 [prefetchRadius] 取一页范围，串行开一个 SAF 流，
     * 一次顺序扫描整个压缩包，把范围内所有尚未落盘的页批量解压到页缓存。
     * 这样 Compose 同时请求多页时不会发生"每页一个流各扫全包"的取消风暴。
     */
    private suspend fun extractNearbyFromStream(
        comicId: Long,
        archivePath: String,
        page: com.kaguya.comicsviewer.domain.model.ComicPage
    ) {
        val streamComicId = runCatching { archivePath.removePrefix("saf:").toLong() }.getOrNull() ?: comicId
        val pages = pageList ?: return
        val from = max(0, page.index - prefetchRadius)
        val to = minOf(pages.lastIndex, page.index + prefetchRadius)
        val batch = (from..to).mapNotNull { i ->
            val p = pages.getOrNull(i) ?: return@mapNotNull null
            val en = p.entryName ?: return@mapNotNull null
            val t = File(cacheDirs.pageCacheDir(comicId), cacheFileName(i, en))
            en to t
        }
        if (batch.isEmpty()) return
        val lock = safLockFor(comicId)
        lock.withLock {
            // 已在锁外被其他请求解出的页跳过，避免重复扫描
            val pending = batch.filter { (_, t) -> !(t.exists() && t.length() > 0) }
            if (pending.isEmpty()) return
            // 关键：流的“打开”和“关闭”必须都在 decodeScope 任务内部完成，
            // 与 native 扫描（libarchive）的生命周期严格绑定。绝不可在调用方（Coil/UI 协程）
            // 的 finally 里关闭流——否则调用方取消 await() 后立刻关流，而 decodeScope 内的
            // native 扫描仍在跑，会读写已关闭的流 → use-after-free → SIGSEGV 崩溃。
            // decodeScope 是独立 SupervisorJob，不继承调用方取消，因此任务会完整跑完（解压落盘）；
            // 调用方 await() 虽可能因自身取消抛 CancellationException，但流由任务自身 finally 安全关闭。
            runCatching {
                decodeScope.async {
                    val pair = repository.openArchiveStream(streamComicId)
                    if (pair == null) {
                        Log.w("PageImageCache", "SAF 流打开失败: comicId=$streamComicId")
                        return@async
                    }
                    val (input, _) = pair
                    try {
                        extractor.extractImagesToStream(input, pending)
                    } finally {
                        runCatching { input.close() }
                    }
                }.await()
            }.onFailure { e ->
                // 调用方（Coil fetch / 阅读器 UI）滑走被取消属正常，不刷 ERROR 噪音。
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d("PageImageCache", "SAF 批量流解压等待被取消（正常，解压仍在后台完成）: comicId=$streamComicId")
                } else {
                    Log.e("PageImageCache", "SAF 批量流解压失败: ${e.message}", e)
                }
            }
            evictIfNeeded(comicId)
        }
    }

    /** 预取当前页附近页面（后台执行，不阻塞）。 */
    suspend fun prefetch(comicId: Long, currentIndex: Int, pages: List<com.kaguya.comicsviewer.domain.model.ComicPage>) {
        this.pageList = pages
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
