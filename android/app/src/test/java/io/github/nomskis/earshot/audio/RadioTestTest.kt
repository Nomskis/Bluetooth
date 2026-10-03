package io.github.nomskis.earshot.audio

import io.github.nomskis.earshot.dsp.SonarSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTestTest {
    private fun summary(ms: Double, hits: Int = 8, attempts: Int = 8) =
        SonarSummary(delayMs = ms, reportedMs = null, spreadMs = 5.0, hits = hits, attempts = attempts, calibrated = true, micErrorMs = 0.0)

    @Test
    fun recommendsMobileDataWhenTwoPointFourGigahertzTrafficHurts() {
        val v = RadioTest.verdict(summary(180.0), summary(240.0), WifiBand.GHZ_2_4)
        assertTrue(v.recommendMobileData)
        assertTrue(v.text.contains("60 ms more delay"))
    }

    @Test
    fun countsDropoutsAsHarmToo() {
        val v = RadioTest.verdict(summary(180.0), summary(185.0, hits = 4), WifiBand.GHZ_2_4)
        assertTrue(v.text.contains("dropouts"))
        assertTrue(v.recommendMobileData)
    }

    @Test
    fun saysWhenItMakesNoDifference() {
        val v = RadioTest.verdict(summary(180.0), summary(188.0), WifiBand.GHZ_5)
        assertFalse(v.recommendMobileData)
        assertTrue(v.text.startsWith("Wi-Fi traffic doesn't affect"))
        // Harm on 5 GHz is reported, but mobile data isn't the cure there.
        assertFalse(RadioTest.verdict(summary(180.0), summary(260.0), WifiBand.GHZ_5).recommendMobileData)
        assertFalse(RadioTest.verdict(null, summary(1.0), WifiBand.GHZ_2_4).recommendMobileData)
    }
}
