package com.morselink.core.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short feedback tones for connection and transfer events.
 *
 * Tones are generated rather than shipped as audio files: there is no media to
 * bundle, the same call works from API 21 up, and nothing has to be decoded
 * before the first sound plays.
 *
 * The setting is read by the caller - this only decides what a given event
 * sounds like.
 */
object SoundEffects {

    enum class Kind { CONNECTED, COMPLETE, FAILED }

    private const val VOLUME = 70

    private var generator: ToneGenerator? = null

    private fun tones(): ToneGenerator? =
        generator ?: runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
        }.getOrNull().also { generator = it }

    fun play(context: Context, kind: Kind) {
        val tone = tones() ?: return
        // On a silent or vibrate-only phone a tone is unwanted noise, so let
        // the ringer mode decide.
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (manager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            when (kind) {
                Kind.CONNECTED -> {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                }
                Kind.COMPLETE -> {
                    // A rising two-note figure reads as "finished" rather than
                    // as an alarm.
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
                    post(120) { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140) }
                }
                Kind.FAILED -> {
                    tone.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                }
            }
        }
    }

    private fun post(delayMs: Long, action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { runCatching(action) },
            delayMs,
        )
    }

    /** Releases the tone generator; it holds an audio track. */
    fun release() {
        runCatching { generator?.release() }
        generator = null
    }
}
