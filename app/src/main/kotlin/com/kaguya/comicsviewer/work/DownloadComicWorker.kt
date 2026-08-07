package com.kaguya.comicsviewer.work

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
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

    companion object {
        private const val TAG = "DownloadComicWorker"
    }

    override suspend fun doWork(): Result {
        val comicId = inputData.getLong(WorkParams.COMIC_ID, -1)
        val sourceId = inputData.getLong(WorkParams.SOURCE_ID, -1)
        Log.d(TAG, "doWork started: comicId=$comicId, sourceId=$sourceId")
        if (comicId <= 0 || sourceId <= 0) {
            Log.e(TAG, "doWork: invalid comicId or sourceId")
            return Result.failure()
        }

        val realSource = repository.listEnabledSources().firstOrNull { it.id == sourceId }
        if (realSource == null) {
            Log.e(TAG, "doWork: source not found for id=$sourceId")
            return Result.failure()
        }
        val title = inputData.getString("title") ?: realSource.name
        Log.d(TAG, "doWork: title='$title', type=${realSource.type}, localUri=${realSource.localUri}")

        val outFile = cacheDirs.archiveFile(comicId, inputData.getString(WorkParams.REMOTE_PATH))
        if (outFile.exists()) outFile.delete()
        outFile.parentFile?.mkdirs()
        Log.d(TAG, "doWork: output file = ${outFile.absolutePath}")

        return try {
            when (realSource.type) {
                ComicSourceType.LOCAL -> {
                    val localPath = inputData.getString(WorkParams.REMOTE_PATH)
                    if (localPath.isNullOrBlank()) {
                        Log.e(TAG, "doWork: localPath is null or blank")
                        return Result.failure()
                    }
                    Log.d(TAG, "doWork: localPath='$localPath', treeUri='${realSource.localUri}'")
                    val treeUri = android.net.Uri.parse(realSource.localUri)
                    val df = DocumentFile.fromTreeUri(applicationContext, treeUri)
                    if (df == null) {
                        Log.e(TAG, "doWork: cannot open tree uri: ${realSource.localUri}")
                        throw IllegalStateException("无法访问本地文件夹，请重新添加文件源")
                    }
                    Log.d(TAG, "doWork: DocumentFile root uri=${df.uri}, name=${df.name}")
                    val file = findFile(df, localPath)
                    if (file == null) {
                        Log.e(TAG, "doWork: file not found in SAF: $localPath")
                        throw IllegalStateException("找不到文件: $localPath")
                    }
                    Log.d(TAG, "doWork: found file, uri=${file.uri}, size=${file.length()}")
                    withContext(Dispatchers.IO) { copyFromSaf(file.uri, outFile) }
                }
                ComicSourceType.SMB -> {
                    val remote = inputData.getString(WorkParams.REMOTE_PATH)
                    if (remote.isNullOrBlank()) {
                        Log.e(TAG, "doWork: remote path is null or blank")
                        return Result.failure()
                    }
                    withContext(Dispatchers.IO) { downloadFromSmb(realSource, remote, outFile, title) }
                }
            }
            Log.d(TAG, "doWork: download complete, file size=${outFile.length()}")
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
            Log.d(TAG, "doWork: success")
            Result.success(workDataOf("archivePath" to outFile.absolutePath))
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed", e)
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

}
