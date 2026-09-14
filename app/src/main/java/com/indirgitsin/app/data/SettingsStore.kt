package com.indirgitsin.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

object SettingsStore {
    private val KEY_AUTO_HIGH = booleanPreferencesKey("auto_high_quality")
    private val KEY_AUDIO_FORMAT = stringPreferencesKey("audio_format")
    private val KEY_DOWNLOAD_SUBFOLDER = stringPreferencesKey("download_subfolder")
    private val KEY_THEME = stringPreferencesKey("theme")
    private val KEY_APP_COLOR = stringPreferencesKey("app_color")
    private val KEY_LANGUAGE = stringPreferencesKey("app_language")
    private val KEY_UNMETERED = booleanPreferencesKey("unmetered_only")
    private val KEY_BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")

    fun backgroundPlaybackFlow(context: Context): Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_BACKGROUND_PLAYBACK] ?: true }
    suspend fun setBackgroundPlayback(context: Context, value: Boolean) { context.settingsDataStore.edit { it[KEY_BACKGROUND_PLAYBACK] = value } }

    fun unmeteredFlow(context: Context): Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_UNMETERED] ?: false }
    suspend fun setUnmetered(context: Context, value: Boolean) { context.settingsDataStore.edit { it[KEY_UNMETERED] = value } }

    fun autoHighFlow(context: Context): Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_AUTO_HIGH] ?: true }
    suspend fun setAutoHigh(context: Context, value: Boolean) { context.settingsDataStore.edit { it[KEY_AUTO_HIGH] = value } }

    fun audioFormatFlow(context: Context): Flow<String> = context.settingsDataStore.data.map { it[KEY_AUDIO_FORMAT]?.takeIf { value -> value in setOf("M4A", "WEBM", "MP3") } ?: "M4A" }
    suspend fun setAudioFormat(context: Context, value: String) { context.settingsDataStore.edit { it[KEY_AUDIO_FORMAT] = value } }

    private fun cleanFolder(value: String): String = value.take(30).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.').ifBlank { "IndirGitsin" }
    fun downloadSubfolderFlow(context: Context): Flow<String> = context.settingsDataStore.data.map { cleanFolder(it[KEY_DOWNLOAD_SUBFOLDER] ?: "IndirGitsin") }
    suspend fun setDownloadSubfolder(context: Context, value: String) { context.settingsDataStore.edit { it[KEY_DOWNLOAD_SUBFOLDER] = cleanFolder(value) } }
    suspend fun getDownloadSubfolderNow(context: Context): String = try { downloadSubfolderFlow(context).first() } catch (_: Exception) { "IndirGitsin" }

    fun themeFlow(context: Context): Flow<String> = context.settingsDataStore.data.map { it[KEY_THEME] ?: "dark" }
    suspend fun setTheme(context: Context, value: String) { context.settingsDataStore.edit { it[KEY_THEME] = value } }

    fun appColorFlow(context: Context): Flow<String> = context.settingsDataStore.data.map { it[KEY_APP_COLOR] ?: "red" }
    suspend fun setAppColor(context: Context, value: String) { context.settingsDataStore.edit { it[KEY_APP_COLOR] = value } }

    fun languageFlow(context: Context): Flow<String> = context.settingsDataStore.data.map { it[KEY_LANGUAGE] ?: "tr" }
    suspend fun setLanguage(context: Context, value: String) { context.settingsDataStore.edit { it[KEY_LANGUAGE] = value } }
}
