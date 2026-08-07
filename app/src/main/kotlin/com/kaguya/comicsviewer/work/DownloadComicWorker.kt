package com.kaguya.comicsviewer.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.util.CacheDirectories
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@HiltWorker
class DownloadComicWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ComicRepository,
    private val smbClient: SmbClient,
    private val cacheDirs: CacheDirectories,
    private val notifications: ComicNotifications
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = inputData.getString("title") ?: "下载中"
        return ForegroundInfo(
            PROGRESS_NOTIF_ID,
            androidx.core.app.NotificationCompat.Builder(
                applicationContext,
                com.kaguya.comicsviewer.notification.NotificationChannels.CHANNEL_DOWNLOAD
            )
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("下载：$title")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val comicId = inputData.getLong(WorkParams.COMIC_ID, -1)
        val sourceId = inputData.getLong(WorkParams.SOURCE_ID, -1)
        if (comicId <= 0 || sourceId <= 0) return@withContext Result.failure()

        val realSource = repository.listEnabledSources().firstOrNull { it.id == sourceId }
            ?: return@withContext Result.failure()
        val title = inputData.getString("title") ?: realSource.name

        runCatching { setForeground(getForegroundInfo()) }
        setProgressAsync(workDataOf(WorkParams.PROGRESS to 0))

        val outFile = cacheDirs.archiveFile(comicId)
        if (outFile.exists()) outFile.delete()
        outFile.parentFile?.mkdirs()

        try {
            when (realSource.type) {
                ComicSourceType.LOCAL -> {
                    val localPath = inputData.getString(WorkParams.REMOTE_PATH) ?: error("local path missing")
                    val treeUri = android.net.Uri.parse(realSource.localUri)
                    val df = DocumentFile.fromTreeUri(applicationContext, treeUri)
                        ?: error("cannot open tree uri")
                    val file = findFile(df, localPath) ?: error("file not found: $localPath")
                    copyFromSaf(file.uri, outFile)
                }
                ComicSourceType.SMB -> {
                    val remote = inputData.getString(WorkParams.REMOTE_PATH) ?: error("remote path missing")
                    downloadFromSmb(realSource, remote, outFile, title)
                }
            }
            repository.upsertCache(
                ComicCache(
                    comicId = comicId,
                    state = CacheState.DOWNLOADED,
                    archiveFile = outFile.absolutePath,
                    extractedDir = null,
                    totalBytes = outFile.length(),
                    downloadedBytes = outFile.length(),
                    lastError = null
                )
            )
            setProgressAsync(workDataOf(WorkParams.PROGRESS to 100))
            Result.success(workDataOf("archivePath" to outFile.absolutePath))
        } catch (e: Exception) {
            notifications.showFailed(comicId, title, e.message)
            repository.upsertCache(
                ComicCache(
                    comicId = comicId,
                    state = CacheState.FAILED,
                    archiveFile = outFile.absolutePath.takeIf { outFile.isFile },
                    extractedDir = null,
                    totalBytes = 0,
                    downloadedBytes = 0,
                    lastError = e.message
                )
            )
            if (outFile.exists() && outFile.length() == 0L) outFile.delete()
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private suspend fun copyFromSaf(fileUri: android.net.Uri, outFile: File) = withContext(Dispatchers.IO) {
        val resolver = applicationContext.contentResolver
        var total = -1L
        var copied = 0L
        runCatching {
            resolver.openAssetFileDescriptor(fileUri, "r")?.use { afd -> total = afd.length }
        }
        resolver.openInputStream(fileUri)?.use { input ->
            FileOutputStream(outFile).use { out ->
                val buf = ByteArray(128 * 1024)
                var lastPercent = -1
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (total > 0) {
                        val percent = ((copied * 100) / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            setProgressAsync(workDataOf(
                                WorkParams.PROGRESS to percent,
                                WorkParams.DOWNLOADED_BYTES to copied,
                                WorkParams.TOTAL_BYTES to total
                            ))
                            notifications.showProgress(inputData.getLong(WorkParams.COMIC_ID, 0), inputData.getString("title") ?: "", percent, total, copied)
                        }
                    }
                }
                out.flush()
            }
        }
    }

    private suspend fun downloadFromSmb(
        source: com.kaguya.comicsviewer.domain.model.ComicSource,
        remote: String,
        outFile: File,
        title: String
    ) = withContext(Dispatchers.IO) {
        val session = smbClient.open(source)
        try {
            val total = runCatching { smbClient.getFileSize(session, remote) }.getOrDefault(-1L)
            var copied = 0L
            var lastPercent = -1
            smbClient.openInput(session, remote).use { input ->
                FileOutputStream(outFile).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                setProgress(workDataOf(
                                    WorkParams.PROGRESS to percent,
                                    WorkParams.DOWNLOADED_BYTES to copied,
                                    WorkParams.TOTAL_BYTES to total
                                ))
                                notifications.showProgress(inputData.getLong(WorkParams.COMIC_ID, 0), title, percent, total, copied)
                            }
                        }
                    }
                }
            }
        } finally {
            session.close()
        }
    }

    private fun findFile(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split('/').filter { it.isNotBlank() }
        var cur: DocumentFile? = root
        for ((idx, p) in parts.withIndex()) {
            val isLast = idx == parts.lastIndex
            cur = cur?.listFiles()?.firstOrNull { it.name == p } ?: return null
            if (isLast) return cur
        }
        return cur
    }

    companion object {
        const val PROGRESS_NOTIF_ID = 1001
    }
}
