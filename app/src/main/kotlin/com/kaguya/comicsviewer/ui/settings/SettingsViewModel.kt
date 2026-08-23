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
    val settings: AppSettings = AppSettings(ReadingMode.PAGED, true, false, true, false, true),
    val cacheSize: String = "0 B",
    val coverSize: String = "0 B",
    val freeSpace: String = "—",
    val isClearing: Boolean = false,
    val isClearingCovers: Boolean = false,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cacheDirs: CacheDirectories,
    private val repository: ComicRepository
) : ViewModel() {

    private val _isClearing = MutableStateFlow(false)
    private val _isClearingCovers = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)

    private val tick = MutableStateFlow(0L)

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        _isClearing,
        _isClearingCovers,
        _isRefreshing,
        tick
    ) { s, clearing, clearingCovers, refreshing, _ ->
        SettingsUiState(
            settings = s,
            cacheSize = FormatUtils.formatBytes(cacheDirs.totalSizeBytes()),
            coverSize = FormatUtils.formatBytes(cacheDirs.coverSizeBytes()),
            freeSpace = FormatUtils.formatBytes(java.io.File("/").usableSpaceOrZero()),
            isClearing = clearing,
            isClearingCovers = clearingCovers,
            isRefreshing = refreshing
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setMode(mode: ReadingMode) = viewModelScope.launch { settings.setReadingMode(mode) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(enabled) }
    fun setAutoMarkRead(enabled: Boolean) = viewModelScope.launch { settings.setAutoMarkRead(enabled) }
    fun setFollowSystemTheme(enabled: Boolean) = viewModelScope.launch {
        settings.setFollowSystemTheme(enabled)
        // 开启跟随系统时，自动关闭手动深色模式
        if (enabled) {
            settings.setDarkMode(false)
        }
    }
    fun setDarkMode(enabled: Boolean) = viewModelScope.launch { settings.setDarkMode(enabled) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColor(enabled) }
    fun setShowCovers(enabled: Boolean) = viewModelScope.launch { settings.setShowCovers(enabled) }
    fun setIndexCoverOnScan(enabled: Boolean) = viewModelScope.launch { settings.setIndexCoverOnScan(enabled) }
    fun setHideFromRecents(enabled: Boolean) = viewModelScope.launch { settings.setHideFromRecents(enabled) }
    fun setLanguage(code: String) = viewModelScope.launch { settings.setLanguage(code) }

    /** 立即刷新存储占用统计。 */
    fun refreshStorage() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                withContext(Dispatchers.IO) {
                    // 触发计算：totalSizeBytes / coverSizeBytes 在 tick 变化时重算
                    cacheDirs.totalSizeBytes()
                    cacheDirs.coverSizeBytes()
                }
                tick.value = System.currentTimeMillis()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            _isClearing.value = true
            try {
                withContext(Dispatchers.IO) {
                    // 先通过 DB 记录删除关联文件
                    val caches = repository.listAllCaches()
                    caches.forEach { cache ->
                        Log.d("SettingsVM", "deleting cache for comicId=${cache.comicId}")
                        repository.deleteCache(cache.comicId)
                    }
                    // 清理孤立文件：直接清空缓存子目录
                    cacheDirs.archives.let { dir ->
                        dir.listFiles()?.forEach { f ->
                            Log.d("SettingsVM", "cleaning orphan archive: ${f.name}")
                            f.deleteRecursively()
                        }
                    }
                    cacheDirs.extracted.let { dir ->
                        dir.listFiles()?.forEach { f ->
                            Log.d("SettingsVM", "cleaning orphan extracted: ${f.name}")
                            f.deleteRecursively()
                        }
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

    fun clearAllCovers() {
        viewModelScope.launch {
            _isClearingCovers.value = true
            try {
                withContext(Dispatchers.IO) {
                    cacheDirs.clearCovers()
                    // 清除数据库中的封面路径
                    repository.clearAllCoverPaths()
                }
                tick.value = System.currentTimeMillis()
                Log.d("SettingsVM", "all covers cleared")
            } catch (e: Exception) {
                Log.e("SettingsVM", "failed to clear covers", e)
            } finally {
                _isClearingCovers.value = false
            }
        }
    }
}

private fun java.io.File.usableSpaceOrZero(): Long = runCatching { usableSpace }.getOrDefault(0L)
