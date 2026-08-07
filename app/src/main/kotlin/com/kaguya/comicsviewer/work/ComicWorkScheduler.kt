package com.kaguya.comicsviewer.work

import android.content.Context
import androidx.work.Constraints
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
    fun scheduleDownloadAndExtract(comic: Comic, source: ComicSource) {
        val downloadInput = workDataOf(
            WorkParams.COMIC_ID to comic.id,
            WorkParams.SOURCE_ID to source.id,
            WorkParams.REMOTE_PATH to comic.filePath,
            "title" to comic.title
        )
        val download = OneTimeWorkRequestBuilder<DownloadComicWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (source.host != null) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
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
            .beginWith(download)
            .then(extract)
            .enqueue()
    }

    fun cancel(comicId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(comicId))
    }

    private fun workName(comicId: Long) = "${WorkParams.UNIQUE_PREFIX}$comicId"
    fun tagFor(comicId: Long) = "comic-$comicId"
}
