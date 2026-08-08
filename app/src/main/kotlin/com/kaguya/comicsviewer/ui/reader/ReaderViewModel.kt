package com.kaguya.comicsviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.kaguya.comicsviewer.util.CacheDirectories
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    private val cacheDirs: CacheDirectories,
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

            // 检查封面文件是否存在，不存在则重新生成
            val updatedComic = ensureCover(comic, list)

            _state.value = ReaderUiState(
                comic = updatedComic, pages = list, page = page, mode = s.readingMode,
                keepScreenOn = s.keepScreenOn, isLoading = false, error = null
            )
        }
    }

    /** 如果封面文件不存在，从解压目录的第一页重新生成 */
    private suspend fun ensureCover(comic: Comic?, pages: List<ComicPage>): Comic? {
        if (comic == null || pages.isEmpty()) return comic
        val coverPath = comic.coverPath
        if (coverPath != null && File(coverPath).exists()) return comic
        // 封面不存在，从第一页生成缩略图
        val firstPage = pages.firstOrNull() ?: return comic
        val firstPageFile = File(firstPage.path)
        if (!firstPageFile.exists()) return comic
        return withContext(Dispatchers.IO) {
            runCatching {
                val coverFile = cacheDirs.coverFile(comicId)
                val bytes = firstPageFile.readBytes()
                generateThumbnail(bytes, coverFile)
                val newCoverPath = coverFile.absolutePath
                val updated = comic.copy(coverPath = newCoverPath)
                repository.upsertComic(updated)
                Log.d("ReaderViewModel", "regenerated cover: $newCoverPath")
                updated
            }.getOrDefault(comic)
        }
    }

    private fun generateThumbnail(imageData: ByteArray, outputFile: File, maxWidth: Int = 300, quality: Int = 75) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
        var sampleSize = 1
        if (options.outWidth > maxWidth) {
            sampleSize = (options.outWidth.toFloat() / maxWidth).toInt()
        }
        var power = 1
        while (power * 2 <= sampleSize) power *= 2
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = power }
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions) ?: return
        FileOutputStream(outputFile).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
        bitmap.recycle()
    }

    fun goTo(p: Int) {
        val max = (_state.value.pages.size - 1).coerceAtLeast(0)
        val page = p.coerceIn(0, max)
        _state.value = _state.value.copy(page = page)
        // 读取到最后一页或倒数第二页都算已读完
        val isFinished = page >= (max - 1).coerceAtLeast(0) && max > 0
        viewModelScope.launch { saveProgress(comicId, page, isFinished) }
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
