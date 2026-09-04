package com.morselink.feature.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.data.prefs.ThemeMode
import com.morselink.core.data.prefs.UserSettings
import com.morselink.core.media.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val media: MediaRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: LiveData<UserSettings> = settings.settings.asLiveData()

    private var latest: UserSettings = UserSettings()

    init {
        viewModelScope.launch { latest = settings.current() }
    }

    fun deviceName(): String = latest.deviceName
    fun downloadDirectory(): String = latest.downloadDirectory ?: defaultDownloadPath()
    fun timeout(): Int = latest.connectionTimeoutSeconds
    fun reconnect(): Int = latest.maxReconnectAttempts

    fun defaultDownloadPath(): String =
        runCatching { media.defaultDownloadDirectory().absolutePath }.getOrDefault("")

    fun versionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("1.0.0") ?: "1.0.0"

    fun setDeviceName(value: String) = viewModelScope.launch { settings.setDeviceName(value); refresh() }
    fun setDownloadDirectory(value: String) = viewModelScope.launch { settings.setDownloadDirectory(value); refresh() }
    fun setPreferWifiDirect(value: Boolean) = viewModelScope.launch { settings.setPreferWifiDirect(value); refresh() }
    fun setTheme(value: ThemeMode) = viewModelScope.launch { settings.setThemeMode(value); refresh() }
    fun setNotifications(value: Boolean) = viewModelScope.launch { settings.setNotifications(value); refresh() }
    fun setSounds(value: Boolean) = viewModelScope.launch { settings.setSounds(value); refresh() }
    fun setTimeout(value: Int) = viewModelScope.launch {
        settings.setTimeoutSeconds(value.coerceIn(5, 120)); refresh()
    }
    fun setReconnect(value: Int) = viewModelScope.launch {
        settings.setReconnectAttempts(value.coerceIn(0, 10)); refresh()
    }
    fun cycleAvatar() = viewModelScope.launch {
        settings.setAvatarSeed((latest.avatarSeed + 1) % 8); refresh()
    }

    private suspend fun refresh() {
        latest = settings.settings.first()
    }
}
