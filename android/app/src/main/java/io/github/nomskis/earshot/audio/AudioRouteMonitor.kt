package io.github.nomskis.earshot.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches where audio is going, so the app can show (and you can verify) that
 * the earbuds really stay on the high-quality link during a call.
 */
@SuppressLint("InlinedApi") // Newer device-type constants are plain ints, safe to compare on any version.
class AudioRouteMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    val route: Flow<AudioRoute> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val refresh = { trySend(snapshot()) }

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refresh()
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        var unregisterListeners: () -> Unit = {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val executor = ContextCompat.getMainExecutor(appContext)
            val modeListener = AudioManager.OnModeChangedListener { refresh() }
            val commListener = AudioManager.OnCommunicationDeviceChangedListener { refresh() }
            audioManager.addOnModeChangedListener(executor, modeListener)
            audioManager.addOnCommunicationDeviceChangedListener(executor, commListener)
            unregisterListeners = {
                audioManager.removeOnModeChangedListener(modeListener)
                audioManager.removeOnCommunicationDeviceChangedListener(commListener)
            }
        }

        // Routing can also change without any callback (another app starting a
        // call, a codec switch), so poll gently as a safety net.
        val poller = launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }

        awaitClose {
            poller.cancel()
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            unregisterListeners()
        }
    }.distinctUntilChanged().conflate()

    fun snapshot(): AudioRoute {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Ask Android directly where audio with our exact attributes would play.
            audioManager.getAudioDevicesForAttributes(AudioProfile.HIFI_PLAYBACK_ATTRIBUTES).firstOrNull()
        } else {
            guessMediaOutput(outputs)
        }
        val communication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else {
            null
        }
        return AudioRoute(
            mediaOutput = media?.toOutputDevice(),
            audioManagerMode = audioManager.mode,
            communicationDevice = communication?.toOutputDevice(),
            bluetoothMusicAvailable = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP },
        )
    }

    private fun guessMediaOutput(outputs: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val priority = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        return priority.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}

internal fun AudioDeviceInfo.toOutputDevice(): OutputDevice {
    val kind = DeviceKind.fromType(type)
    val label = when (kind) {
        DeviceKind.SPEAKER -> "Phone speaker"
        DeviceKind.EARPIECE -> "Phone earpiece"
        else -> productName?.toString()?.takeIf { it.isNotBlank() } ?: when (kind) {
            DeviceKind.WIRED -> "Wired headphones"
            DeviceKind.USB -> "USB audio"
            else -> "Bluetooth device"
        }
    }
    return OutputDevice(kind, label)
}
