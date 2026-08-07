package com.kaguya.comicsviewer.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 调度下载 + 解压的链式工作。 */
@Singleton
class ComicWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ComicWorkScheduler"
    }

    fun scheduleDownloadAndExtract(comic: Comic, source: ComicSource) {
        Log.d(TAG, "scheduleDownloadAndExtract: comicId=${comic.id}, title='${comic.title}', sourceId=${source.id}, type=${source.type}")
        val downloadInput = workDataOf(
            WorkParams.COMIC_ID to comic.id,
            WorkParams.SOURCE_ID to source.id,
            WorkParams.REMOTE_PATH to comic.filePath,
            "title" to comic.title
        )
        Log.d(TAG, "downloadInput: comicId=${comic.id}, sourceId=${source.id}, remotePath='${comic.filePath}'")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (source.host != null) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .setRequiresStorageNotLow(true)
            .build()
        Log.d(TAG, "constraints: networkType=${if (source.host != null) "CONNECTED" else "NOT_REQUIRED"}")

        val download = OneTimeWorkRequestBuilder<DownloadComicWorker>()
            .setConstraints(constraints)
            .setInputData(downloadInput)
            .addTag(tagFor(comic.id))
            .build()

        val extract = OneTimeWorkRequestBuilder<ExtractComicWorker>()
            .setInputData(
                workDataOf(
                    WorkParams.COMIC_ID to comic.id,
                    "title" to comic.title
                )
            )
            .addTag(tagFor(comic.id))
            .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(workName(comic.id), ExistingWorkPolicy.REPLACE, download)
            .then(extract)
            .enqueue()
        Log.d(TAG, "WorkManager chain enqueued: download->extract for comicId=${comic.id}")
    }

    fun cancel(comicId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(comicId))
    }

    private fun workName(comicId: Long) = "${WorkParams.UNIQUE_PREFIX}$comicId"
    fun tagFor(comicId: Long) = "comic-$comicId"
}
