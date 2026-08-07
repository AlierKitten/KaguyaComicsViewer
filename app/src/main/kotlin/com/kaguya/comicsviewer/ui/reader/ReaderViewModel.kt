package com.kaguya.comicsviewer.ui.reader

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.prefs.AppSettings
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicPage
import com.kaguya.comicsviewer.domain.model.ReadingMode
import com.kaguya.comicsviewer.domain.usecase.SaveProgressUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val comic: Comic? = null,
    val pages: List<ComicPage> = emptyList(),
    val page: Int = 0,
    val mode: ReadingMode = ReadingMode.PAGED,
    val keepScreenOn: Boolean = true,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: ComicRepository,
    private val settings: SettingsRepository,
    private val saveProgress: SaveProgressUseCase,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val comicId: Long = savedState.get<Long>("comicId") ?: 0L

    private val _state = MutableStateFlow(ReaderUiState())

    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("ReaderViewModel", "Error loading comic", throwable)
        _state.value = _state.value.copy(isLoading = false, error = throwable.message ?: "未知错误")
    }

    init {
        loadComic()
    }

    private fun loadComic() {
        viewModelScope.launch(errorHandler) {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val comic = repository.findComic(comicId)
            val list = repository.listPages(comicId)
            val s: AppSettings = runCatching { settings.settings.first() }
                .getOrDefault(AppSettings(ReadingMode.PAGED, keepScreenOn = true, autoMarkRead = false))
            val initialPage = runCatching { repository.observeProgress(comicId).first()?.page ?: 0 }
                .getOrDefault(0)
            val page = initialPage.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            _state.value = ReaderUiState(
                comic = comic, pages = list, page = page, mode = s.readingMode,
                keepScreenOn = s.keepScreenOn, isLoading = false, error = null
            )
        }
    }

    fun goTo(p: Int) {
        val max = (_state.value.pages.size - 1).coerceAtLeast(0)
        val page = p.coerceIn(0, max)
        _state.value = _state.value.copy(page = page)
        viewModelScope.launch { saveProgress(comicId, page) }
    }

    fun next() = goTo(_state.value.page + 1)
    fun prev() = goTo(_state.value.page - 1)

    fun setMode(mode: ReadingMode) {
        viewModelScope.launch {
            settings.setReadingMode(mode)
            val s = runCatching { settings.settings.first() }
                .getOrDefault(AppSettings(ReadingMode.PAGED, keepScreenOn = true, autoMarkRead = false))
            _state.value = _state.value.copy(mode = s.readingMode, keepScreenOn = s.keepScreenOn)
        }
    }

    fun retry() = loadComic()
}
