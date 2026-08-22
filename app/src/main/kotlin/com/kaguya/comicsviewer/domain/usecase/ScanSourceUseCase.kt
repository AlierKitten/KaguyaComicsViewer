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

        // 2b. 生成封面（带重试，且能识别「假成功」）
        // 修复点：
        //  1) 原逻辑「剩余数不再减少就停」不区分失败类型，会把临时失败（下轮能成功）与永久
        //     index 失败（压缩包本身读不出）一起丢弃。现改为连续失败达阈值(MAX_CONSECUTIVE_FAILS)
        //     轮才判为永久失败移出 retry 池；只要还有临时失败项就继续重试。
        //  2) 原逻辑只判 coverBytes != null，但 downscaleCover 在压缩失败/数据残缺时可能返回
        //     非空却无效的字节，导致「日志全成功、UI 却加载不出」的假成功。现写入前校验字节
        //     能解码为有效 Bitmap(outWidth>0)，无效视为失败(null)，使其进入 retry/永久失败判定。
        if (indexCover && needsCover.isNotEmpty()) {
            val comicsByPath = repository.listComicsBySources(listOf(source.id)).associateBy { it.filePath }
            var pending = needsCover.mapNotNull { d -> comicsByPath[d.relativePath]?.let { c -> c to d } }
                .filter { (comic, _) -> comic.coverPath == null }
            val consecutiveFails = mutableMapOf<Long, Int>()
            val MAX_CONSECUTIVE_FAILS = 3
            var round = 0
            onPhase2("正在生成封面 (${pending.size} 个漫画)...")
            // 写出封面文件（含 DB 标记）
            suspend fun writeCover(comic: Comic, bytes: ByteArray) {
                val coverFile = cacheDirs.coverFile(comic.id)
                coverFile.parentFile?.mkdirs()
                FileOutputStream(coverFile).use { it.write(bytes) }
                repository.upsertComic(comic.copy(coverPath = coverFile.absolutePath))
            }
            // 特殊尝试：常规 scanner.cover 连续失败达阈值的漫画，复用阅读器同款路径重新生成封面。
            // 本地源走 SAF 流直读，SMB 源走已下载副本（resolveArchiveFile + File 解压），
            // 两种源都覆盖，因为漫画阅读器本就同时支持本地与 SMB 源。
            // 该路径只把字节读入内存/临时目录、读后即删，封面文件即唯一产物，天然无额外缓存残留。
            // 返回 true 表示抢救成功。
            suspend fun specialStreamTry(comic: Comic): Boolean {
                Log.d(TAG, "specialStreamTry: 进入 comicId=${comic.id}, source.type=${source.type}")
                val bytes = repository.tryGenerateCoverViaStream(comic.id) ?: return false
                val ok = bytes.isNotEmpty() && runCatching {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    opts.outWidth > 0 && opts.outHeight > 0
                }.getOrDefault(false)
                if (ok) {
                    writeCover(comic, bytes)
                    return true
                }
                return false
            }
            while (pending.isNotEmpty() && !cancelled.get()) {
                round++
                val stillFailing = mutableListOf<Pair<Comic, DiscoveredComic>>()
                var successInRound = 0
                for ((comic, d) in pending) {
                    val latest = repository.findComic(comic.id)
                    if (latest?.coverPath != null) continue
                    var recovered = false
                    try {
                        val coverBytes = scanner.cover(source, d)
                        // 校验：非空且能解码为有效 Bitmap 才算真正成功，避免残缺/空字节的假成功
                        val valid = coverBytes != null && coverBytes.size > 0 && runCatching {
                            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, opts)
                            opts.outWidth > 0 && opts.outHeight > 0
                        }.getOrDefault(false)
                        if (valid) {
                            writeCover(comic, coverBytes)
                            successInRound++
                            consecutiveFails.remove(comic.id)
                            Log.d(TAG, "phase2: cover generated for comicId=${comic.id}, title='${d.title}'")
                        } else {
                            val fails = consecutiveFails.getOrDefault(comic.id, 0) + 1
                            consecutiveFails[comic.id] = fails
                            if (fails < MAX_CONSECUTIVE_FAILS) {
                                stillFailing.add(comic to d)
                                Log.w(TAG, "phase2: cover 无效(假成功)，第 $fails 轮，重试 comicId=${comic.id}, title='${d.title}'")
                            } else {
                                // 达阈值：先特殊尝试阅读器同款 SAF 流路径
                                recovered = specialStreamTry(comic)
                                if (recovered) {
                                    successInRound++
                                    consecutiveFails.remove(comic.id)
                                    Log.d(TAG, "phase2: 特殊尝试(SAF 流)成功生成封面 comicId=${comic.id}, title='${d.title}'")
                                } else {
                                    Log.w(TAG, "phase2: cover 连续 $fails 轮无效且特殊尝试失败，判定为永久失败，跳过 '${d.title}'")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val fails = consecutiveFails.getOrDefault(comic.id, 0) + 1
                        consecutiveFails[comic.id] = fails
                        if (fails < MAX_CONSECUTIVE_FAILS) {
                            stillFailing.add(comic to d)
                            Log.w(TAG, "phase2: cover generation failed for '${d.title}': ${e.message}")
                        } else {
                            recovered = specialStreamTry(comic)
                            if (recovered) {
                                successInRound++
                                consecutiveFails.remove(comic.id)
                                Log.d(TAG, "phase2: 特殊尝试(SAF 流)成功生成封面 comicId=${comic.id}, title='${d.title}'")
                            } else {
                                Log.w(TAG, "phase2: cover 连续 $fails 轮异常(${e.message})且特殊尝试失败，判定为永久失败，跳过 '${d.title}'")
                            }
                        }
                    }
                    if (!recovered) {
                        processed++
                        onProgress(processed, total)
                    }
                }
                pending = stillFailing
                val remaining = pending.size
                if (remaining == 0) {
                    onPhase2("封面已全部生成完成")
                    break
                }
                // 仅当本轮零成功且 remaining 中已无未达永久失败阈值的项（含特殊尝试已尽力）时才停止
                if (successInRound == 0) {
                    val hasRetryable = pending.any { (comic, _) -> (consecutiveFails[comic.id] ?: 0) < MAX_CONSECUTIVE_FAILS }
                    if (!hasRetryable) {
                        onPhase2("${remaining} 个封面永久获取失败（含特殊尝试），已停止重试")
                        Log.w(TAG, "phase2: 共 $remaining 个永久失败封面，停止重试")
                        break
                    }
                }
                onPhase2("第 $round 轮完成，仍有 $remaining 个封面待重试，正在重试...")
                kotlinx.coroutines.delay(1500L)
            }
            Log.d(TAG, "phase2: covers done, round=$round, remaining pending=${pending.size}")
        }

        onPhase2("扫描完成")
        Log.d(TAG, "scan complete for source '${source.name}'")
        return found.size
    }
}
