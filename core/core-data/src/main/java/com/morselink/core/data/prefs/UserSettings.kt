package com.morselink.core.data.prefs

enum class ThemeMode { LIGHT, DARK, SYSTEM }

data class UserSettings(
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val avatarSeed: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val downloadDirectory: String? = null,
    val soundsEnabled: Boolean = true,
    val transferNotifications: Boolean = true,
    val preferWifiDirectOverHotspot: Boolean = true,
    val connectionTimeoutSeconds: Int = 15,
    val maxReconnectAttempts: Int = 3,
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "Morselink Phone"
    }
}
