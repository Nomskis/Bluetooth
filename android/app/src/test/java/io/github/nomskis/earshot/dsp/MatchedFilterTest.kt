package io.github.nomskis.earshot.dsp

import io.github.nomskis.earshot.dsp.SonarTestSignals.RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchedFilterTest {
    private val template = Chirp.generate(RATE)

    @Test
    fun findsAChirpToAFractionOfASampleInNoiseAndMusic() {
        for (start in listOf(4_800.0, 12_345.25, 30_000.6)) {
            val recording = SonarTestSignals.background(RATE, noise = 0.1)
            SonarTestSignals.addChirp(recording, start, gain = 0.3)
            val peak = MatchedFilter(recording, template).findPeak(0, RATE)
            assertNotNull(peak)
            assertEquals(start, peak!!.position, 0.3)
            assertTrue(peak.score > 0.3)
        }
    }

    @Test
    fun stillWorksWhenTheChirpIsQuieterThanTheRoom() {
        val recording = SonarTestSignals.background(RATE, noise = 0.3)
        SonarTestSignals.addChirp(recording, 20_000.0, gain = 0.15)
        val peak = MatchedFilter(recording, template).findPeak(0, RATE, minScore = 0.1)
        assertNotNull(peak)
        assertEquals(20_000.0, peak!!.position, 1.0)
    }

    @Test
    fun reportsNothingWhenThereIsNoChirp() {
        val recording = SonarTestSignals.background(RATE, noise = 0.2)
        assertNull(MatchedFilter(recording, template).findPeak(0, RATE))
    }

    @Test
    fun onlySearchesTheGivenWindow() {
        val recording = SonarTestSignals.background(RATE, noise = 0.05)
        SonarTestSignals.addChirp(recording, 5_000.0, gain = 0.3)
        SonarTestSignals.addChirp(recording, 30_000.0, gain = 0.3)
        val filter = MatchedFilter(recording, template)
        assertEquals(30_000.0, filter.findPeak(20_000, 40_000)!!.position, 0.5)
        assertEquals(5_000.0, filter.findPeak(0, 10_000)!!.position, 0.5)
    }
}
