package com.kaguya.comicsviewer.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.domain.usecase.ScanSourceUseCase
import com.kaguya.comicsviewer.util.FormatUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceRow(
    val source: ComicSource,
    val lastScanned: String
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val repository: ComicRepository,
    private val scanUseCase: ScanSourceUseCase
) : ViewModel() {

    val sources: StateFlow<List<SourceRow>> = repository.observeSources()
        .map { list -> list.map { SourceRow(it, FormatUtils.formatDate(it.lastScannedAt)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isScanning = MutableStateFlow(false)

    fun addLocalSource(name: String, treeUri: String) {
        viewModelScope.launch {
            repository.upsertSource(
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
            repository.upsertSource(
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
            isScanning.value = true
            try { scanUseCase(source) } finally { isScanning.value = false }
        }
    }
}
