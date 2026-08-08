package com.kaguya.comicsviewer.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.util.CacheDirectories
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first

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
    private val notifications: ComicNotifications,
    private val settings: SettingsRepository
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

        // 生成封面缩略图（根据设置）
        val enableCover = runCatching { settings.settings.first().enableCoverGeneration }.getOrDefault(true)
        var coverPath: String? = null
        if (enableCover) {
            val coverFile = cacheDirs.coverFile(comicId)
            // 优先从解压目录生成封面
            val firstImage = target.listFiles()?.filter { it.isImageFile() }?.sortedBy { it.name }?.firstOrNull()
            if (firstImage != null) {
                runCatching { firstImage.readBytes() }.getOrNull()?.let { bytes ->
                    generateThumbnail(bytes, coverFile, maxWidth = 300, quality = 75)
                    coverPath = coverFile.absolutePath
                    Log.d(TAG, "doWork: cover thumbnail written from extracted, size=${coverFile.length()}")
                }
            } else {
                // 回退：从压缩包读取
                runCatching { extractor.readCover(archive) }.getOrNull()?.let { bytes ->
                    generateThumbnail(bytes, coverFile, maxWidth = 300, quality = 75)
                    coverPath = coverFile.absolutePath
                    Log.d(TAG, "doWork: cover thumbnail written from archive, size=${coverFile.length()}")
                }
            }
        } else {
            Log.d(TAG, "doWork: cover generation disabled, skipping")
        }

        // 记录总页数
        repository.updatePageCount(comicId, count)
        Log.d(TAG, "doWork: pageCount updated to $count")

        // 更新漫画封面路径
        if (coverPath != null) {
            repository.findComic(comicId)?.let { comic ->
                repository.upsertComic(comic.copy(coverPath = coverPath))
            }
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

    /**
     * 将图片字节数据压缩生成缩略图。
     */
    private fun generateThumbnail(
        imageData: ByteArray,
        outputFile: File,
        maxWidth: Int = 300,
        quality: Int = 75
    ) {
        // 先解码获取原始尺寸
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)

        // 计算采样率
        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxWidth)

        // 解码缩放后的图片
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions)
            ?: return

        // 压缩输出为 JPEG
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bitmap.recycle()
    }

    private fun calculateSampleSize(width: Int, height: Int, targetWidth: Int): Int {
        var sampleSize = 1
        if (width > targetWidth) {
            sampleSize = (width.toFloat() / targetWidth).toInt()
        }
        // 确保 sampleSize 是 2 的幂（BitmapFactory 要求）
        var power = 1
        while (power * 2 <= sampleSize) {
            power *= 2
        }
        return power.coerceAtLeast(1)
    }

    private fun File.isImageFile(): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp") ||
            n.endsWith(".gif") || n.endsWith(".bmp") ||
            n.endsWith(".avif") || n.endsWith(".heic")
    }
}
