package com.kaguya.comicsviewer.domain.usecase

import android.util.Log
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.work.ComicWorkScheduler
import javax.inject.Inject

/** 触发一本漫画的下载与解压链。 */
class DownloadComicUseCase @Inject constructor(
    private val repository: ComicRepository,
    private val scheduler: ComicWorkScheduler
) {
    companion object {
        private const val TAG = "DownloadComicUseCase"
    }

    suspend operator fun invoke(comicId: Long) {
        Log.d(TAG, "invoke: comicId=$comicId")
        val comic = repository.findComic(comicId)
        if (comic == null) {
            Log.w(TAG, "invoke: comic not found for id=$comicId")
            return
        }
        Log.d(TAG, "invoke: found comic '${comic.title}', sourceId=${comic.sourceId}, filePath=${comic.filePath}")
        val sources = repository.listEnabledSources()
        val source = sources.firstOrNull { it.id == comic.sourceId }
        if (source == null) {
            Log.w(TAG, "invoke: source not found for sourceId=${comic.sourceId}")
            return
        }
        Log.d(TAG, "invoke: scheduling download for '${comic.title}', source=${source.name}, type=${source.type}, localUri=${source.localUri}")
        scheduler.scheduleDownloadAndExtract(comic, source)
        Log.d(TAG, "invoke: download scheduled successfully")
    }
}

class CancelDownloadUseCase @Inject constructor(
    private val scheduler: ComicWorkScheduler
) {
    operator fun invoke(comicId: Long) = scheduler.cancel(comicId)
}
