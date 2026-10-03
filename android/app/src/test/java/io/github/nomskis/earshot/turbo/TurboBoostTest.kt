package io.github.nomskis.earshot.turbo

import io.github.nomskis.earshot.audio.Codecs
import io.github.nomskis.earshot.settings.DelayRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurboBoostTest {
    private fun run(label: String, ms: Double, device: String = "Buds") = DelayRun(device = device, label = label, delayMs = ms, atMillis = 0)

    private val sbc = 0
    private val aac = 1
    private val ldac = 4

    @Test
    fun readsCodecLabels() {
        assertEquals(aac, TurboBoost.codecOfLabel("Codec: AAC (auto)"))
        assertEquals(sbc, TurboBoost.codecOfLabel("Codec: SBC"))
        assertEquals(9, TurboBoost.codecOfLabel("Codec: Codec 9 (auto)")) // a vendor codec such as LHDC
        assertNull(TurboBoost.codecOfLabel("Codec: LDAC/LHDC/aptX")) // the tuner's grouped label
        assertNull(TurboBoost.codecOfLabel("Game mode on"))
        assertEquals(3, Codecs.typeOf("aptX HD"))
    }

    @Test
    fun switchesOnlyForAClearWin() {
        val runs = listOf(run("Codec: LDAC (auto)", 260.0), run("Codec: AAC (auto)", 190.0), run("Codec: SBC (auto)", 200.0))
        assertEquals(aac, TurboBoost.callCodec(runs, "Buds", currentType = ldac, selectable = listOf(sbc, aac, ldac)))
        // AAC is 10 ms faster than SBC: not worth the dropout of a codec switch.
        assertNull(TurboBoost.callCodec(runs, "Buds", currentType = sbc, selectable = listOf(sbc, aac, ldac)))
        // Already on the fastest.
        assertNull(TurboBoost.callCodec(runs, "Buds", currentType = aac, selectable = listOf(sbc, aac, ldac)))
    }

    @Test
    fun neverPicksWhatThePhoneCannotSelectOrAnotherDevicesRuns() {
        val runs = listOf(run("Codec: SBC (auto)", 150.0), run("Codec: AAC (auto)", 90.0, device = "Other"))
        assertNull(TurboBoost.callCodec(runs, "Buds", currentType = aac, selectable = listOf(aac)))
        // The current codec was never measured: the measured fast one is the evidence.
        assertEquals(sbc, TurboBoost.callCodec(runs, "Buds", currentType = aac, selectable = emptyList()))
        assertNull(TurboBoost.callCodec(runs, null, currentType = aac, selectable = emptyList()))
    }
}
