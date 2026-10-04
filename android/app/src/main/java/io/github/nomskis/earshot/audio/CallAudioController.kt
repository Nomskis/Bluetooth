package io.github.nomskis.earshot.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
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
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private var active = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var startedSco = false
    /** The device [begin] routed the call to (Android 12+). */
    private var chosen: AudioDeviceInfo? = null

    fun begin(profile: AudioProfile) {
        if (!profile.useCallMode || active) return
        active = true
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = pickCommunicationDevice(audioManager.availableCommunicationDevices)
            if (device != null && !audioManager.setCommunicationDevice(device)) {
                Log.w(TAG, "Could not route the call to ${device.productName}")
            } else {
                chosen = device
            }
        } else {
            beginLegacyRouting()
        }
    }

    /**
     * The call was routed to Bluetooth earbuds' call link (SCO) and it isn't up yet. Android
     * starts it in the background and, until then, plays the call on the earpiece and records
     * from the phone's mic; that takes up to a second or two.
     */
    @Suppress("DEPRECATION")
    fun awaitingBluetoothRoute(): Boolean = when {
        !active -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            chosen?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                audioManager.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        // A sticky broadcast: registering for it with no receiver returns the current state.
        else -> startedSco && appContext.registerReceiver(null, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            ?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED) !=
            AudioManager.SCO_AUDIO_STATE_CONNECTED
    }

    fun end() {
        endEarbudMic()
        if (!active) return
        active = false
        chosen = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            endLegacyRouting()
        }
        audioManager.mode = previousMode
    }

    private var earbudMic = false

    /**
     * Hi-Fi call, temporarily on the earbuds' microphone: communication mode
     * with the Bluetooth headset as the communication device. The earbuds
     * drop to the call link (and call quality) until [endEarbudMic].
     */
    fun beginEarbudMic(): Boolean {
        if (active || earbudMic) return earbudMic
        val headset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: return false
        } else {
            null
        }
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (headset != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !audioManager.setCommunicationDevice(headset)) {
                audioManager.mode = previousMode
                return false
            }
        } else {
            beginLegacyRouting()
        }
        earbudMic = true
        return true
    }

    fun endEarbudMic() {
        if (!earbudMic) return
        earbudMic = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice() else endLegacyRouting()
        audioManager.mode = previousMode
    }

    /** True when a Bluetooth headset can carry a call (so the earbud mic is an option). */
    fun earbudMicAvailable(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.availableCommunicationDevices.any {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
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
