package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.audio.LatencyProbe
import io.github.nomskis.earshot.dsp.TimeMap
import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.settings.DelayRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LipSyncTest {
    @Test
    fun holdsVideoBackByWhatWebRtcDoesNotKnowAbout() {
        // 250 ms app-to-ear; WebRTC assumes 75 ms, the display takes ~20 ms.
        val plan = LipSync.plan(onBluetooth = true, measuredMs = 250.0, estimatedMs = 400.0)!!
        assertEquals(155, plan.videoDelayMs)
        assertEquals(LipSync.Source.MEASURED, plan.source) // a measurement beats Android's belief
        assertEquals(LipSync.Source.ESTIMATED, LipSync.plan(true, null, 200.0)!!.source)
    }

    @Test
    fun leavesFastOrNonBluetoothOutputAlone() {
        assertNull(LipSync.plan(onBluetooth = false, measuredMs = 300.0, estimatedMs = null))
        assertNull(LipSync.plan(onBluetooth = true, measuredMs = 110.0, estimatedMs = null)) // 15 ms: imperceptible
        assertNull(LipSync.plan(onBluetooth = true, measuredMs = null, estimatedMs = null))
        assertEquals(LipSync.MAX_MS, LipSync.plan(true, 2_000.0, null)!!.videoDelayMs)
    }

    @Test
    fun picksTheMeasurementThatMatchesTheSetupNow() {
        val run = { label: String, ms: Double, codec: String?, at: Long ->
            DelayRun(device = "Buds", label = label, delayMs = ms, codec = codec, atMillis = at)
        }
        val runs = listOf(
            run("Normal", 260.0, "LHDC 48 kHz", 1),
            run(DelayRuns.GAME_MODE_LABEL, 120.0, "LHDC 48 kHz", 2),
            run("Codec: AAC", 210.0, "AAC", 3),
            DelayRun(device = "Other", label = "Normal", delayMs = 90.0, atMillis = 4),
        )
        assertEquals(120.0, DelayRuns.bestMatch(runs, "Buds", gameModeOn = true, codec = "LHDC 48 kHz", gameAudio = true)!!.delayMs, 0.0)
        assertEquals(260.0, DelayRuns.bestMatch(runs, "Buds", gameModeOn = false, codec = "LHDC 48 kHz 24-bit", gameAudio = true)!!.delayMs, 0.0)
        assertEquals(210.0, DelayRuns.bestMatch(runs, "Buds", gameModeOn = false, codec = "AAC", gameAudio = true)!!.delayMs, 0.0)
        // Unknown codec: the latest run for these earbuds that matches the game mode state.
        assertEquals(210.0, DelayRuns.bestMatch(runs, "Buds", gameModeOn = false, codec = null, gameAudio = true)!!.delayMs, 0.0)
        assertNull(DelayRuns.bestMatch(runs, "Missing", false, null, true))
    }

    @Test
    fun probeLatencyIsWhenWrittenAudioPlaysMinusWhenItWasWritten() {
        // Playback clock: frame f plays at 1e9 + f / 48 kHz.
        val frames = (0..10).map { it * 4_800L }
        val nanos = frames.map { 1_000_000_000L + it * 1_000_000_000L / 48_000 }
        val map = TimeMap.fit(frames, nanos, 48_000)!!
        // Every write happens 180 ms before the frame just written gets played.
        val writes = (1..20).map { i ->
            val frame = i * 2_400L
            frame to (1_000_000_000L + frame * 1_000_000_000L / 48_000 - 180_000_000L)
        }
        assertEquals(180.0, LatencyProbe.latencyFromWrites(map, writes)!!, 0.5)
    }
}
