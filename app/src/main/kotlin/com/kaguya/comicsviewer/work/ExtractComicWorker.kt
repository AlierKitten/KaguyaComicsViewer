package com.kaguya.comicsviewer.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.notification.NotificationChannels
import com.kaguya.comicsviewer.util.CacheDirectories
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = inputData.getString("title") ?: "解压中"
        return ForegroundInfo(
            1002,
            NotificationCompat.Builder(
                applicationContext,
                NotificationChannels.CHANNEL_DOWNLOAD
            )
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("解压：$title")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val comicId = inputData.getLong(WorkParams.COMIC_ID, -1)
        val archivePath = inputData.getString("archivePath") ?: return@withContext Result.failure()
        val title = repository.findComic(comicId)?.title ?: inputData.getString("title") ?: ""

        runCatching { setForeground(getForegroundInfo()) }

        val archive = File(archivePath)
        if (!archive.isFile) return@withContext Result.failure(workDataOf(WorkParams.ERROR to "archive missing"))

        val target = cacheDirs.extractedDir(comicId)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

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

        val count = runCatching { extractor.extractImages(archive, target) }
            .onFailure { e ->
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
                return@withContext Result.failure()
            }.getOrDefault(0)

        if (count == 0) {
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
            return@withContext Result.failure()
        }

        // 生成封面
        val coverFile = cacheDirs.coverFile(comicId)
        runCatching { extractor.readCover(archive) }.getOrNull()?.let { bytes ->
            coverFile.writeBytes(bytes)
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
        Result.success(workDataOf("extractedDir" to target.absolutePath, "pageCount" to count))
    }
}
