package com.kaguya.comicsviewer.domain.usecase

import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.domain.model.ReadingProgress
import javax.inject.Inject

class SaveProgressUseCase @Inject constructor(
    private val repository: ComicRepository
) {
    suspend operator fun invoke(comicId: Long, page: Int, isFinished: Boolean = false) {
        if (page < 0) return
        repository.saveProgress(
            ReadingProgress(comicId = comicId, page = page, updatedAt = System.currentTimeMillis(), isFinished = isFinished)
        )
    }
}
