package com.kaguya.comicsviewer.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.prefs.AppSettings
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.domain.model.ReadingMode
import com.kaguya.comicsviewer.util.CacheDirectories
import com.kaguya.comicsviewer.util.FormatUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(ReadingMode.PAGED, true, false),
    val cacheSize: String = "0 B",
    val freeSpace: String = "—",
    val isClearing: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cacheDirs: CacheDirectories,
    private val repository: ComicRepository
) : ViewModel() {

    private val _isClearing = MutableStateFlow(false)

    private val tick = MutableStateFlow(0L)

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        _isClearing,
        tick
    ) { s, clearing, _ ->
        SettingsUiState(
            settings = s,
            cacheSize = FormatUtils.formatBytes(cacheDirs.totalSizeBytes()),
            freeSpace = FormatUtils.formatBytes(java.io.File("/").usableSpaceOrZero()),
            isClearing = clearing
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setMode(mode: ReadingMode) = viewModelScope.launch { settings.setReadingMode(mode) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(enabled) }
    fun setAutoMarkRead(enabled: Boolean) = viewModelScope.launch { settings.setAutoMarkRead(enabled) }

    fun clearAllCache() {
        viewModelScope.launch {
            _isClearing.value = true
            try {
                withContext(Dispatchers.IO) {
                    val caches = repository.listAllCaches()
                    caches.forEach { cache ->
                        Log.d("SettingsVM", "deleting cache for comicId=${cache.comicId}")
                        repository.deleteCache(cache.comicId)
                    }
                }
                tick.value = System.currentTimeMillis()
                Log.d("SettingsVM", "all caches cleared")
            } catch (e: Exception) {
                Log.e("SettingsVM", "failed to clear cache", e)
            } finally {
                _isClearing.value = false
            }
        }
    }
}

private fun java.io.File.usableSpaceOrZero(): Long = runCatching { usableSpace }.getOrDefault(0L)
