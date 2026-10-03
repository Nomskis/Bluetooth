package io.github.nomskis.earshot.turbo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboDiagnosticsTest {
    // Shaped exactly like AOSP's dump output.
    private val bluetooth = """
        Profile: A2dpService
          ...
        A2DP Codecs State:
          Current Codec: None
        A2DP Peer: AA:BB:CC:DD:EE:FF
            Delay Reporting: 1650 (in 1/10 milliseconds) 
            Codec Preferred: LDAC
        A2DP Codecs State:
          Current Codec: LDAC
    """.trimIndent()

    private val audioFlinger = """
        Output thread 0x7a1, name AudioOut_D, tid 1201, type 0 (MIXER):
          I/O handle: 13
          Standby: no
          Sample rate: 48000 Hz
          HAL frame count: 192
          Output devices: 0x2 (AUDIO_DEVICE_OUT_SPEAKER)
          FastMixer command=COLD_IDLE
        Bluetooth latency modes are enabled
        HAL does not support Bluetooth latency modes
        Supported latency modes: { }

        Output thread 0x7b2, name AudioOut_2D, tid 1388, type 0 (MIXER):
          I/O handle: 45
          Standby: no
          Sample rate: 48000 Hz
          HAL frame count: 960
          Output devices: 0x80 (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)
          No FastMixer
        Bluetooth latency modes are enabled
        HAL does support Bluetooth latency modes
        Supported latency modes: { FREE, LOW }
    """.trimIndent()

    @Test
    fun readsTheBluetoothOutputThread() {
        val d = TurboDiagnostics.parse(bluetooth, audioFlinger)
        assertEquals("LDAC", d.codec)
        assertEquals(165.0, d.reportedDelayMs!!, 1e-9)
        assertEquals(20.0, d.halBufferMs!!, 1e-9)
        assertEquals(false, d.hasFastMixer)
        assertEquals(true, d.latencyModesEnabled)
        assertEquals(true, d.halSupportsLatencyModes)
        assertEquals(listOf("FREE", "LOW"), d.supportedLatencyModes)
        // No fast mixer on the Bluetooth thread: a GAME track can't be fast, so no LOW request.
        assertEquals(false, d.gameLabelCanLowerLatency)
    }

    @Test
    fun recognisesAPhoneWhereTheGameLabelWorks() {
        val d = TurboDiagnostics.parse(bluetooth, audioFlinger.replace("  No FastMixer", "  FastMixer command=MIX_WRITE"))
        assertEquals(true, d.hasFastMixer)
        assertTrue(d.gameLabelCanLowerLatency == true)
    }

    @Test
    fun copesWithMissingSections() {
        val d = TurboDiagnostics.parse("", "")
        assertNull(d.codec)
        assertNull(d.hasFastMixer)
        assertNull(d.gameLabelCanLowerLatency)
        assertTrue(d.supportedLatencyModes.isEmpty())
    }

    @Test
    fun parsesCodecStatus() {
        val s = TurboDiagnostics.parseCodecStatus("4:2:2:1003|0,1,4")!!
        assertEquals(4, s.codecType)
        assertEquals(1003L, s.codecSpecific1)
        assertEquals(listOf(0, 1, 4), s.selectableTypes)
        assertNull(TurboDiagnostics.parseCodecStatus(""))
        assertNull(TurboDiagnostics.parseCodecStatus("garbage"))
        assertFalse(TurboDiagnostics.parseCodecStatus("1:1:1:0|")!!.selectableTypes.isNotEmpty())
    }
}
