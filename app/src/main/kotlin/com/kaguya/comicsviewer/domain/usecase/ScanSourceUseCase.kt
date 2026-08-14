package com.kaguya.comicsviewer.domain.usecase

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kaguya.comicsviewer.MainActivity
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.DiscoveredComic
import com.kaguya.comicsviewer.data.source.LocalFileScanner
import com.kaguya.comicsviewer.data.source.SmbFileScanner
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.domain.model.IndexStatus
import com.kaguya.comicsviewer.notification.NotificationChannels
import com.kaguya.comicsviewer.util.CacheDirectories
import com.kaguya.comicsviewer.work.IndexKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 扫描一个源：发现所有漫画并同步到数据库。 */
@Singleton
class ScanSourceUseCase @Inject constructor(
    private val appContext: Application,
    private val repository: ComicRepository,
    private val localScanner: LocalFileScanner,
    private val smbScanner: SmbFileScanner,
    private val cacheDirs: CacheDirectories,
    private val settings: SettingsRepository
) {
    companion object {
        private const val TAG = "ScanSourceUseCase"
        private const val DONE_NOTIFY_ID = 5002
    }

    /** 后台 index 协程作用域：绑定 Application 生命周期，不在 UI 协程中运行，
     *  因此切页面、退出到后台、ViewModel 重建都不会中断 index。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 取消标志：由调用方在「开始一次扫描任务」前调用 resetCancellation() 清零；调用 cancel() 置为 true 后扫描循环应尽快中止（已写入的数据保留）。 */
    private val cancelled = AtomicBoolean(false)

    /** 正在后台索引的源 ID 集合，供 UI 观察展示「扫描中」状态。 */
    private val _indexingIds = MutableStateFlow<Set<Long>>(emptySet())
    val indexingIds: StateFlow<Set<Long>> = _indexingIds.asStateFlow()

    /** 是否已请求停止（用于 UI 显示「正在停止…」）。 */
    private val _isStopping = MutableStateFlow(false)
    val isStopping: StateFlow<Boolean> = _isStopping.asStateFlow()

    /** 重置取消标志（应在开始一次新的扫描任务时调用，不要在循环的每个源之前调用，否则会清除正在进行的取消请求）。 */
    fun resetCancellation() {
        cancelled.set(false)
    }

    /** 请求中止当前正在进行的扫描。 */
    fun cancel() {
        cancelled.set(true)
        _isStopping.value = true
    }

    /** 当前是否请求中止。 */
    fun isCancelled(): Boolean = cancelled.get()

    /** 按 ID 获取单个文件源（供后台索引调用）。 */
    suspend fun getSource(sourceId: Long): ComicSource? =
        repository.observeSources().first().firstOrNull { it.id == sourceId }

    /** 是否正在后台索引。 */
    fun isIndexing(): Boolean = _indexingIds.value.isNotEmpty()

    /** 在后台开始索引单个源（不阻塞调用方，可自由切页面/退后台）。 */
    fun startScan(source: ComicSource) {
        val indexCover = settings.settings.value.indexCoverOnScan
        scope.launch {
            resetCancellation()
            _isStopping.value = false
            _indexingIds.value = _indexingIds.value + source.id
            ensureKeepAlive()
            try {
                runSource(source, indexCover)
                onIndexSettled(done = 1, failed = 0)
            } catch (e: Exception) {
                Log.e(TAG, "startScan failed for '${source.name}'", e)
                onIndexSettled(done = 0, failed = 1)
            } finally {
                _indexingIds.value = _indexingIds.value - source.id
            }
        }
    }

    /** 在后台开始索引所有启用的源（顺序执行）。 */
    fun startScanAll() {
        scope.launch {
            val sources = repository.listEnabledSources()
            if (sources.isEmpty()) return@launch
            resetCancellation()
            _isStopping.value = false
            _indexingIds.value = sources.map { it.id }.toSet()
            ensureKeepAlive()
            var done = 0
            var failed = 0
            try {
                for (s in sources) {
                    if (cancelled.get()) break
                    try {
                        runSource(s, settings.settings.value.indexCoverOnScan)
                        done++
                    } catch (e: Exception) {
                        Log.e(TAG, "startScanAll failed for '${s.name}'", e)
                        failed++
                    }
                }
            } finally {
                _indexingIds.value = emptySet()
                onIndexSettled(done, failed)
            }
        }
    }

    /**
     * 跑单个源的完整扫描，并在过程中：
     * - 落库索引状态（SCANNING → DONE/CANCELLED/FAILED），防止进程被杀后误报完成；
     * - 实时更新前台通知进度（仅百分比，不展示具体名字）。
     */
    private suspend fun runSource(source: ComicSource, indexCover: Boolean) {
        repository.updateSourceIndex(source.id, IndexStatus.SCANNING.name, 0, 0)
        IndexKeepAliveService.updateProgress(appContext, 0, 1)
        try {
            val found = invoke(
                source,
                indexCover = indexCover,
                onPhase1 = { total ->
                    // Phase1 完成：写入总数（已处理 0 / 总漫画数）；落库为异步写，不阻塞扫描回调
                    scope.launch { repository.updateSourceIndex(source.id, IndexStatus.SCANNING.name, 0, total) }
                    IndexKeepAliveService.updateProgress(appContext, 0, total)
                },
                onProgress = { current, total ->
                    // Phase2 进度：已处理 / 总数
                    scope.launch { repository.updateSourceIndex(source.id, IndexStatus.SCANNING.name, current, total) }
                    IndexKeepAliveService.updateProgress(appContext, current, total)
                }
            )
            if (cancelled.get()) {
                // 用户中途取消，但数据已落库，记为 CANCELLED
                repository.updateSourceIndex(source.id, IndexStatus.CANCELLED.name, found, found)
            } else {
                repository.updateSourceIndex(source.id, IndexStatus.DONE.name, found, found)
                IndexKeepAliveService.updateProgress(appContext, found, found)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            repository.updateSourceIndex(source.id, IndexStatus.CANCELLED.name, 0, 0)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runSource failed for '${source.name}'", e)
            repository.updateSourceIndex(source.id, IndexStatus.FAILED.name, 0, 0)
            throw e
        }
    }

    /** 当一组源全部索引结束（成功或取消）时调用：停止保活服务，必要时发完成通知。 */
    private fun onIndexSettled(done: Int = 0, failed: Int = 0) {
        if (_indexingIds.value.isEmpty()) {
            IndexKeepAliveService.stop(appContext)
            _isStopping.value = false
            if (done > 0 || failed > 0) {
                notifyDone(done, failed)
            }
        }
    }

    /** 确保保活前台服务在运行（仅首次进入索引时启动）。 */
    private fun ensureKeepAlive() {
        IndexKeepAliveService.start(appContext)
    }

    private fun notifyDone(done: Int, failed: Int) {
        val text = when {
            failed == 0 -> "已完成 $done 个文件源"
            done == 0 -> "索引失败：$failed 个源未能扫描，请检查源配置"
            else -> "已完成 $done 个源，$failed 个源失败"
        }
        val pi = PendingIntent.getActivity(
            appContext,
            DONE_NOTIFY_ID,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, NotificationChannels.CHANNEL_GENERAL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (failed == 0 && done > 0) "索引完成" else "索引结束")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        if (!IndexKeepAliveService.hasNotificationPermission(appContext)) return
        try {
            NotificationManagerCompat.from(appContext).notify(DONE_NOTIFY_ID, notification)
        } catch (e: SecurityException) {
            // 无 POST_NOTIFICATIONS 权限时忽略
        }
    }

    /**
     * 两阶段扫描：
     * 1. 快速索引所有漫画名（立即写入数据库，UI 可显示）
     * 2. 后台获取文件大小 + 生成封面
     *
     * @param onPhase1 第一阶段完成时回调（参数为发现的漫画总数）
     * @param onProgress Phase2 进度回调（参数依次为已处理数、总数）
     * @param onPhase2 第二阶段进度文本回调（保留，用于日志/埋点）
     * @param onRarSkipped 本地源发现不支持的 RAR/CBR 数量时回调（用于提示用户）
     */
    suspend operator fun invoke(
        source: ComicSource,
        onPhase1: (Int) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onPhase2: (String) -> Unit = {},
        onRarSkipped: (Int) -> Unit = {},
        indexCover: Boolean = true
    ): Int {
        Log.d(TAG, "scan start: source='${source.name}', type=${source.type}")
        val scanner = when (source.type) {
            ComicSourceType.LOCAL -> localScanner
            ComicSourceType.SMB -> smbScanner
        }

        // ── Phase 1: 快速索引 ──
        val found = try {
            scanner.scan(source) { cancelled.get() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "scan cancelled for source '${source.name}'")
            return 0
        } catch (e: Exception) {
            Log.e(TAG, "scan failed for source '${source.name}'", e)
            return 0
        }
        Log.d(TAG, "phase1: found ${found.size} comics for source '${source.name}'")
        val now = System.currentTimeMillis()

        val existing = repository.listComicsBySources(listOf(source.id))
        val existingByPath = existing.associateBy { it.filePath }
        val keepIds = mutableListOf<Long>()
        // 本地源仅支持 ZIP/CBZ；RAR/CBR 不支持，跳过不入库并统计数量用于提示。
        var rarSkipped = 0

        for (d in found) {
            // 本软件不支持 RAR/CBR（无法随机访问、按需解压单页），扫描阶段直接跳过并提示用户。
            if (!d.isZip) {
                rarSkipped++
                Log.d(TAG, "comic '${d.title}': RAR/CBR not supported, skipped (filePath=${d.relativePath})")
                continue
            }

            val prev = existingByPath[d.relativePath]
            val comic = Comic(
                id = prev?.id ?: 0L,
                sourceId = source.id,
                title = d.title,
                filePath = d.relativePath,
                sizeBytes = prev?.sizeBytes ?: 0L,
                pageCount = prev?.pageCount ?: 0,
                coverPath = prev?.coverPath,
                addedAt = prev?.addedAt ?: now,
                updatedAt = now
            )
            val id = repository.upsertComic(comic)
            keepIds += id

            // 本地源：直接以原始压缩包作为数据源，不复制一份到缓存目录。
            // ZIP/CBZ 支持随机访问，可「按需单页解压、整包零落盘」，扫描后直接 READY（阅读时按页从 SAF 流解压）。
            // archiveFile 记为 "saf:<id>" 标识，isExternalArchive=true 表示原始文件在外部存储，清理缓存时不得删除。
            if (source.type == ComicSourceType.LOCAL) {
                Log.d(TAG, "local comic '${d.title}': isZip=${d.isZip}, filePath=${d.relativePath}")
                repository.upsertCache(
                    ComicCache(
                        comicId = id,
                        state = CacheState.READY,
                        archiveFile = "saf:$id",
                        extractedDir = null,
                        totalBytes = prev?.sizeBytes ?: 0L,
                        downloadedBytes = prev?.sizeBytes ?: 0L,
                        lastError = null,
                        isExternalArchive = true
                    )
                )
            }
        }

        repository.removeComicsNotIn(source.id, keepIds)
        repository.markSourceScanned(source.id, now)
        Log.d(TAG, "phase1 complete: upserted=${keepIds.size}, rarSkipped=$rarSkipped")

        // 通知 UI 第一阶段完成
        onPhase1(keepIds.size)
        // 有被跳过的 RAR/CBR 时提示用户
        if (rarSkipped > 0) {
            onRarSkipped(rarSkipped)
        }

        // ── Phase 2: 后台获取文件大小 + 生成封面 ──
        if (cancelled.get()) return found.size
        val needsSize = found.filter { it.sizeBytes == 0L }
        val needsCover = if (indexCover) found.filter { d ->
            val prev = existingByPath[d.relativePath]
            prev?.coverPath == null
        } else emptyList()

        if (needsSize.isEmpty() && needsCover.isEmpty()) {
            Log.d(TAG, "phase2: nothing to enrich")
            return found.size
        }

        val total = needsSize.size + needsCover.size
        var processed = 0

        // 2a. 获取文件大小
        if (needsSize.isNotEmpty()) {
            onPhase2("正在获取文件大小共 (${needsSize.size} 个漫画)...")
            try {
                val sizes = scanner.fetchSizes(source, needsSize)
                // 一次性获取当前数据库中的漫画，避免重复查询
                val comicsByPath = repository.listComicsBySources(listOf(source.id)).associateBy { it.filePath }
                for ((path, size) in sizes) {
                    if (size > 0) {
                        comicsByPath[path]?.let { comic ->
                            if (comic.sizeBytes != size) {
                                repository.upsertComic(comic.copy(sizeBytes = size))
                            }
                        }
                    }
                    processed++
                    onProgress(processed, total)
                }
                Log.d(TAG, "phase2: fetched ${sizes.size} file sizes")
            } catch (e: Exception) {
                Log.w(TAG, "phase2: fetchSizes failed", e)
                processed += needsSize.size
                onProgress(processed, total)
            }
        }

        // 2b. 生成封面（带自动重试：统计未获取封面数，有减少趋势就继续重试，直到不再减少或全部完成）
        if (indexCover && needsCover.isNotEmpty()) {
            // pending 持有「漫画 + 原始发现项」对，失败时留到下一轮重试
            val comicsByPath = repository.listComicsBySources(listOf(source.id)).associateBy { it.filePath }
            var pending = needsCover.mapNotNull { d -> comicsByPath[d.relativePath]?.let { c -> c to d } }
                .filter { (comic, _) -> comic.coverPath == null }
            var round = 0
            var prevSize = pending.size
            onPhase2("正在生成封面共 (${pending.size} 个漫画)...")
            while (pending.isNotEmpty() && !cancelled.get()) {
                round++
                val stillFailing = mutableListOf<Pair<Comic, DiscoveredComic>>()
                var successInRound = 0
                for ((comic, d) in pending) {
                    // 数据库里可能已被上轮更新，重新读取最新 coverPath
                    val latest = repository.findComic(comic.id)
                    if (latest?.coverPath != null) continue
                    try {
                        val coverBytes = scanner.cover(source, d)
                        if (coverBytes != null) {
                            val coverFile = cacheDirs.coverFile(comic.id)
                            coverFile.parentFile?.mkdirs()
                            FileOutputStream(coverFile).use { it.write(coverBytes) }
                            repository.upsertComic(comic.copy(coverPath = coverFile.absolutePath))
                            successInRound++
                            Log.d(TAG, "phase2: cover generated for comicId=${comic.id}, title='${d.title}'")
                        } else {
                            stillFailing.add(comic to d)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "phase2: cover generation failed for '${d.title}': ${e.message}")
                        stillFailing.add(comic to d)
                    }
                    processed++
                    onProgress(processed, total)
                }
                pending = stillFailing
                val remaining = pending.size
                if (remaining == 0) {
                    onPhase2("封面已全部生成完成")
                    break
                }
                // 有减少趋势（本轮有成功且数量在下降）才继续重试，否则停止避免无谓循环
                if (successInRound == 0 || remaining >= prevSize) {
                    onPhase2("有 $remaining 个封面未能获取，请检查网络或稍后刷新重试")
                    break
                }
                prevSize = remaining
                onPhase2("第 $round 轮完成，仍有 $remaining 个封面未获取，正在重试...")
                // 轮间退避，让 SMB 连接释放，缓解连接数上限
                kotlinx.coroutines.delay(1500L)
            }
            Log.d(TAG, "phase2: covers done, remaining pending=${pending.size}")
        }

        onPhase2("扫描完成")
        Log.d(TAG, "scan complete for source '${source.name}'")
        return found.size
    }
}
