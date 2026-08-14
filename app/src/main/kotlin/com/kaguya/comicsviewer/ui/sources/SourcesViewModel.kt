package com.kaguya.comicsviewer.ui.sources

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.domain.usecase.ScanSourceUseCase
import com.kaguya.comicsviewer.util.FormatUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // 独立刷新状态：存储正在扫描的源 ID
    private val _scanningIds = MutableStateFlow<Set<Long>>(emptySet())
    val scanningIds: StateFlow<Set<Long>> = _scanningIds.asStateFlow()

    /** 是否有任意源正在扫描中。 */
    val isIndexing: StateFlow<Boolean> = _scanningIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 是否请求过停止但扫描尚未完全退出（用于 UI 显示「正在停止…」）。 */
    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

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

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
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
            // 添加后自动刷新
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
            // 添加后自动刷新
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

    fun scan(source: ComicSource) {
        viewModelScope.launch {
            _stopping.value = false
            scanUseCase.resetCancellation()
            _scanningIds.value = _scanningIds.value + source.id
            try {
                val count = scanUseCase(
                    source,
                    onPhase1 = { n ->
                        if (n == 0) _toastEvents.tryEmit("未发现漫画文件")
                        else _toastEvents.tryEmit("已发现 $n 个漫画，正在获取详细信息...")
                    },
                    onPhase2 = { msg -> _toastEvents.tryEmit(msg) },
                    onRarSkipped = { n -> _toastEvents.tryEmit("已跳过 $n 个 RAR/CBR（暂不支持，仅支持 ZIP/CBZ）") },
                    indexCover = settings.settings.value.indexCoverOnScan
                )
                if (_stopping.value) _toastEvents.tryEmit("已停止索引，已扫描的数据已保留")
            } catch (e: Exception) {
                Log.e("SourcesViewModel", "scan failed for ${source.name}", e)
                _toastEvents.tryEmit("扫描失败：${e.message}")
            } finally {
                _scanningIds.value = _scanningIds.value - source.id
                if (_scanningIds.value.isEmpty()) _stopping.value = false
            }
        }
    }

    fun scanAllEnabled() {
        viewModelScope.launch {
            val enabledSources = repository.listEnabledSources()
            if (enabledSources.isEmpty()) return@launch
            _stopping.value = false
            scanUseCase.resetCancellation()
            _scanningIds.value = enabledSources.map { it.id }.toSet()
            try {
                var totalFound = 0
                for (s in enabledSources) {
                    // 已被用户中止则提前结束后续源扫描
                    if (_stopping.value) break
                    try {
                        val count = scanUseCase(
                            s,
                            onPhase1 = { n -> totalFound += n },
                            onPhase2 = { msg -> _toastEvents.tryEmit("${s.name}: $msg") },
                            onRarSkipped = { n -> _toastEvents.tryEmit("已跳过 $n 个 RAR/CBR（暂不支持，仅支持 ZIP/CBZ）") },
                            indexCover = settings.settings.value.indexCoverOnScan
                        )
                    } catch (e: Exception) {
                        Log.e("SourcesViewModel", "scan failed for ${s.name}", e)
                        _toastEvents.tryEmit("扫描失败：${s.name} - ${e.message}")
                    }
                }
                if (_stopping.value) {
                    _toastEvents.tryEmit("已停止索引，已扫描的数据已保留")
                } else if (totalFound == 0) {
                    _toastEvents.tryEmit("扫描完成，未发现新漫画")
                }
            } finally {
                _scanningIds.value = emptySet()
                _stopping.value = false
            }
        }
    }

    /** 请求中止所有正在进行的索引。已写入数据库的漫画数据保留。 */
    fun cancelIndexing() {
        scanUseCase.cancel()
        _stopping.value = true
    }

    private suspend fun scanSourceById(sourceId: Long) {
        val source = repository.listEnabledSources().firstOrNull { it.id == sourceId } ?: return
        _stopping.value = false
        scanUseCase.resetCancellation()
        _scanningIds.value = _scanningIds.value + sourceId
        try {
                val count = scanUseCase(
                    source,
                    onPhase1 = { n ->
                        if (n == 0) _toastEvents.tryEmit("未发现漫画文件")
                        else _toastEvents.tryEmit("已发现 $n 个漫画，正在获取详细信息...")
                    },
                    onPhase2 = { msg -> _toastEvents.tryEmit(msg) },
                    onRarSkipped = { n -> _toastEvents.tryEmit("已跳过 $n 个 RAR/CBR（暂不支持，仅支持 ZIP/CBZ）") },
                    indexCover = settings.settings.value.indexCoverOnScan
                )
            if (_stopping.value) _toastEvents.tryEmit("已停止索引，已扫描的数据已保留")
        } catch (e: Exception) {
            Log.e("SourcesViewModel", "scan failed for ${source.name}", e)
            _toastEvents.tryEmit("扫描失败：${e.message}")
        } finally {
            _scanningIds.value = _scanningIds.value - sourceId
            if (_scanningIds.value.isEmpty()) _stopping.value = false
        }
    }
}
