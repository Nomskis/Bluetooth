package io.github.nomskis.earshot.dsp

import io.github.nomskis.earshot.dsp.SonarTestSignals.RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A complete simulated measurement. The fake phone has a microphone whose
 * timestamps run [MIC_ERROR_MS] late, a speaker Android times correctly, and
 * earbuds that are really [EARBUD_MS] slow but tell Android [REPORTED_MS].
 */
class SonarAnalysisTest {
    private companion object {
        const val SPEAKER_MS = 14.0
        const val EARBUD_MS = 187.3
        const val REPORTED_MS = 150.0
        const val MIC_ERROR_MS = 4.0
        const val NS_PER_FRAME = 1e9 / RATE
    }

    private val recordStartNanos = 1_000_000_000_000L
    private val recording = SonarTestSignals.background(RATE * 6, noise = 0.08)

    /** Frame in the recording where a sound that truly arrived at [nanos] lands. */
    private fun recordFrame(nanos: Double) = (nanos - recordStartNanos) / NS_PER_FRAME

    /** The recorder's timestamps, which (like many phones) are off by a few ms. */
    private fun recordMap(): TimeMap {
        val frames = (0 until 60).map { it * 4_800L }
        val nanos = frames.map { (recordStartNanos + it * NS_PER_FRAME + MIC_ERROR_MS * 1e6).toLong() }
        return TimeMap.fit(frames, nanos, RATE)!!
    }

    /** Plays [count] chirps starting at [startMs] after recording began. */
    private fun phase(startMs: Double, count: Int, trueDelayMs: Double, reportedDelayMs: Double, gain: Double): SonarPhase {
        val emissions = (0 until count).map { i ->
            val handedOver = recordStartNanos + ((startMs + i * 600) * 1e6).toLong()
            Emission(frame = i * 28_800L, handedOverAtNanos = handedOver)
        }
        emissions.forEach { e ->
            SonarTestSignals.addChirp(recording, recordFrame(e.handedOverAtNanos + trueDelayMs * 1e6), gain)
        }
        // Android's idea of when each frame plays: handed over + reported delay.
        val trackFrames = emissions.map { it.frame }
        val trackNanos = emissions.map { (it.handedOverAtNanos + reportedDelayMs * 1e6).toLong() }
        return SonarPhase(emissions, TimeMap.fit(trackFrames, trackNanos, RATE))
    }

    @Test
    fun measuresTheTrueEarbudDelayAndHowWrongAndroidIs() {
        val speaker = phase(startMs = 300.0, count = 3, trueDelayMs = SPEAKER_MS, reportedDelayMs = SPEAKER_MS, gain = 0.5)
        val earbuds = phase(startMs = 2_400.0, count = 5, trueDelayMs = EARBUD_MS, reportedDelayMs = REPORTED_MS, gain = 0.25)

        val filter = MatchedFilter(recording, Chirp.generate(RATE))
        val map = recordMap()
        val speakerHits = SonarAnalysis.detect(filter, map, speaker)
        val earbudHits = SonarAnalysis.detect(filter, map, earbuds)
        assertEquals(3, speakerHits.size)
        assertEquals(5, earbudHits.size)

        val summary = SonarAnalysis.summarize(speakerHits, earbudHits, earbudAttempts = 5)
        assertNotNull(summary)
        summary!!
        assertTrue(summary.calibrated)
        assertEquals(MIC_ERROR_MS, summary.micErrorMs, 0.2)
        assertEquals(EARBUD_MS, summary.delayMs, 0.3)
        assertEquals(REPORTED_MS, summary.reportedMs!!, 0.1)
        assertTrue("spread ${summary.spreadMs}", summary.spreadMs < 1.0)
    }

    @Test
    fun withoutCalibrationTheMicErrorIsNotRemoved() {
        val earbuds = phase(startMs = 1_000.0, count = 5, trueDelayMs = EARBUD_MS, reportedDelayMs = REPORTED_MS, gain = 0.25)
        val hits = SonarAnalysis.detect(MatchedFilter(recording, Chirp.generate(RATE)), recordMap(), earbuds)
        assertEquals(5, hits.size)
        val summary = SonarAnalysis.summarize(emptyList(), hits, earbudAttempts = 5)!!
        assertFalse(summary.calibrated)
        // The mic's late timestamps make the uncorrected delay look longer.
        assertEquals(EARBUD_MS + MIC_ERROR_MS, summary.delayMs, 0.3)
    }

    @Test
    fun aDelayLongerThanTheChirpSpacingIsNotMistakenForTheNextChirp() {
        // 450 ms delay with 600 ms spacing: each window must still pick its own chirp.
        val earbuds = phase(startMs = 500.0, count = 4, trueDelayMs = 450.0, reportedDelayMs = 300.0, gain = 0.25)
        val hits = SonarAnalysis.detect(MatchedFilter(recording, Chirp.generate(RATE)), recordMap(), earbuds)
        assertEquals(4, hits.size)
        hits.forEach { assertEquals(450.0 + MIC_ERROR_MS, it.handoffToHeardMs, 0.3) }
    }

    @Test
    fun nothingHeardMeansNoResult() {
        val silentPhase = SonarPhase(listOf(Emission(0, recordStartNanos + 500_000_000)), trackMap = null)
        val hits = SonarAnalysis.detect(MatchedFilter(recording, Chirp.generate(RATE)), recordMap(), silentPhase)
        assertTrue(hits.isEmpty())
        assertNull(SonarAnalysis.summarize(emptyList(), hits, earbudAttempts = 1))
    }
}
