package io.github.nomskis.earshot.audio

import android.media.AudioAttributes
import android.media.MediaRecorder
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.EchoCancellation

/**
 * Everything that decides how one call's audio is wired, worked out once when
 * the call starts.
 *
 * Hi-Fi mode is the core idea of Earshot. Android drags all audio into the
 * Bluetooth call link (HFP/SCO) as soon as an app (1) switches AudioManager
 * into communication mode or (2) records from a Bluetooth headset mic. Hi-Fi
 * mode does neither: playback is labelled as game/media audio and the
 * microphone is the phone's own, so the earbuds stay on A2DP and keep playing
 * music at full quality next to the call.
 */
data class AudioProfile(
    val mode: AudioMode,
    val playbackUsage: Int,
    val playbackContentType: Int,
    /** MediaRecorder.AudioSource preset used for the microphone. */
    val audioSource: Int,
    /** Pin the microphone to the phone's built-in mic so Bluetooth never gets involved. */
    val preferBuiltInMic: Boolean,
    /** Touch AudioManager mode / communication device. Only for the classic headset mode. */
    val useCallMode: Boolean,
    val hardwareEchoCanceler: Boolean,
    val hardwareNoiseSuppressor: Boolean,
    val softwareEchoCancellation: Boolean,
    val softwareNoiseSuppression: Boolean,
    val softwareAutoGain: Boolean,
    /** Use Android's fast playback path (PERFORMANCE_MODE_LOW_LATENCY) with an adaptive buffer. */
    val lowLatencyPlayback: Boolean,
) {
    val playbackAttributes: AudioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(playbackUsage)
            .setContentType(playbackContentType)
            .build()

    /** Value sent to the other side in media-state messages. */
    val wireName: String
        get() = when (mode) {
            AudioMode.HIFI -> "hifi"
            AudioMode.HEADSET -> "headset"
        }

    companion object {
        /** The attributes Hi-Fi playback uses; also used to ask Android where it would route. */
        val HIFI_PLAYBACK_ATTRIBUTES: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }

        /**
         * Hi-Fi only pays off with earbuds or headphones on: on the loudspeaker there's no
         * music link to keep, and playing the call as media leaves only WebRTC's software echo
         * control, which on Android is its weak mobile mode (webrtc_voice_engine.cc sets
         * echo_canceller.mobile_mode), so the other side heard themselves back. Without them,
         * the call goes the phone's own call path, with its echo canceller, like any calling app.
         */
        fun forCall(settings: AppSettings, route: AudioRoute): AudioProfile = when {
            settings.audioMode == AudioMode.HIFI && route.outputIsPersonal -> AudioProfile(
                mode = AudioMode.HIFI,
                // USAGE_GAME routes exactly like media (same audio strategy), but AudioFlinger
                // asks a capable Bluetooth stack for its low-latency mode while a fast GAME
                // track plays (frameworks/av Threads.cpp, MixerThread::setHalLatencyMode_l).
                playbackUsage = if (settings.gameAudioLabel) AudioAttributes.USAGE_GAME else AudioAttributes.USAGE_MEDIA,
                playbackContentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                audioSource = settings.micSource.androidSource,
                preferBuiltInMic = true,
                useCallMode = false,
                // Hardware effects are tuned for (and on many phones tied to) the call path.
                hardwareEchoCanceler = false,
                hardwareNoiseSuppressor = false,
                softwareEchoCancellation = when (settings.echoCancellation) {
                    // With earbuds in, the phone's mic can't hear the call, so there is no
                    // echo to cancel, and an echo canceller would only hurt your voice.
                    EchoCancellation.AUTO -> !route.outputIsPersonal
                    EchoCancellation.ON -> true
                    EchoCancellation.OFF -> false
                },
                softwareNoiseSuppression = settings.noiseSuppression,
                softwareAutoGain = settings.autoGainControl,
                lowLatencyPlayback = settings.lowLatencyPlayback,
            )

            else -> AudioProfile(
                mode = AudioMode.HEADSET,
                playbackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                playbackContentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                preferBuiltInMic = false,
                useCallMode = true,
                hardwareEchoCanceler = true,
                hardwareNoiseSuppressor = true,
                softwareEchoCancellation = settings.echoCancellation != EchoCancellation.OFF,
                softwareNoiseSuppression = settings.noiseSuppression,
                softwareAutoGain = settings.autoGainControl,
                lowLatencyPlayback = settings.lowLatencyPlayback,
            )
        }
    }
}
