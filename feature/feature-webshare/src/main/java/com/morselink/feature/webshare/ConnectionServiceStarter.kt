package com.morselink.feature.webshare

import android.content.Context
import com.morselink.app.service.ConnectionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Thin indirection so feature modules never touch the app module's service type directly. */
@Singleton
class ConnectionServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() = ConnectionService.start(context)
    fun stop() = ConnectionService.stop(context)
}
