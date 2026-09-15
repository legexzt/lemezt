package com.lemezt.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lemezt_preferences")

class UserPreferencesDataStore(private val context: Context) {

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode") // "SYSTEM", "LIGHT", "DARK"
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEY_AUTO_RESUME = booleanPreferencesKey("auto_resume")
        val KEY_DEFAULT_VIDEO_QUALITY = stringPreferencesKey("default_video_quality") // "1080p", "720p", "Best"
        val KEY_DEFAULT_AUDIO_FORMAT = stringPreferencesKey("default_audio_format")   // "M4A", "MP3"
        val KEY_EMBED_COVER = booleanPreferencesKey("embed_cover")
        val KEY_SAVE_LYRICS = booleanPreferencesKey("save_lyrics")
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "SYSTEM"
    }

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_WIFI_ONLY] ?: false
    }

    val autoResume: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RESUME] ?: true
    }

    val defaultVideoQuality: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_VIDEO_QUALITY] ?: "1080p"
    }

    val defaultAudioFormat: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_AUDIO_FORMAT] ?: "M4A"
    }

    val embedCover: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EMBED_COVER] ?: true
    }

    val saveLyrics: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAVE_LYRICS] ?: true
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_WIFI_ONLY] = enabled }
    }

    suspend fun setAutoResume(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_RESUME] = enabled }
    }

    suspend fun setDefaultVideoQuality(quality: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DEFAULT_VIDEO_QUALITY] = quality }
    }

    suspend fun setDefaultAudioFormat(format: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DEFAULT_AUDIO_FORMAT] = format }
    }

    suspend fun setEmbedCover(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_EMBED_COVER] = enabled }
    }

    suspend fun setSaveLyrics(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SAVE_LYRICS] = enabled }
    }
}
