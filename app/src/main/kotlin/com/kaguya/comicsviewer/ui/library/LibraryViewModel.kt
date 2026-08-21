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
import com.kaguya.comicsviewer.domain.model.ComicSourceType
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
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
    val sortAscending: Boolean,
    val enabledReady: Boolean,
    val enabledIds: Set<Long>
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
    // 后台 index 状态来自 ScanSourceUseCase（Application 级后台协程），
    // 切页面 / 退到后台 / ViewModel 重建都不会丢失。
    private val scanning = scanUseCase.indexingIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
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

    // 启用中的源 id 集合：仅用于 UI 层过滤显示（关闭源 = 隐藏，不删除数据、不重新索引）
    // 用 (ready, ids) 携带"是否已就绪"标志：冷启动时 observeSources 尚未 emit 第一帧前，
    // 默认 enabledIds = emptySet()，若直接用它过滤会把全部漫画误判为"关闭源"而隐藏（显示 0 个）。
    // 因此未就绪时跳过过滤，待源列表首帧到达后再正常按启用状态隐藏。
    private val enabledSourceIds: kotlinx.coroutines.flow.Flow<Pair<Boolean, Set<Long>>> =
        repository.observeSources()
            .map { srcs -> true to srcs.filter { it.enabled }.map { it.id }.toSet() }
            .onStart { emit(false to emptySet()) }

    // 当前正在加载的漫画进度
    private val _loadingProgress = MutableStateFlow<LoadingProgress?>(null)
    val loadingProgress: StateFlow<LoadingProgress?> = _loadingProgress.asStateFlow()

    // Toast 一次性事件
    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvents = _toastEvents.asSharedFlow()

    // 将单个 Comic 包装成带 cache + progress 的 ComicRow Flow
    private fun toRowFlow(comic: Comic) = combine(
        repository.observeCache(comic.id),
        repository.observeProgress(comic.id)
    ) { cache, progress ->
        ComicRow(
            comic = comic,
            cache = cache,
            progress = progress?.page ?: 0,
            isFinished = progress?.isFinished ?: false
        )
    }

    // 始终查询所有源的漫画：与源的启用/关闭完全解耦，数据不会被删除。
    // 源关闭仅在最终 UI 层过滤（隐藏），重新启用即可立即恢复，无需重新扫描。
    //
    // 关键修复：comics.source_id 是 comic_sources 的外键，Room 的 InvalidationTracker 在
    // 源的 enabled 字段被 upsert 时会使 comics 相关查询失效并重查。重查瞬间可能短暂
    // 返回空列表；若直接 flatMapLatest 到空的 flowOf，内层会被取消且上游不再变化，
    // 导致 comicsFlow 永久停留在 0（重新启用也无法恢复）。因此用 scan 保留上一次
    // 非空快照，空发射不覆盖已有数据，避免瞬时空击穿 UI。
    private val comicsFlow: kotlinx.coroutines.flow.Flow<List<ComicRow>> =
        repository.observeAllComics()
            .scan(emptyList<Comic>()) { acc, value ->
                if (value.isEmpty() && acc.isNotEmpty()) acc else value
            }
            .flatMapLatest { comics ->
                if (comics.isEmpty()) {
                    flowOf(emptyList<ComicRow>())
                } else {
                    val rowFlows = comics.map { toRowFlow(it) }
                    combine(rowFlows) { it.toList() }
                }
            }.onEach { rows: List<ComicRow> ->
                Log.d("LibraryVM", "comicsFlow emitted: ${rows.size} rows")
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
        if (comics.isEmpty()) {
            flowOf(emptyList<ComicRow>())
        } else {
            val rowFlows = comics.map { toRowFlow(it) }
            combine(rowFlows) { it.toList() }
        }
    }

    // displayMode + showCovers + sort + enabledSourceIds 合并为一个 Flow，避免 combine 超过 5 个参数
    private val displaySortPrefs = combine(
        displayMode,
        showCovers,
        sortField,
        sortAscending,
        enabledSourceIds
    ) { dm, scv, sf, asc, (ready, ids) ->
        DisplaySortPrefs(dm, scv, sf, asc, ready, ids)
    }

    fun sortComics(
        comics: List<ComicRow>,
        field: ComicSortField,
        ascending: Boolean
    ): List<ComicRow> {
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
        // 按源的启用/关闭做纯 UI 过滤：关闭的源仅隐藏，不删除数据、不重新索引
        // enabledReady 为 false（源列表尚未首帧就绪）时跳过过滤，避免冷启动空集合误杀全部漫画
        val visibleComics = if (prefs.enabledReady)
            comics.filter { it.comic.sourceId in prefs.enabledIds } else comics
        val visibleRecent = if (prefs.enabledReady)
            recent.filter { it.comic.sourceId in prefs.enabledIds } else recent
        val filtered = if (q.isBlank()) {
            visibleComics
        } else {
            val keywords = q.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (keywords.isEmpty()) {
                visibleComics
            } else {
                visibleComics.filter { row ->
                    keywords.all { keyword -> row.comic.title.contains(keyword, ignoreCase = true) }
                }
            }
        }
        LibraryUiState(
            isScanning = sc,
            comics = sortComics(filtered, prefs.sortField, prefs.sortAscending),
            recent = visibleRecent,
            query = q,
            displayMode = prefs.displayMode,
            showCovers = prefs.showCovers,
            sortField = prefs.sortField,
            sortAscending = prefs.sortAscending
        ).also {
            Log.d(
                "LibraryVM",
                "state recompute: allComics=${comics.size}, enabledIds=${prefs.enabledIds}, visible=${filtered.size}, query='$q'"
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun clearQuery() {
        query.value = ""
    }

    fun toggleDisplayMode() {
        val next =
            if (displayMode.value == LibraryDisplayMode.GRID) LibraryDisplayMode.LIST else LibraryDisplayMode.GRID
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

    /** 在后台索引所有启用的源（不阻塞 UI，可切页面 / 退到后台）。 */
    fun scanAll() {
        scanUseCase.startScanAll()
        _toastEvents.tryEmit("已在后台开始索引全部启用的文件源")
    }

    /** 在后台索引单个源。 */
    fun scanSource(sourceId: Long) {
        viewModelScope.launch {
            val source = repository.listEnabledSources().firstOrNull { it.id == sourceId } ?: return@launch
            scanUseCase.startScan(source)
            _toastEvents.tryEmit("已在后台开始索引「${source.name}」")
        }
    }

    /** 开始加载漫画，并追踪进度 */
    fun startLoading(comicId: Long, title: String) {
        Log.d("LibraryViewModel", "startLoading: comicId=$comicId, title='$title'")

        viewModelScope.launch {
            // 本地源 ZIP/CBZ：扫描阶段已标记 READY，采用「按需单页解压、整包零落盘」，
            // 无需任何 Worker。直接置 READY 让 UI 立即跳转阅读器（不弹加载框）。
            // 判断依据为「源类型 + 扩展名」，不依赖缓存行是否存在，避免缓存行缺失时卡死。
            val comic = repository.findComic(comicId)
            val source = comic?.let { c ->
                repository.listEnabledSources().firstOrNull { it.id == c.sourceId }
            }
            val isLocalZip = source?.type == ComicSourceType.LOCAL &&
                comic?.filePath?.let { p ->
                    p.endsWith(".zip", true) || p.endsWith(".cbz", true)
                } == true
            if (isLocalZip) {
                Log.d("LibraryViewModel", "local ZIP direct-read, skip loading dialog: comicId=$comicId")
                _loadingProgress.value = LoadingProgress(
                    comicId = comicId,
                    title = title,
                    state = CacheState.READY
                )
                return@launch
            }

            // 非本地源（如 SMB）或 RAR（理论上扫描阶段已过滤）：置 PENDING 并启动观察与下载链
            _loadingProgress.value = LoadingProgress(
                comicId = comicId,
                title = title,
                state = CacheState.PENDING
            )

            // 观察缓存状态变化来更新进度
            launch {
                repository.observeCache(comicId).collectLatest { c ->
                    // 已取消或切换到其他漫画，不再更新
                    if (_loadingProgress.value?.comicId != comicId) return@collectLatest
                    Log.d("LibraryViewModel", "cache update: comicId=$comicId, state=${c?.state}")
                    _loadingProgress.value = LoadingProgress(
                        comicId = comicId,
                        title = title,
                        state = c?.state ?: CacheState.PENDING,
                        progressPercent = if ((c?.totalBytes ?: 0) > 0)
                            ((c!!.downloadedBytes * 100) / c.totalBytes).toInt() else 0,
                        downloadedBytes = c?.downloadedBytes ?: 0,
                        totalBytes = c?.totalBytes ?: 0,
                        error = c?.lastError
                    )
                }
            }

            // 触发下载
            try {
                downloadUseCase(comicId)
                Log.d("LibraryViewModel", "download scheduled: comicId=$comicId")
            } catch (e: Exception) {
                Log.e("LibraryViewModel", "download failed: comicId=$comicId", e)
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

    /** 清除单条阅读记录 */
    fun clearProgress(comicId: Long) {
        viewModelScope.launch {
            repository.deleteProgress(comicId)
        }
    }
}
