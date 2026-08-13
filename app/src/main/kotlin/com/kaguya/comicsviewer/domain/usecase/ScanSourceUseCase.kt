package com.kaguya.comicsviewer.domain.usecase

import android.util.Log
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.DiscoveredComic
import com.kaguya.comicsviewer.data.source.LocalFileScanner
import com.kaguya.comicsviewer.data.source.SmbFileScanner
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.util.CacheDirectories
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** 扫描一个源：发现所有漫画并同步到数据库。 */
class ScanSourceUseCase @Inject constructor(
    private val repository: ComicRepository,
    private val localScanner: LocalFileScanner,
    private val smbScanner: SmbFileScanner,
    private val cacheDirs: CacheDirectories
) {
    companion object {
        private const val TAG = "ScanSourceUseCase"
    }

    /** 取消标志：由调用方在「开始一次扫描任务」前调用 resetCancellation() 清零；调用 cancel() 置为 true 后扫描循环应尽快中止（已写入的数据保留）。 */
    private val cancelled = AtomicBoolean(false)

    /** 重置取消标志（应在开始一次新的扫描任务时调用，不要在循环的每个源之前调用，否则会清除正在进行的取消请求）。 */
    fun resetCancellation() {
        cancelled.set(false)
    }

    /** 请求中止当前正在进行的扫描。 */
    fun cancel() {
        cancelled.set(true)
    }

    /** 当前是否请求中止。 */
    fun isCancelled(): Boolean = cancelled.get()
    /**
     * 两阶段扫描：
     * 1. 快速索引所有漫画名（立即写入数据库，UI 可显示）
     * 2. 后台获取文件大小 + 生成封面
     *
     * @param onPhase1 第一阶段完成时回调（参数为发现的漫画数）
     * @param onPhase2 第二阶段进度回调（参数为进度文本）
     */
    suspend operator fun invoke(
        source: ComicSource,
        onPhase1: (Int) -> Unit = {},
        onPhase2: (String) -> Unit = {},
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

        for (d in found) {
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
        }

        repository.removeComicsNotIn(source.id, keepIds)
        repository.markSourceScanned(source.id, now)
        Log.d(TAG, "phase1 complete: upserted=${found.size}")

        // 通知 UI 第一阶段完成
        onPhase1(found.size)

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
                }
                Log.d(TAG, "phase2: fetched ${sizes.size} file sizes")
            } catch (e: Exception) {
                Log.w(TAG, "phase2: fetchSizes failed", e)
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
