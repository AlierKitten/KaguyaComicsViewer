package com.kaguya.comicsviewer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaguya.comicsviewer.data.prefs.AppSettings
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.domain.model.ReadingMode
import com.kaguya.comicsviewer.util.CacheDirectories
import com.kaguya.comicsviewer.util.FormatUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(ReadingMode.PAGED, true, false),
    val cacheSize: String = "0 B",
    val freeSpace: String = "—"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cacheDirs: CacheDirectories
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        kotlinx.coroutines.flow.flowOf(System.currentTimeMillis()) // tick
    ) { s, _ ->
        SettingsUiState(
            settings = s,
            cacheSize = FormatUtils.formatBytes(cacheDirs.totalSizeBytes()),
            freeSpace = FormatUtils.formatBytes(java.io.File("/").usableSpaceOrZero())
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setMode(mode: ReadingMode) = viewModelScope.launch { settings.setReadingMode(mode) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(enabled) }
    fun setAutoMarkRead(enabled: Boolean) = viewModelScope.launch { settings.setAutoMarkRead(enabled) }
}

private fun java.io.File.usableSpaceOrZero(): Long = runCatching { usableSpace }.getOrDefault(0L)
