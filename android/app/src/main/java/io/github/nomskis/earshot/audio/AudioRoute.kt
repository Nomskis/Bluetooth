package io.github.nomskis.earshot.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class DeviceKind {
    BLUETOOTH_MUSIC,
    BLUETOOTH_LE,
    BLUETOOTH_CALL,
    HEARING_AID,
    WIRED,
    USB,
    SPEAKER,
    EARPIECE,
    OTHER,
    ;

    /** Something worn on or in the ears, where echo from the phone's mic is not a concern. */
    val isPersonal: Boolean
        get() = this == BLUETOOTH_MUSIC || this == BLUETOOTH_LE || this == BLUETOOTH_CALL ||
            this == HEARING_AID || this == WIRED || this == USB

    companion object {
        fun fromType(type: Int): DeviceKind = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> BLUETOOTH_MUSIC
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> BLUETOOTH_CALL
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> BLUETOOTH_LE
            AudioDeviceInfo.TYPE_HEARING_AID -> HEARING_AID
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG -> WIRED
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> USB
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> EARPIECE
            else -> OTHER
        }
    }
}

data class OutputDevice(val kind: DeviceKind, val name: String)

/** What the app can tell the user about audio quality right now. */
enum class RouteQuality {
    /** Bluetooth on the high-quality music link (A2DP). The goal. */
    BLUETOOTH_HIFI,

    /** LE Audio, which handles music and a microphone well at the same time. */
    BLUETOOTH_LE_AUDIO,

    /** Bluetooth squeezed into the narrow call link (HFP/SCO). */
    BLUETOOTH_CALL_QUALITY,

    /** Wired or USB headphones: full quality, no Bluetooth involved. */
    WIRED,

    /** Playing from the phone itself. */
    PHONE_SPEAKER,

    UNKNOWN,
}

/**
 * Snapshot of where audio goes.
 *
 * @property mediaOutput the device Android picks for our call playback (media usage).
 * @property audioManagerMode AudioManager.getMode(); anything but MODE_NORMAL means
 *   some app has put the phone in call mode, which drags all audio with it.
 * @property communicationDevice the device used for calls (API 31+), if any.
 */
@SuppressLint("InlinedApi") // Newer device-type constants are plain ints, safe to compare on any version.
data class AudioRoute(
    val mediaOutput: OutputDevice?,
    val audioManagerMode: Int,
    val communicationDevice: OutputDevice?,
    val bluetoothMusicAvailable: Boolean,
) {
    val callModeActive: Boolean
        get() = audioManagerMode == AudioManager.MODE_IN_CALL ||
            audioManagerMode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManagerMode == AudioManager.MODE_CALL_SCREENING ||
            audioManagerMode == MODE_CALL_REDIRECT ||
            audioManagerMode == MODE_COMMUNICATION_REDIRECT

    val quality: RouteQuality
        get() {
            val output = if (callModeActive) communicationDevice ?: mediaOutput else mediaOutput
            return when (output?.kind) {
                DeviceKind.BLUETOOTH_MUSIC -> if (callModeActive) RouteQuality.BLUETOOTH_CALL_QUALITY else RouteQuality.BLUETOOTH_HIFI
                DeviceKind.BLUETOOTH_CALL -> RouteQuality.BLUETOOTH_CALL_QUALITY
                DeviceKind.BLUETOOTH_LE -> RouteQuality.BLUETOOTH_LE_AUDIO
                DeviceKind.WIRED, DeviceKind.USB, DeviceKind.HEARING_AID -> RouteQuality.WIRED
                DeviceKind.SPEAKER, DeviceKind.EARPIECE -> RouteQuality.PHONE_SPEAKER
                DeviceKind.OTHER, null -> RouteQuality.UNKNOWN
            }
        }

    /** True when the call will be heard through something worn, so echo is unlikely. */
    val outputIsPersonal: Boolean
        get() = mediaOutput?.kind?.isPersonal == true

    companion object {
        // AudioManager.MODE_CALL_REDIRECT / MODE_COMMUNICATION_REDIRECT (API 33).
        private const val MODE_CALL_REDIRECT = 5
        private const val MODE_COMMUNICATION_REDIRECT = 6

        val Unknown = AudioRoute(null, AudioManager.MODE_NORMAL, null, false)
    }
}
