package com.morselink.core.transfer

/**
 * The one place the app writes diagnostics to.
 *
 * Everything goes to logcat, and anything the sink accepts goes to the in-app
 * log file as well. The sink matters because logcat is a rotating system buffer
 * shared with every other process: a burst of output from anything else can push
 * our lines out before anyone thinks to export, which is how a 3-file export
 * ended up carrying a single Morselink line.
 *
 * It lives here, at the bottom of the module graph, so the transports can reach
 * it without depending on a UI module.
 */
object MorselinkLog {

    private const val TAG = "Morselink"

    fun interface Sink {
        fun write(level: String, message: String)
    }

    @Volatile
    private var sink: Sink? = null

    /** Installed once by the Application; null switches file recording off. */
    fun install(sink: Sink?) {
        this.sink = sink
    }

    fun d(message: String) {
        android.util.Log.d(TAG, message)
        sink?.write("D", message)
    }

    fun w(message: String) {
        android.util.Log.w(TAG, message)
        sink?.write("W", message)
    }

    fun e(message: String) {
        android.util.Log.e(TAG, message)
        sink?.write("E", message)
    }
}
