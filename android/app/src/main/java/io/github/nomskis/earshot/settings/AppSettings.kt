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
    /** Flip: your video mirrored left to right, the same for you and the other person. */
    val flip: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Ring when someone calls you, like a phone (keeps a connection to your server). */
    val receiveCalls: Boolean = true,
    /** Turn the screen off when the proximity sensor is covered (in a pocket), so it can't be tapped by accident. */
    val pocketGuard: Boolean = true,
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
    /**
     * Switch the earbuds' own game mode on for the call (and back after), on
     * brands Earshot has a driver for. Off by default: it talks to the
     * earbuds over their companion-app channel.
     */
    val autoGameMode: Boolean = false,
    /**
     * On 2.4 GHz Wi-Fi, which shares the phone's radio with Bluetooth, keep
     * the call's video lighter so the earbuds get more airtime.
     */
    val bluetoothFriendlyVideo: Boolean = true,
    /**
     * On 2.4 GHz Wi-Fi, carry the call over mobile data instead, freeing the
     * shared radio for the earbuds entirely. Uses your data plan.
     */
    val mobileDataOn24GHz: Boolean = false,
    /**
     * Opt-in. Keep mobile data ready next to Wi-Fi during calls, so the call
     * can move there when Wi-Fi stalls or keeps dropping packets. Off, the
     * call never touches mobile data while Wi-Fi is connected (like any other
     * app, it uses mobile data only when that's the phone's only network).
     */
    val mobileDataBackup: Boolean = false,
    /** With Shizuku set up: Turbo's codec, buffer and low-latency switches for each call, undone after. */
    val turboDuringCalls: Boolean = true,
    /** Hold her video back to match the Bluetooth audio delay, so lips match words. */
    val lipSync: Boolean = true,
    /** The home screen's "your earbuds have a game mode" suggestion was answered. */
    val gameModeHintDone: Boolean = false,
    /** The "keep calls alive with the screen off" guide was finished or dismissed. */
    val backgroundGuideDone: Boolean = false,
    /** Went through the phone maker's call switches (lock screen, pop-ups) that Android can't check. */
    val callSetupDone: Boolean = false,
    /** Extra gain for the other person's voice, 1.0 = unchanged. */
    val voiceVolume: Float = 1f,
    val lastRoom: String = "",
)
