package com.kaguya.comicsviewer.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.util.CacheDirectories
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * 解压 Worker：解压下载好的压缩包到缓存目录，并写封面。
 */
@HiltWorker
class ExtractComicWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ComicRepository,
    private val extractor: ArchiveExtractor,
    private val cacheDirs: CacheDirectories,
    private val notifications: ComicNotifications
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ExtractComicWorker"
    }

    override suspend fun doWork(): Result {
        val comicId = inputData.getLong(WorkParams.COMIC_ID, -1)
        val archivePath = inputData.getString("archivePath")
        if (archivePath.isNullOrBlank()) {
            Log.e(TAG, "doWork: archivePath is null")
            return Result.failure()
        }
        val title = repository.findComic(comicId)?.title ?: inputData.getString("title") ?: ""
        Log.d(TAG, "doWork started: comicId=$comicId, title='$title', archivePath='$archivePath'")

        val archive = File(archivePath)
        if (!archive.isFile) {
            Log.e(TAG, "doWork: archive file not found: $archivePath")
            return Result.failure(workDataOf(WorkParams.ERROR to "archive missing"))
        }
        Log.d(TAG, "doWork: archive size=${archive.length()}")

        val target = cacheDirs.extractedDir(comicId)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        Log.d(TAG, "doWork: target dir=${target.absolutePath}")

        // 标记为解压中
        repository.upsertCache(
            ComicCache(
                comicId = comicId,
                state = CacheState.EXTRACTING,
                archiveFile = archivePath,
                extractedDir = null,
                totalBytes = archive.length(),
                downloadedBytes = archive.length(),
                lastError = null
            )
        )

        val extractResult = runCatching { extractor.extractImages(archive, target) }
        if (extractResult.isFailure) {
            val e = extractResult.exceptionOrNull()!!
            Log.e(TAG, "doWork: extract failed", e)
            notifications.showFailed(comicId, title, e.message)
            repository.upsertCache(
                ComicCache(
                    comicId = comicId,
                    state = CacheState.FAILED,
                    archiveFile = archivePath,
                    extractedDir = null,
                    totalBytes = archive.length(),
                    downloadedBytes = archive.length(),
                    lastError = e.message
                )
            )
            return Result.failure()
        }
        val count = extractResult.getOrDefault(0)

        Log.d(TAG, "doWork: extracted $count images")

        if (count == 0) {
            Log.w(TAG, "doWork: no images found in archive")
            notifications.showFailed(comicId, title, "未找到可读取的图片")
            repository.upsertCache(
                ComicCache(
                    comicId = comicId,
                    state = CacheState.FAILED,
                    archiveFile = archivePath,
                    extractedDir = target.absolutePath,
                    totalBytes = archive.length(),
                    downloadedBytes = archive.length(),
                    lastError = "no images"
                )
            )
            return Result.failure()
        }

        // 生成封面
        val coverFile = cacheDirs.coverFile(comicId)
        runCatching { extractor.readCover(archive) }.getOrNull()?.let { bytes ->
            coverFile.writeBytes(bytes)
            Log.d(TAG, "doWork: cover written, size=${bytes.size}")
        }

        // 标记为就绪
        repository.upsertCache(
            ComicCache(
                comicId = comicId,
                state = CacheState.READY,
                archiveFile = archivePath,
                extractedDir = target.absolutePath,
                totalBytes = archive.length(),
                downloadedBytes = archive.length(),
                lastError = null
            )
        )
        notifications.showReady(comicId, title)
        Log.d(TAG, "doWork: success, pages=$count")
        return Result.success(workDataOf("extractedDir" to target.absolutePath, "pageCount" to count))
    }
}
