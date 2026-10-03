package io.github.nomskis.earshot.settings

import android.media.MediaRecorder

/**
 * How call audio is wired. This choice is the reason the app exists.
 */
enum class AudioMode {
    /**
     * The other person's voice is played as ordinary media and your voice is
     * picked up by the phone's own microphone. Android never enters "call
     * mode", so Bluetooth earbuds stay on their high-quality music link
     * (A2DP) and your music keeps playing at full quality alongside the call.
     */
    HIFI,

    /**
     * A classic call: the earbuds' microphone is used, which forces Bluetooth
     * into the low-quality two-way "headset" link (HFP/SCO), exactly like
     * WhatsApp or a phone call. Useful when you want to talk a lot.
     */
    HEADSET,
}

/** Which Android capture preset feeds the microphone in Hi-Fi mode. */
enum class MicSource(val androidSource: Int) {
    /** Plain microphone input: no OEM call processing, never routed to Bluetooth. */
    MIC(MediaRecorder.AudioSource.MIC),

    /** Tuned for video recording; on many phones uses the mic next to the camera. */
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),

    /** Light processing tuned for speech recognition. */
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),

    /** Raw input with no processing at all, where the phone supports it. */
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED),

    /** The phone's call-tuned processing. May behave differently per manufacturer. */
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
}

enum class EchoCancellation {
    /** On when the call plays from the loudspeaker, off with earbuds or headphones. */
    AUTO,
    ON,
    OFF,
}

enum class VideoQuality(val width: Int, val height: Int, val fps: Int) {
    LOW(640, 360, 24),
    MEDIUM(960, 540, 30),
    HIGH(1280, 720, 30),
    FULL_HD(1920, 1080, 30),
}

data class AppSettings(
    /** Base URL of the Earshot server, e.g. https://calls.example.com */
    val serverUrl: String = "",
    val displayName: String = "",
    val audioMode: AudioMode = AudioMode.HIFI,
    val micSource: MicSource = MicSource.MIC,
    val echoCancellation: EchoCancellation = EchoCancellation.AUTO,
    val noiseSuppression: Boolean = true,
    val autoGainControl: Boolean = true,
    val videoQuality: VideoQuality = VideoQuality.HIGH,
    val startWithBackCamera: Boolean = false,
    val keepScreenOn: Boolean = true,
    /**
     * Label the call's audio as game audio (still routed like media). On phones
     * and earbuds whose Bluetooth stack supports it, Android then switches the
     * link to its low-latency mode automatically.
     */
    val gameAudioLabel: Boolean = true,
    /** Play through Android's low-latency (fast) path with a small, self-adjusting buffer. */
    val lowLatencyPlayback: Boolean = true,
    /** Light up the call screen as the other person starts talking, before you hear it. */
    val headStartCue: Boolean = true,
    /** Dip your music while the other person talks (Android ducks it for us). */
    val smartDuck: Boolean = true,
    /** Extra gain for the other person's voice, 1.0 = unchanged. */
    val voiceVolume: Float = 1f,
    val lastRoom: String = "",
)
