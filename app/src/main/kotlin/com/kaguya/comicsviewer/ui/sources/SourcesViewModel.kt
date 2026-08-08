package com.kaguya.comicsviewer.ui.sources

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val smbClient: SmbClient
) : ViewModel() {

    // 独立刷新状态：存储正在扫描的源 ID
    private val _scanningIds = MutableStateFlow<Set<Long>>(emptySet())
    val scanningIds: StateFlow<Set<Long>> = _scanningIds.asStateFlow()

    // 每个源的漫画数量
    private val comicCounts = repository.observeSources()
        .map { list -> list.map { it.id } }
        .flatMapLatest { ids ->
            if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap<Long, Int>())
            else repository.observeComicCountBySources(ids)
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

    fun delete(source: ComicSource) {
        viewModelScope.launch { repository.deleteSource(source.id) }
    }

    fun scan(source: ComicSource) {
        viewModelScope.launch {
            _scanningIds.value = _scanningIds.value + source.id
            try {
                val count = scanUseCase(source)
                if (count == 0) {
                    _toastEvents.tryEmit("未发现漫画文件")
                }
            } catch (e: Exception) {
                Log.e("SourcesViewModel", "scan failed for ${source.name}", e)
                _toastEvents.tryEmit("扫描失败：${e.message}")
            } finally {
                _scanningIds.value = _scanningIds.value - source.id
            }
        }
    }

    fun scanAllEnabled() {
        viewModelScope.launch {
            val enabledSources = repository.listEnabledSources()
            if (enabledSources.isEmpty()) return@launch
            _scanningIds.value = enabledSources.map { it.id }.toSet()
            try {
                var totalFound = 0
                for (s in enabledSources) {
                    try {
                        val count = scanUseCase(s)
                        totalFound += count
                    } catch (e: Exception) {
                        Log.e("SourcesViewModel", "scan failed for ${s.name}", e)
                        _toastEvents.tryEmit("扫描失败：${s.name} - ${e.message}")
                    }
                }
                if (totalFound == 0) {
                    _toastEvents.tryEmit("扫描完成，未发现新漫画")
                }
            } finally {
                _scanningIds.value = emptySet()
            }
        }
    }

    private suspend fun scanSourceById(sourceId: Long) {
        val source = repository.listEnabledSources().firstOrNull { it.id == sourceId } ?: return
        _scanningIds.value = _scanningIds.value + sourceId
        try {
            val count = scanUseCase(source)
            if (count == 0) {
                _toastEvents.tryEmit("未发现漫画文件")
            }
        } catch (e: Exception) {
            Log.e("SourcesViewModel", "scan failed for ${source.name}", e)
            _toastEvents.tryEmit("扫描失败：${e.message}")
        } finally {
            _scanningIds.value = _scanningIds.value - sourceId
        }
    }
}
