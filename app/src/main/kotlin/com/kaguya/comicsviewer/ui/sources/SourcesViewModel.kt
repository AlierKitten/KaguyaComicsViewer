package com.kaguya.comicsviewer.ui.sources

import com.kaguya.comicsviewer.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.domain.usecase.ScanSourceUseCase
import com.kaguya.comicsviewer.util.FormatUtils
import com.kaguya.comicsviewer.util.ToastEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceRow(
    val source: ComicSource,
    val lastScanned: String,
    val comicCount: Int = 0
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val repository: ComicRepository,
    private val scanUseCase: ScanSourceUseCase,
    private val settings: SettingsRepository,
    val smbClient: SmbClient
) : ViewModel() {

    // 后台 index 状态直接来自 ScanSourceUseCase（Application 级后台协程维护），
    // 这样切页面 / 退到后台 / ViewModel 重建都不会丢失状态。
    val scanningIds: StateFlow<Set<Long>> = scanUseCase.indexingIds
    val isIndexing: StateFlow<Boolean> = scanUseCase.indexingIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val stopping: StateFlow<Boolean> = scanUseCase.isStopping

    // 每个源的漫画数量
    // 同 LibraryViewModel：comics.source_id 是 comic_sources 外键，toggle enabled 会让
    // observeComicCountBySources 因 Room 失效重查并瞬间返回空 map；flatMapLatest 收到空
    // 会卡在 0。用 scan 保留上一次非空快照，避免瞬时空击穿 UI。
    private val comicCounts = repository.observeSources()
        .map { list -> list.map { it.id } }
        .flatMapLatest { ids ->
            if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap<Long, Int>())
            else repository.observeComicCountBySources(ids)
        }
        .scan(emptyMap<Long, Int>()) { acc, value ->
            if (value.isEmpty() && acc.isNotEmpty()) acc else value
        }

    val sources: StateFlow<List<SourceRow>> = combine(
        repository.observeSources(),
        comicCounts
    ) { list, counts ->
        list.map {
            SourceRow(
                source = it,
                lastScanned = FormatUtils.formatDate(it.lastScannedAt),
                comicCount = counts[it.id] ?: 0
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toastEvents = MutableSharedFlow<ToastEvent>(extraBufferCapacity = 1)
    val toastEvents = _toastEvents.asSharedFlow()

    fun addLocalSource(name: String, treeUri: String) {
        viewModelScope.launch {
            val id = repository.upsertSource(
                ComicSource(
                    id = 0,
                    type = ComicSourceType.LOCAL,
                    name = name,
                    localUri = treeUri,
                    host = null, share = null, path = null,
                    username = null, password = null, domain = null,
                    enabled = true, lastScannedAt = null
                )
            )
            // 添加后自动刷新（后台 index）
            scanSourceById(id)
        }
    }

    fun addSmbSource(
        name: String,
        host: String,
        share: String,
        path: String?,
        username: String?,
        password: String?,
        domain: String?
    ) {
        viewModelScope.launch {
            val id = repository.upsertSource(
                ComicSource(
                    id = 0,
                    type = ComicSourceType.SMB,
                    name = name,
                    localUri = null,
                    host = host, share = share, path = path,
                    username = username, password = password, domain = domain,
                    enabled = true, lastScannedAt = null
                )
            )
            // 添加后自动刷新（后台 index）
            scanSourceById(id)
        }
    }

    fun toggleEnabled(source: ComicSource, enabled: Boolean) {
        viewModelScope.launch {
            repository.upsertSource(source.copy(enabled = enabled))
        }
    }

    fun renameSource(source: ComicSource, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == source.name) return
        viewModelScope.launch {
            repository.upsertSource(source.copy(name = trimmed))
        }
    }

    fun delete(source: ComicSource) {
        viewModelScope.launch { repository.deleteSource(source.id) }
    }

    /**
     * 在后台索引单个源。扫描在 Application 级后台协程中运行，不阻塞 UI，
     * 可自由切到其他页面或退出应用；进程由前台保活服务守护。
     */
    fun scan(source: ComicSource) {
        scanUseCase.startScan(source)
        _toastEvents.tryEmit(ToastEvent(R.string.toast_indexing_started_named, arrayOf(source.name)))
    }

    /** 在后台索引所有启用的源。 */
    fun scanAllEnabled() {
        scanUseCase.startScanAll()
        _toastEvents.tryEmit(ToastEvent(R.string.toast_indexing_started, emptyArray()))
    }

    /** 请求中止所有正在进行的后台索引。已写入数据库的漫画数据保留。 */
    fun cancelIndexing() {
        scanUseCase.cancel()
    }

    /** 添加源后自动在后台索引该源。 */
    private suspend fun scanSourceById(sourceId: Long) {
        val source = repository.listEnabledSources().firstOrNull { it.id == sourceId } ?: return
        scanUseCase.startScan(source)
    }
}
