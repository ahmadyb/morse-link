package com.morselink.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "morselink_settings")

/** Jetpack DataStore backed preferences (§8 — no legacy SharedPreferences). */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val AVATAR_SEED = intPreferencesKey("avatar_seed")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        val SOUNDS = booleanPreferencesKey("sounds")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val PREFER_WIFI_DIRECT = booleanPreferencesKey("prefer_wifi_direct")
        val TIMEOUT_SEC = intPreferencesKey("timeout_sec")
        val RECONNECT_ATTEMPTS = intPreferencesKey("reconnect_attempts")
    }

    val settings: Flow<UserSettings> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            UserSettings(
                deviceName = prefs[Keys.DEVICE_NAME] ?: UserSettings.DEFAULT_DEVICE_NAME,
                avatarSeed = prefs[Keys.AVATAR_SEED] ?: 0,
                themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: "SYSTEM") }
                    .getOrDefault(ThemeMode.SYSTEM),
                downloadDirectory = prefs[Keys.DOWNLOAD_DIR],
                soundsEnabled = prefs[Keys.SOUNDS] ?: true,
                transferNotifications = prefs[Keys.NOTIFICATIONS] ?: true,
                preferWifiDirectOverHotspot = prefs[Keys.PREFER_WIFI_DIRECT] ?: true,
                connectionTimeoutSeconds = prefs[Keys.TIMEOUT_SEC] ?: 15,
                maxReconnectAttempts = prefs[Keys.RECONNECT_ATTEMPTS] ?: 3,
            )
        }

    suspend fun current(): UserSettings = settings.first()

    private suspend fun edit(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { transform(it) }
    }

    suspend fun setDeviceName(value: String) = edit { it[Keys.DEVICE_NAME] = value }

    suspend fun setAvatarSeed(value: Int) = edit { it[Keys.AVATAR_SEED] = value }

    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.THEME_MODE] = value.name }

    suspend fun setDownloadDirectory(value: String) = edit { it[Keys.DOWNLOAD_DIR] = value }

    suspend fun setSounds(value: Boolean) = edit { it[Keys.SOUNDS] = value }

    suspend fun setNotifications(value: Boolean) = edit { it[Keys.NOTIFICATIONS] = value }

    suspend fun setPreferWifiDirect(value: Boolean) = edit { it[Keys.PREFER_WIFI_DIRECT] = value }

    suspend fun setTimeoutSeconds(value: Int) = edit { it[Keys.TIMEOUT_SEC] = value }

    suspend fun setReconnectAttempts(value: Int) = edit { it[Keys.RECONNECT_ATTEMPTS] = value }
}
