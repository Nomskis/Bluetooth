package io.github.nomskis.earshot.calls

import android.media.ToneGenerator
import android.util.Log

/** The "it's ringing" tone you hear while calling someone, on the call's own audio stream. */
class Ringback(private val stream: Int) {
    private var tone: ToneGenerator? = null

    fun start() {
        if (tone != null) return
        tone = try {
            ToneGenerator(stream, VOLUME).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: RuntimeException) {
            Log.w("EarshotRingback", "No ringback tone: ${e.message}")
            null
        }
    }

    fun stop() {
        tone?.stopTone()
        tone?.release()
        tone = null
    }

    private companion object {
        const val VOLUME = 60
    }
}
