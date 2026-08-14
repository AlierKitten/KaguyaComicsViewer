package com.kaguya.comicsviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.cache.PageImageCache
import com.kaguya.comicsviewer.data.prefs.AppSettings
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicPage
import com.kaguya.comicsviewer.domain.model.ReadingMode
import com.kaguya.comicsviewer.domain.usecase.SaveProgressUseCase
import com.kaguya.comicsviewer.util.CacheDirectories
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val pageCache: PageImageCache,
    private val extractor: ArchiveExtractor,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val comicId: Long = savedState.get<Long>("comicId") ?: 0L

    private val _state = MutableStateFlow(ReaderUiState())

    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** 一次性跳转事件：Screen 消费后滚动到该页。 */
    private val _jumpEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val jumpEvent = _jumpEvent.asSharedFlow()

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
            // listPages 对本地源会顺序扫描整个 SAF 流，耗时较长；用 NonCancellable 保护，
            // 避免阅读器协程因配置变更/重组被取消而中断扫描（否则会得到空页列表 → "未找到页面"）。
            val list = withContext(NonCancellable) { repository.listPages(comicId) }
            val s: AppSettings = runCatching { settings.settings.first() }
                .getOrDefault(AppSettings(ReadingMode.PAGED, keepScreenOn = true, autoMarkRead = false))
            val initialPage = runCatching { repository.observeProgress(comicId).first()?.page ?: 0 }
                .getOrDefault(0)
            val page = initialPage.coerceIn(0, (list.size - 1).coerceAtLeast(0))

            // 检查封面文件是否存在，不存在则重新生成
            val updatedComic = ensureCover(comic, list)

            // SMB 源扫描阶段未计算页数（pageCount=0），阅读器打开后这里把实时列举出的
            // 总页数写回数据库，保证"继续阅读"等列表页能显示正确总页数而非 "1/? 页"。
            if (list.isNotEmpty() && comic != null && comic.pageCount != list.size) {
                runCatching { repository.updatePageCount(comicId, list.size) }
            }

            _state.value = ReaderUiState(
                comic = updatedComic, pages = list, page = page, mode = s.readingMode,
                keepScreenOn = s.keepScreenOn, isLoading = false, error = null
            )
            // 预取附近页
            prefetch(page)
        }
    }

    /** 如果封面文件不存在，从第一页重新生成（ZIP 先从压缩包解出第一页字节）。 */
    private suspend fun ensureCover(comic: Comic?, pages: List<ComicPage>): Comic? {
        if (comic == null || pages.isEmpty()) return comic
        val coverPath = comic.coverPath
        if (coverPath != null && File(coverPath).exists()) return comic
        val firstPage = pages.firstOrNull() ?: return comic
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                if (firstPage.path != null) {
                    File(firstPage.path).takeIf { it.exists() }?.readBytes()
                } else if (firstPage.isArchive) {
                    val ap = firstPage.archivePath!!
                    if (ap.startsWith("saf:")) {
                        // 本地源 SAF 流直读封面（第一页），不依赖压缩包真实路径。
                        val streamComicId = runCatching { ap.removePrefix("saf:").toLong() }.getOrNull()
                            ?: comic.id
                        val pair = repository.openArchiveStream(streamComicId)
                        if (pair != null) {
                            val (input, _) = pair
                            val b = runCatching {
                                extractor.readEntryStream(input, firstPage.entryName!!)
                            }.getOrNull()
                            runCatching { input.close() }
                            b
                        } else null
                    } else {
                        extractor.readEntry(
                            archive = File(ap),
                            entryName = firstPage.entryName!!
                        )
                    }
                } else null
            }.getOrNull()
        } ?: return comic
        return withContext(Dispatchers.IO) {
            runCatching {
                val coverFile = cacheDirs.coverFile(comicId)
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
        prefetch(page)
    }

    /** 页面跳转：更新页码并发出一次性跳转事件，由 Screen 滚动到目标页。 */
    fun jumpTo(p: Int) {
        val max = (_state.value.pages.size - 1).coerceAtLeast(0)
        val page = p.coerceIn(0, max)
        _state.value = _state.value.copy(page = page)
        val isFinished = page >= (max - 1).coerceAtLeast(0) && max > 0
        viewModelScope.launch { saveProgress(comicId, page, isFinished) }
        _jumpEvent.tryEmit(page)
        prefetch(page)
    }

    /** 后台预取当前页附近的 ZIP 页到磁盘缓存。 */
    private fun prefetch(page: Int) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            pageCache.prefetch(comicId, page, pages)
        }
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
