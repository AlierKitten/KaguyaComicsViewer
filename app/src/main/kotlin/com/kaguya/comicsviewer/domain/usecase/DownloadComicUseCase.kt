package com.kaguya.comicsviewer.domain.usecase

import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.work.ComicWorkScheduler
import javax.inject.Inject

/** 触发一本漫画的下载与解压链。 */
class DownloadComicUseCase @Inject constructor(
    private val repository: ComicRepository,
    private val scheduler: ComicWorkScheduler
) {
    suspend operator fun invoke(comicId: Long) {
        val comic = repository.findComic(comicId) ?: return
        val sources = repository.listEnabledSources()
        val source = sources.firstOrNull { it.id == comic.sourceId } ?: return
        scheduler.scheduleDownloadAndExtract(comic, source)
    }
}

class CancelDownloadUseCase @Inject constructor(
    private val scheduler: ComicWorkScheduler
) {
    operator fun invoke(comicId: Long) = scheduler.cancel(comicId)
}
