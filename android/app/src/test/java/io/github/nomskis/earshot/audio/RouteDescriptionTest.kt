package io.github.nomskis.earshot.audio

import android.media.AudioManager
import io.github.nomskis.earshot.settings.AudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDescriptionTest {
    private val buds = OutputDevice(DeviceKind.BLUETOOTH_MUSIC, "realme Buds Air 8 Pro")
    private val normal = AudioRoute(buds, AudioManager.MODE_NORMAL, null, true)

    @Test
    fun earbudsOnTheMusicLinkAreTheGoodCase() {
        assertEquals(RouteQuality.BLUETOOTH_HIFI, normal.quality)
        val d = describeRoute(normal, AudioMode.HIFI)
        assertEquals(Tone.GOOD, d.tone)
        assertEquals("realme Buds Air 8 Pro", d.title)
    }

    @Test
    fun anotherAppInCallModeIsAWarning() {
        val whatsApp = normal.copy(audioManagerMode = AudioManager.MODE_IN_COMMUNICATION)
        assertTrue(whatsApp.callModeActive)
        assertEquals(RouteQuality.BLUETOOTH_CALL_QUALITY, whatsApp.quality)
        assertEquals(Tone.WARNING, describeRoute(whatsApp, AudioMode.HIFI, inCall = true).tone)
    }

    @Test
    fun ourOwnHeadsetCallIsExpected() {
        val sco = OutputDevice(DeviceKind.BLUETOOTH_CALL, "realme Buds Air 8 Pro")
        val route = normal.copy(audioManagerMode = AudioManager.MODE_IN_COMMUNICATION, communicationDevice = sco)
        assertEquals(Tone.NEUTRAL, describeRoute(route, AudioMode.HEADSET, inCall = true).tone)
    }

    @Test
    fun leAudioAndWiredAreGood() {
        val le = normal.copy(mediaOutput = OutputDevice(DeviceKind.BLUETOOTH_LE, "LE buds"))
        assertEquals(Tone.GOOD, describeRoute(le, AudioMode.HIFI).tone)
        val wired = normal.copy(mediaOutput = OutputDevice(DeviceKind.WIRED, "Wired headphones"))
        assertEquals(RouteQuality.WIRED, wired.quality)
        assertTrue(wired.outputIsPersonal)
    }

    @Test
    fun speakerIsNeutral() {
        val speaker = AudioRoute(OutputDevice(DeviceKind.SPEAKER, "Phone speaker"), AudioManager.MODE_NORMAL, null, false)
        assertEquals(Tone.NEUTRAL, describeRoute(speaker, AudioMode.HIFI).tone)
        assertEquals(false, speaker.outputIsPersonal)
    }
}
