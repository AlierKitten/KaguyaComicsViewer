package com.kaguya.comicsviewer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaguya.comicsviewer.domain.model.ReadingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val readingMode: ReadingMode,
    val keepScreenOn: Boolean,
    val autoMarkRead: Boolean,
    val followSystemTheme: Boolean = true,
    val darkMode: Boolean = false,
    val dynamicColor: Boolean = true,
    val libraryDisplayMode: Int = 0,
    val enableCoverGeneration: Boolean = true
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyReadingMode = stringPreferencesKey("reading_mode")
    private val keyKeepScreenOn = booleanPreferencesKey("keep_screen_on")
    private val keyAutoMarkRead = booleanPreferencesKey("auto_mark_read")
    private val keyFollowSystemTheme = booleanPreferencesKey("follow_system_theme")
    private val keyDarkMode = booleanPreferencesKey("dark_mode")
    private val keyDynamicColor = booleanPreferencesKey("dynamic_color")
    private val keyLibraryDisplayMode = intPreferencesKey("library_display_mode")
    private val keyEnableCoverGeneration = booleanPreferencesKey("enable_cover_generation")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            readingMode = prefs[keyReadingMode]?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
                ?: ReadingMode.PAGED,
            keepScreenOn = prefs[keyKeepScreenOn] ?: true,
            autoMarkRead = prefs[keyAutoMarkRead] ?: false,
            followSystemTheme = prefs[keyFollowSystemTheme] ?: true,
            darkMode = prefs[keyDarkMode] ?: false,
            dynamicColor = prefs[keyDynamicColor] ?: true,
            libraryDisplayMode = prefs[keyLibraryDisplayMode] ?: 0,
            enableCoverGeneration = prefs[keyEnableCoverGeneration] ?: true
        )
    }

    suspend fun setReadingMode(mode: ReadingMode) {
        context.dataStore.edit { it[keyReadingMode] = mode.name }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[keyKeepScreenOn] = enabled }
    }

    suspend fun setAutoMarkRead(enabled: Boolean) {
        context.dataStore.edit { it[keyAutoMarkRead] = enabled }
    }

    suspend fun setFollowSystemTheme(enabled: Boolean) {
        context.dataStore.edit { it[keyFollowSystemTheme] = enabled }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[keyDarkMode] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[keyDynamicColor] = enabled }
    }

    suspend fun setLibraryDisplayMode(mode: Int) {
        context.dataStore.edit { it[keyLibraryDisplayMode] = mode }
    }

    suspend fun setEnableCoverGeneration(enabled: Boolean) {
        context.dataStore.edit { it[keyEnableCoverGeneration] = enabled }
    }
}
