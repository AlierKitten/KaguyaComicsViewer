package com.kaguya.comicsviewer.domain.usecase

import android.util.Log
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.LocalFileScanner
import com.kaguya.comicsviewer.data.source.SmbFileScanner
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.util.CacheDirectories
import java.io.FileOutputStream
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
        onPhase2: (String) -> Unit = {}
    ): Int {
        Log.d(TAG, "scan start: source='${source.name}', type=${source.type}")
        val scanner = when (source.type) {
            ComicSourceType.LOCAL -> localScanner
            ComicSourceType.SMB -> smbScanner
        }

        // ── Phase 1: 快速索引 ──
        val found = try {
            scanner.scan(source)
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
        val needsSize = found.filter { it.sizeBytes == 0L }
        val needsCover = found.filter { d ->
            val prev = existingByPath[d.relativePath]
            prev?.coverPath == null
        }

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

        // 2b. 生成封面
        if (needsCover.isNotEmpty()) {
            onPhase2("正在生成封面共 (${needsCover.size} 个漫画)...")
            var coverCount = 0
            // 一次性获取当前数据库中的漫画
            val comicsByPath = repository.listComicsBySources(listOf(source.id)).associateBy { it.filePath }
            for (d in needsCover) {
                val comic = comicsByPath[d.relativePath] ?: continue
                if (comic.coverPath != null) continue
                try {
                    val coverBytes = scanner.cover(source, d) ?: continue
                    val coverFile = cacheDirs.coverFile(comic.id)
                    coverFile.parentFile?.mkdirs()
                    FileOutputStream(coverFile).use { it.write(coverBytes) }
                    repository.upsertComic(comic.copy(coverPath = coverFile.absolutePath))
                    coverCount++
                    Log.d(TAG, "phase2: cover generated for comicId=${comic.id}, title='${d.title}'")
                } catch (e: Exception) {
                    Log.w(TAG, "phase2: cover generation failed for '${d.title}': ${e.message}")
                }
            }
            Log.d(TAG, "phase2: generated $coverCount covers")
        }

        onPhase2("扫描完成")
        Log.d(TAG, "scan complete for source '${source.name}'")
        return found.size
    }
}
