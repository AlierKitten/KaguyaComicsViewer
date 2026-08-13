package com.kaguya.comicsviewer.domain.usecase

import android.util.Log
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.domain.model.ComicSourceType
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
            ?: throw IllegalStateException("漫画不存在: id=$comicId")
        Log.d(TAG, "invoke: found comic '${comic.title}', sourceId=${comic.sourceId}, filePath=${comic.filePath}")
        val sources = repository.listEnabledSources()
        val source = sources.firstOrNull { it.id == comic.sourceId }
            ?: throw IllegalStateException("文件源不存在或已禁用: sourceId=${comic.sourceId}")
        Log.d(TAG, "invoke: scheduling download for '${comic.title}', source=${source.name}, type=${source.type}, localUri=${source.localUri}")

        // 本地源：扫描阶段已标记 READY，阅读时按需从 SAF 流单页解压（整包零落盘），无需任何 Worker。
        if (source.type == ComicSourceType.LOCAL) {
            Log.d(TAG, "invoke: local source ZIP already READY, no download/extract worker needed")
            return
        }

        scheduler.scheduleDownloadAndExtract(comic, source)
        Log.d(TAG, "invoke: download scheduled successfully")
    }
}

class CancelDownloadUseCase @Inject constructor(
    private val scheduler: ComicWorkScheduler
) {
    operator fun invoke(comicId: Long) = scheduler.cancel(comicId)
}
