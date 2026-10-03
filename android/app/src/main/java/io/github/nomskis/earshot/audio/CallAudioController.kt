package io.github.nomskis.earshot.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Applies the system-wide audio state a call needs, and undoes it afterwards.
 *
 * In Hi-Fi mode this deliberately does nothing: leaving AudioManager in
 * MODE_NORMAL is what keeps Bluetooth on the music link. Only the classic
 * headset mode switches the phone into communication mode.
 */
@SuppressLint("InlinedApi") // Newer device-type constants are plain ints, safe to compare on any version.
class CallAudioController(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private var active = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var startedSco = false

    fun begin(profile: AudioProfile) {
        if (!profile.useCallMode || active) return
        active = true
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = pickCommunicationDevice(audioManager.availableCommunicationDevices)
            if (device != null && !audioManager.setCommunicationDevice(device)) {
                Log.w(TAG, "Could not route the call to ${device.productName}")
            }
        } else {
            beginLegacyRouting()
        }
    }

    fun end() {
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            endLegacyRouting()
        }
        audioManager.mode = previousMode
    }

    /** For a video call the loudspeaker beats the earpiece when nothing is plugged in. */
    private fun pickCommunicationDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val priority = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        return priority.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
    }

    @Suppress("DEPRECATION")
    private fun beginLegacyRouting() {
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetooth = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val hasWired = outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        when {
            hasBluetooth && audioManager.isBluetoothScoAvailableOffCall -> {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                startedSco = true
            }
            !hasWired -> audioManager.isSpeakerphoneOn = true
        }
    }

    @Suppress("DEPRECATION")
    private fun endLegacyRouting() {
        if (startedSco) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            startedSco = false
        }
        audioManager.isSpeakerphoneOn = previousSpeakerphone
    }

    private companion object {
        const val TAG = "EarshotAudio"
    }
}
