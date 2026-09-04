package com.morselink.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.data.prefs.ThemeMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class MorselinkApp : Application() {

    @Inject
    lateinit var settings: SettingsStore

    override fun onCreate() {
        super.onCreate()
        val mode = runCatching {
            runBlocking(Dispatchers.IO) { settings.settings.first().themeMode }
        }.getOrDefault(ThemeMode.SYSTEM)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        Notifications.createChannels(this)
    }
}
