package io.github.nomskis.earshot.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Dips the user's music while the other person talks, then brings it back.
 *
 * Android 8+ ducks other apps by itself (to -14 dB over 500 ms, see
 * PlaybackActivityMonitor.DUCK_VSHAPE) while someone holds
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK. The voice detector fires as her audio
 * enters Android, so the dip starts before her voice has travelled through
 * the Bluetooth buffer to your ears.
 *
 * Some players pause instead of ducking (podcast apps, for example). If the
 * number of active media players drops right after a dip, we give the focus
 * back at once and stop dipping for this call.
 */
class SmartDuck(context: Context, private val onPausesInsteadOfDucking: () -> Unit) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener({ }, handler)
        .build()

    private var holding = false
    private var disabled = false
    private var mediaPlayersBeforeDip = 0
    private val release = Runnable { abandon() }
    private val pauseWatch = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (holding && mediaPlayers(configs) < mediaPlayersBeforeDip) {
                Log.i(TAG, "A media player paused instead of ducking; turning smart dip off for this call")
                disabled = true
                abandon()
                onPausesInsteadOfDucking()
            }
        }
    }

    init {
        audioManager.registerAudioPlaybackCallback(pauseWatch, handler)
    }

    /** Call with the voice detector's state changes. */
    fun onRemoteSpeaking(speaking: Boolean) {
        handler.post {
            if (disabled) return@post
            if (speaking) {
                handler.removeCallbacks(release)
                if (!holding) dip()
            } else if (holding) {
                // Stay dipped through short pauses so the music doesn't pump between sentences.
                handler.postDelayed(release, HOLD_AFTER_SPEECH_MS)
            }
        }
    }

    fun release() {
        handler.post {
            handler.removeCallbacks(release)
            abandon()
            audioManager.unregisterAudioPlaybackCallback(pauseWatch)
        }
    }

    private fun dip() {
        val players = mediaPlayers(audioManager.activePlaybackConfigurations)
        if (players == 0) return // Nothing to dip.
        mediaPlayersBeforeDip = players
        holding = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandon() {
        if (!holding) return
        holding = false
        audioManager.abandonAudioFocusRequest(request)
    }

    /** Music players other than us: the call itself is labelled game audio, not media. */
    private fun mediaPlayers(configs: List<AudioPlaybackConfiguration>): Int =
        configs.count { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }

    private companion object {
        const val TAG = "EarshotDuck"
        const val HOLD_AFTER_SPEECH_MS = 1_500L
    }
}
