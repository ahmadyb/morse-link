package com.morselink.core.network

/** Lets feature modules keep the transfer foreground service alive without depending on :app. */
interface SessionServiceController {
    fun start()
    fun stop()
}
