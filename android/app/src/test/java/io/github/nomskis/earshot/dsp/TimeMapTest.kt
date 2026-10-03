package io.github.nomskis.earshot.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class TimeMapTest {
    @Test
    fun recoversAClockDespiteJitter() {
        val random = Random(3)
        val trueSlope = 1e9 / 48_000 * (1 + 40e-6) // runs 40 ppm slow
        val t0 = 5_000_000_000L
        val frames = (0 until 50).map { it * 960L + 100 }
        val nanos = frames.map { (t0 + it * trueSlope + random.nextDouble(-300_000.0, 300_000.0)).toLong() }
        val map = TimeMap.fit(frames, nanos, 48_000)!!
        // Inside the measured range the fit is far better than any single timestamp.
        val check = 24_000.0
        assertEquals(t0 + check * trueSlope, map.toNanos(check), 100_000.0)
        // Over one second with 0.3 ms jitter the rate itself is only good to a few Hz.
        assertEquals(48_000.0 / (1 + 40e-6), map.measuredSampleRate, 10.0)
        assertEquals(check, map.toFrame(map.toNanos(check)), 1e-6)
    }

    @Test
    fun fallsBackToTheNominalRateWithFewPoints() {
        val map = TimeMap.fit(listOf(0L, 10L), listOf(0L, 1_000L), 48_000)!!
        assertEquals(48_000.0, map.measuredSampleRate, 1e-6)
    }
}
