package io.github.nomskis.earshot.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.EchoCancellation
import io.github.nomskis.earshot.settings.MicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfileTest {
    private val earbuds = AudioRoute(
        mediaOutput = OutputDevice(DeviceKind.BLUETOOTH_MUSIC, "realme Buds Air 8 Pro"),
        audioManagerMode = AudioManager.MODE_NORMAL,
        communicationDevice = null,
        bluetoothMusicAvailable = true,
    )
    private val speaker = earbuds.copy(mediaOutput = OutputDevice(DeviceKind.SPEAKER, "Phone speaker"), bluetoothMusicAvailable = false)

    @Test
    fun hifiNeverTouchesCallMode() {
        val profile = AudioProfile.forCall(AppSettings(audioMode = AudioMode.HIFI), earbuds)
        // These together are what keep Bluetooth on A2DP. USAGE_GAME routes like media
        // but lets a capable Bluetooth stack switch to its low-latency mode.
        assertEquals(AudioAttributes.USAGE_GAME, profile.playbackUsage)
        assertTrue(profile.lowLatencyPlayback)
        assertFalse(profile.useCallMode)
        assertTrue(profile.preferBuiltInMic)
        assertEquals(MediaRecorder.AudioSource.MIC, profile.audioSource)
        assertFalse(profile.hardwareEchoCanceler)
        assertEquals("hifi", profile.wireName)
    }

    @Test
    fun mediaLabelCanBeChosenInstead() {
        val profile = AudioProfile.forCall(AppSettings(gameAudioLabel = false, lowLatencyPlayback = false), earbuds)
        assertEquals(AudioAttributes.USAGE_MEDIA, profile.playbackUsage)
        assertFalse(profile.lowLatencyPlayback)
    }

    @Test
    fun hifiUsesTheChosenMicSource() {
        val profile = AudioProfile.forCall(AppSettings(micSource = MicSource.CAMCORDER), earbuds)
        assertEquals(MediaRecorder.AudioSource.CAMCORDER, profile.audioSource)
    }

    @Test
    fun automaticEchoCancellationFollowsTheOutput() {
        assertFalse(AudioProfile.forCall(AppSettings(), earbuds).softwareEchoCancellation)
        assertTrue(AudioProfile.forCall(AppSettings(), speaker).softwareEchoCancellation)
        assertTrue(AudioProfile.forCall(AppSettings(echoCancellation = EchoCancellation.ON), earbuds).softwareEchoCancellation)
        assertFalse(AudioProfile.forCall(AppSettings(echoCancellation = EchoCancellation.OFF), speaker).softwareEchoCancellation)
    }

    @Test
    fun headsetModeIsAClassicCall() {
        val profile = AudioProfile.forCall(AppSettings(audioMode = AudioMode.HEADSET), earbuds)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, profile.playbackUsage)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, profile.audioSource)
        assertTrue(profile.useCallMode)
        assertFalse(profile.preferBuiltInMic)
        assertEquals("headset", profile.wireName)
    }
}
