package com.kaguya.comicsviewer.ui.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicSortField
import com.kaguya.comicsviewer.domain.usecase.CancelDownloadUseCase
import com.kaguya.comicsviewer.domain.usecase.DownloadComicUseCase
import com.kaguya.comicsviewer.domain.usecase.ScanSourceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryDisplayMode { GRID, LIST }

data class LibraryUiState(
    val isScanning: Boolean = false,
    val comics: List<ComicRow> = emptyList(),
    val recent: List<ComicRow> = emptyList(),
    val query: String = "",
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.GRID,
    val showCovers: Boolean = true,
    val sortField: ComicSortField = ComicSortField.NAME,
    val sortAscending: Boolean = true
)

private data class DisplaySortPrefs(
    val displayMode: LibraryDisplayMode,
    val showCovers: Boolean,
    val sortField: ComicSortField,
    val sortAscending: Boolean
)

data class ComicRow(
    val comic: Comic,
    val cache: ComicCache?,
    val progress: Int,
    val isFinished: Boolean = false
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
    private val cancelDownloadUseCase: CancelDownloadUseCase,
    private val settings: SettingsRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val scanning = MutableStateFlow(false)
    private val displayMode = MutableStateFlow(LibraryDisplayMode.GRID)
    private val showCovers = MutableStateFlow(true)
    private val sortField = MutableStateFlow(ComicSortField.NAME)
    private val sortAscending = MutableStateFlow(true)

    init {
        // 监听设置变化
        viewModelScope.launch {
            settings.settings.collect { s ->
                displayMode.value = LibraryDisplayMode.entries[s.libraryDisplayMode]
                showCovers.value = s.showCovers
                sortField.value = s.sortField
                sortAscending.value = s.sortAscending
            }
        }
    }
    private val sourceIds = repository.observeSources().map { srcs -> srcs.filter { it.enabled }.map { it.id } }

    // 当前正在加载的漫画进度
    private val _loadingProgress = MutableStateFlow<LoadingProgress?>(null)
    val loadingProgress: StateFlow<LoadingProgress?> = _loadingProgress.asStateFlow()

    // Toast 一次性事件
    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvents = _toastEvents.asSharedFlow()

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
                        ComicRow(comic = comic, cache = cache, progress = progress?.page ?: 0, isFinished = progress?.isFinished ?: false)
                    }
                }
                combine(rowFlows) { it.toList() }
            }
        }
    }

    private val recentFlow = combine(
        repository.observeLoading(),
        repository.observeRecent(6)
    ) { loading, recent ->
        // 加载中的漫画排在前面，合并后去重
        val loadingIds = loading.map { it.id }.toSet()
        val merged = loading + recent.filter { it.id !in loadingIds }
        merged
    }.flatMapLatest { comics ->
        if (comics.isEmpty()) flowOf(emptyList<ComicRow>())
        else {
            val rowFlows = comics.map { comic ->
                combine(
                    repository.observeCache(comic.id),
                    repository.observeProgress(comic.id)
                ) { cache, progress ->
                    ComicRow(comic = comic, cache = cache, progress = progress?.page ?: 0, isFinished = progress?.isFinished ?: false)
                }
            }
            combine(rowFlows) { it.toList() }
        }
    }

    // displayMode + showCovers + sort 合并为一个 Flow，避免 combine 超过 5 个参数
    private val displaySortPrefs = combine(displayMode, showCovers, sortField, sortAscending) { dm, scv, sf, asc ->
        DisplaySortPrefs(dm, scv, sf, asc)
    }

    fun sortComics(comics: List<ComicRow>, field: ComicSortField, ascending: Boolean): List<ComicRow> {
        val sorted = when (field) {
            ComicSortField.NAME -> comics.sortedBy { it.comic.title.lowercase() }
            ComicSortField.SIZE -> comics.sortedBy { it.comic.sizeBytes }
            ComicSortField.DATE -> comics.sortedBy { it.comic.addedAt }
        }
        return if (ascending) sorted else sorted.reversed()
    }

    val state: StateFlow<LibraryUiState> = combine(
        comicsFlow, recentFlow, query, scanning, displaySortPrefs
    ) { comics, recent, q, sc, prefs ->
        val filtered = if (q.isBlank()) comics else {
            val keywords = q.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (keywords.isEmpty()) comics
            else comics.filter { row ->
                keywords.all { keyword -> row.comic.title.contains(keyword, ignoreCase = true) }
            }
        }
        LibraryUiState(
            isScanning = sc,
            comics = sortComics(filtered, prefs.sortField, prefs.sortAscending),
            recent = recent,
            query = q,
            displayMode = prefs.displayMode,
            showCovers = prefs.showCovers,
            sortField = prefs.sortField,
            sortAscending = prefs.sortAscending
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun clearQuery() {
        query.value = ""
    }

    fun toggleDisplayMode() {
        val next = if (displayMode.value == LibraryDisplayMode.GRID) LibraryDisplayMode.LIST else LibraryDisplayMode.GRID
        displayMode.value = next
        viewModelScope.launch { settings.setLibraryDisplayMode(next.ordinal) }
    }

    fun setSortField(field: ComicSortField) {
        sortField.value = field
        viewModelScope.launch { settings.setSortField(field) }
    }

    fun toggleSortDirection() {
        val next = !sortAscending.value
        sortAscending.value = next
        viewModelScope.launch { settings.setSortAscending(next) }
    }

    fun scanAll() {
        viewModelScope.launch {
            scanning.value = true
            try {
                val sources = repository.listEnabledSources()
                var totalFound = 0
                for (s in sources) {
                    try {
                        val count = scanUseCase(
                            s,
                            onPhase1 = { n -> totalFound += n },
                            onPhase2 = { msg -> _toastEvents.tryEmit("${s.name}: $msg") }
                        )
                    } catch (e: Exception) {
                        Log.e("LibraryViewModel", "scan failed for ${s.name}", e)
                        _toastEvents.tryEmit("扫描失败：${s.name} - ${e.message}")
                    }
                }
                if (totalFound == 0 && sources.isNotEmpty()) {
                    _toastEvents.tryEmit("扫描完成，未发现新漫画")
                }
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
                val source = sources.firstOrNull { it.id == sourceId }
                if (source != null) {
                    val count = scanUseCase(
                        source,
                        onPhase1 = { n ->
                            if (n == 0) _toastEvents.tryEmit("未发现漫画文件")
                            else _toastEvents.tryEmit("已发现 $n 个漫画，正在获取详细信息...")
                        },
                        onPhase2 = { msg -> _toastEvents.tryEmit(msg) }
                    )
                }
            } catch (e: Exception) {
                Log.e("LibraryViewModel", "scanSource failed", e)
                _toastEvents.tryEmit("扫描失败：${e.message}")
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
                // 已取消或切换到其他漫画，不再更新
                if (_loadingProgress.value?.comicId != comicId) return@collectLatest
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
                _toastEvents.tryEmit("加载失败：${e.message}")
            }
        }
    }

    /** 关闭加载进度弹窗 */
    fun dismissLoading() {
        _loadingProgress.value = null
    }

    /** 取消加载漫画 */
    fun cancelLoading(comicId: Long) {
        viewModelScope.launch {
            // 先清除进度，让 observeCache 收集器不再覆盖
            if (_loadingProgress.value?.comicId == comicId) {
                _loadingProgress.value = null
            }
            cancelDownloadUseCase(comicId)
            repository.deleteCache(comicId)
        }
    }

    /** 清除所有阅读记录 */
    fun clearProgress() {
        viewModelScope.launch {
            repository.clearAllProgress()
        }
    }
}
