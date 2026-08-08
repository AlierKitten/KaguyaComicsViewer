package com.kaguya.comicsviewer.ui.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.usecase.DownloadComicUseCase
import com.kaguya.comicsviewer.domain.usecase.ScanSourceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val isScanning: Boolean = false,
    val comics: List<ComicRow> = emptyList(),
    val recent: List<ComicRow> = emptyList(),
    val query: String = ""
)

data class ComicRow(
    val comic: Comic,
    val cache: ComicCache?,
    val progress: Int
)

data class LoadingProgress(
    val comicId: Long,
    val title: String,
    val state: CacheState,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: ComicRepository,
    private val scanUseCase: ScanSourceUseCase,
    private val downloadUseCase: DownloadComicUseCase,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val query = MutableStateFlow(savedState.get<String>("q").orEmpty())
    private val scanning = MutableStateFlow(false)
    private val sourceIds = repository.observeSources().map { srcs -> srcs.filter { it.enabled }.map { it.id } }

    // 当前正在加载的漫画进度
    private val _loadingProgress = MutableStateFlow<LoadingProgress?>(null)
    val loadingProgress: StateFlow<LoadingProgress?> = _loadingProgress.asStateFlow()

    // 用 flatMapLatest + combine 为每个 comic 附加 cache + progress
    private val comicsFlow = sourceIds.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList<ComicRow>())
        else repository.observeComicsBySources(ids).flatMapLatest { comics ->
            if (comics.isEmpty()) flowOf(emptyList<ComicRow>())
            else {
                val rowFlows = comics.map { comic ->
                    combine(
                        repository.observeCache(comic.id),
                        repository.observeProgress(comic.id)
                    ) { cache, progress ->
                        ComicRow(comic = comic, cache = cache, progress = progress?.page ?: 0)
                    }
                }
                combine(rowFlows) { it.toList() }
            }
        }
    }

    private val recentFlow = repository.observeRecent(6).flatMapLatest { comics ->
        if (comics.isEmpty()) flowOf(emptyList<ComicRow>())
        else {
            val rowFlows = comics.map { comic ->
                combine(
                    repository.observeCache(comic.id),
                    repository.observeProgress(comic.id)
                ) { cache, progress ->
                    ComicRow(comic = comic, cache = cache, progress = progress?.page ?: 0)
                }
            }
            combine(rowFlows) { it.toList() }
        }
    }

    val state: StateFlow<LibraryUiState> = combine(
        comicsFlow, recentFlow, query, scanning
    ) { comics, recent, q, sc ->
        val filtered = if (q.isBlank()) comics else comics.filter {
            it.comic.title.contains(q, ignoreCase = true)
        }
        LibraryUiState(isScanning = sc, comics = filtered, recent = recent, query = q)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun setQuery(q: String) {
        query.value = q
        savedState["q"] = q
    }

    fun scanAll() {
        viewModelScope.launch {
            scanning.value = true
            try {
                val sources = repository.listEnabledSources()
                for (s in sources) scanUseCase(s)
            } finally {
                scanning.value = false
            }
        }
    }

    fun scanSource(sourceId: Long) {
        viewModelScope.launch {
            scanning.value = true
            try {
                val sources = repository.listEnabledSources()
                sources.firstOrNull { it.id == sourceId }?.let { scanUseCase(it) }
            } finally {
                scanning.value = false
            }
        }
    }

    /** 开始加载漫画，并追踪进度 */
    fun startLoading(comicId: Long, title: String) {
        Log.d("LibraryViewModel", "startLoading: comicId=$comicId, title='$title'")
        _loadingProgress.value = LoadingProgress(
            comicId = comicId,
            title = title,
            state = CacheState.PENDING
        )

        // 观察缓存状态变化来更新进度
        viewModelScope.launch {
            repository.observeCache(comicId).collectLatest { cache ->
                val c = cache ?: return@collectLatest
                Log.d("LibraryViewModel", "cache update: comicId=$comicId, state=${c.state}")
                _loadingProgress.value = LoadingProgress(
                    comicId = comicId,
                    title = title,
                    state = c.state,
                    progressPercent = if (c.totalBytes > 0) ((c.downloadedBytes * 100) / c.totalBytes).toInt() else 0,
                    downloadedBytes = c.downloadedBytes,
                    totalBytes = c.totalBytes,
                    error = c.lastError
                )
            }
        }

        // 触发下载
        viewModelScope.launch {
            try {
                downloadUseCase(comicId)
                Log.d("LibraryViewModel", "download scheduled: comicId=$comicId")
            } catch (e: Exception) {
                Log.e("LibraryViewModel", "download failed: comicId=$comicId", e)
                _loadingProgress.value = _loadingProgress.value?.copy(
                    state = CacheState.FAILED,
                    error = e.message
                )
            }
        }
    }

    /** 关闭加载进度弹窗 */
    fun dismissLoading() {
        _loadingProgress.value = null
    }

    /** 清除所有阅读记录 */
    fun clearProgress() {
        viewModelScope.launch {
            repository.clearAllProgress()
        }
    }
}
