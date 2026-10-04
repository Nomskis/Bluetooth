package io.github.nomskis.earshot.calls

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/** How the phone should announce a call, the way its own phone app would. */
object RingPolicy {
    data class Ring(val sound: Boolean, val vibrate: Boolean)

    /** [ringerMode] is AudioManager's; [doNotDisturb] means calls are being held back. */
    fun decide(ringerMode: Int, doNotDisturb: Boolean): Ring = when {
        doNotDisturb -> Ring(sound = false, vibrate = false)
        ringerMode == AudioManager.RINGER_MODE_NORMAL -> Ring(sound = true, vibrate = true)
        ringerMode == AudioManager.RINGER_MODE_VIBRATE -> Ring(sound = false, vibrate = true)
        else -> Ring(sound = false, vibrate = false)
    }
}

/** Plays your phone's ringtone on repeat and vibrates until stopped. */
class Ringer(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start() {
        stop()
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val ring = RingPolicy.decide(audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL, dnd)
        if (ring.sound) playRingtone()
        if (ring.vibrate) vibrate()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun playRingtone() {
        val tone = RingtoneManager.getRingtone(context, Settings.System.DEFAULT_RINGTONE_URI) ?: return
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
        tone.play()
        ringtone = tone
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) keepLooping(tone)
    }

    /** Android 8 has no looping ringtones: start it again whenever it finishes. */
    private fun keepLooping(tone: Ringtone) {
        handler.postDelayed({
            if (ringtone !== tone) return@postDelayed
            if (!tone.isPlaying) tone.play()
            keepLooping(tone)
        }, 500)
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(PATTERN, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        }
        vibrator = v
    }

    private companion object {
        /** A second on, a second off, like a phone. */
        val PATTERN = longArrayOf(0, 1_000, 1_000)
    }
}
