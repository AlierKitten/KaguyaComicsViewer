package com.kaguya.comicsviewer.data.prefs

import com.kaguya.comicsviewer.domain.model.ComicSortField
import com.kaguya.comicsviewer.domain.model.ReadingMode
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val readingMode: ReadingMode,
    val keepScreenOn: Boolean,
    val autoMarkRead: Boolean,
    val followSystemTheme: Boolean = true,
    val darkMode: Boolean = false,
    val dynamicColor: Boolean = true,
    val libraryDisplayMode: Int = 0,
    val showCovers: Boolean = true,
    val indexCoverOnScan: Boolean = true,
    val sortField: ComicSortField = ComicSortField.NAME,
    val sortAscending: Boolean = true,
    val hideFromRecents: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor() {

    private val kv: MMKV = MMKV.defaultMMKV()

    private val keyReadingMode = "reading_mode"
    private val keyKeepScreenOn = "keep_screen_on"
    private val keyAutoMarkRead = "auto_mark_read"
    private val keyFollowSystemTheme = "follow_system_theme"
    private val keyDarkMode = "dark_mode"
    private val keyDynamicColor = "dynamic_color"
    private val keyLibraryDisplayMode = "library_display_mode"
    private val keyShowCovers = "show_covers"
    private val keyIndexCoverOnScan = "index_cover_on_scan"
    private val keySortField = "sort_field"
    private val keySortAscending = "sort_ascending"
    private val keyHideFromRecents = "hide_from_recents"

    private fun readSettings(): AppSettings = AppSettings(
        readingMode = kv.decodeString(keyReadingMode)?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
            ?: ReadingMode.PAGED,
        keepScreenOn = kv.decodeBool(keyKeepScreenOn, true),
        autoMarkRead = kv.decodeBool(keyAutoMarkRead, false),
        followSystemTheme = kv.decodeBool(keyFollowSystemTheme, true),
        darkMode = kv.decodeBool(keyDarkMode, false),
        dynamicColor = kv.decodeBool(keyDynamicColor, true),
        libraryDisplayMode = kv.decodeInt(keyLibraryDisplayMode, 0),
        showCovers = kv.decodeBool(keyShowCovers, true),
        indexCoverOnScan = kv.decodeBool(keyIndexCoverOnScan, true),
        sortField = kv.decodeString(keySortField)?.let { runCatching { ComicSortField.valueOf(it) }.getOrNull() }
            ?: ComicSortField.NAME,
        sortAscending = kv.decodeBool(keySortAscending, true),
        hideFromRecents = kv.decodeBool(keyHideFromRecents, false)
    )

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings

    private fun emit() {
        _settings.value = readSettings()
    }

    fun setReadingMode(mode: ReadingMode) {
        kv.encode(keyReadingMode, mode.name)
        emit()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        kv.encode(keyKeepScreenOn, enabled)
        emit()
    }

    fun setAutoMarkRead(enabled: Boolean) {
        kv.encode(keyAutoMarkRead, enabled)
        emit()
    }

    fun setFollowSystemTheme(enabled: Boolean) {
        kv.encode(keyFollowSystemTheme, enabled)
        emit()
    }

    fun setDarkMode(enabled: Boolean) {
        kv.encode(keyDarkMode, enabled)
        emit()
    }

    fun setDynamicColor(enabled: Boolean) {
        kv.encode(keyDynamicColor, enabled)
        emit()
    }

    fun setLibraryDisplayMode(mode: Int) {
        kv.encode(keyLibraryDisplayMode, mode)
        emit()
    }

    fun setShowCovers(enabled: Boolean) {
        kv.encode(keyShowCovers, enabled)
        emit()
    }

    fun setIndexCoverOnScan(enabled: Boolean) {
        kv.encode(keyIndexCoverOnScan, enabled)
        emit()
    }

    fun setSortField(field: ComicSortField) {
        kv.encode(keySortField, field.name)
        emit()
    }

    fun setSortAscending(ascending: Boolean) {
        kv.encode(keySortAscending, ascending)
        emit()
    }

    fun setHideFromRecents(enabled: Boolean) {
        kv.encode(keyHideFromRecents, enabled)
        emit()
    }
}
