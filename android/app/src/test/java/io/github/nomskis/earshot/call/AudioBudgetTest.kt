package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.call.AudioBudget.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBudgetTest {
    private val budget = AudioBudget(redundancy = 3)
    private var now = 0L

    /** One 2 s stats interval. */
    private fun tick(available: Double?, audio: Double? = budget.wireBps(budget.level), video: Boolean = true): Boolean {
        now += 2_000
        return budget.update(available, audio, video, now)
    }

    @Test
    fun hdVoiceWithItsCopiesIsAboutAFifthOfAMegabit() {
        // 4 x 48 kbps of Opus, plus 50 packets a second of headers.
        assertEquals(220_000.0, budget.wireBps(Level.FULL), 2_000.0)
        assertEquals(108_000.0, budget.wireBps(Level.LOW), 2_000.0)
    }

    @Test
    fun aGoodConnectionNeverTouchesTheVoice() {
        repeat(100) { assertFalse(tick(2_000_000.0)) }
        assertEquals(Level.FULL, budget.level)
        assertNull(budget.capBps)
    }

    @Test
    fun aStarvedConnectionStepsTheVoiceDownAfterTwoIntervals() {
        assertFalse(tick(300_000.0)) // one bad interval could be a blip
        assertTrue(tick(300_000.0))
        assertEquals(Level.REDUCED, budget.level)
        assertEquals(32_000, budget.capBps)
        // Still too little for that plus some video: down again.
        assertFalse(tick(250_000.0))
        assertTrue(tick(250_000.0))
        assertEquals(Level.LOW, budget.level)
        assertEquals(20_000, budget.capBps)
        // And no further.
        repeat(10) { assertFalse(tick(100_000.0)) }
        assertEquals(Level.LOW, budget.level)
    }

    @Test
    fun itComesBackOnceThereIsRoomAgain() {
        tick(250_000.0); tick(250_000.0); tick(250_000.0); tick(250_000.0)
        assertEquals(Level.LOW, budget.level)
        // Room again, but it waits a while before stepping up.
        var changedAt = -1L
        repeat(30) { if (tick(1_000_000.0) && changedAt < 0) changedAt = now }
        assertEquals(Level.FULL, budget.level)
        assertTrue(changedAt - 8_000 >= AudioBudget.MIN_HOLD_MS)
    }

    @Test
    fun withVideoItWaitsForTheEstimateToShowRoomForTheNextStep() {
        tick(300_000.0); tick(300_000.0)
        assertEquals(Level.REDUCED, budget.level)
        // Fits where it is (185 kbps of voice + video room), not with HD voice.
        repeat(60) { tick(330_000.0) }
        assertEquals(Level.REDUCED, budget.level)
    }

    @Test
    fun aStepUpThatDoesntHoldMakesTheNextTryWaitLonger() {
        tick(300_000.0); tick(300_000.0)
        assertEquals(Level.REDUCED, budget.level)
        // Voice only: probing up by time alone.
        var firstUp = -1L
        while (firstUp < 0) if (tick(250_000.0, video = false)) firstUp = now
        assertEquals(Level.FULL, budget.level)
        val reducedAgainAt = run {
            tick(200_000.0, video = false)
            assertTrue(tick(200_000.0, video = false))
            now
        }
        assertEquals(Level.REDUCED, budget.level)
        var secondUp = -1L
        while (secondUp < 0) if (tick(250_000.0, video = false)) secondUp = now
        assertTrue(secondUp - reducedAgainAt >= 2 * AudioBudget.MIN_HOLD_MS)
    }

    @Test
    fun withoutAnEstimateNothingChanges() {
        repeat(20) { assertFalse(tick(null)) }
        repeat(20) { assertFalse(tick(100_000.0, audio = null)) }
        assertEquals(Level.FULL, budget.level)
    }

    @Test
    fun statsGiveTheEstimateAndWhatWasSent() {
        val report = mapOf(
            "T1" to CallStats.Entry("transport", mapOf("selectedCandidatePairId" to "P1")),
            "P1" to CallStats.Entry("candidate-pair", mapOf("state" to "succeeded", "availableOutgoingBitrate" to 812_000.0)),
            "A" to CallStats.Entry("outbound-rtp", mapOf("kind" to "audio", "bytesSent" to 1_000L, "headerBytesSent" to 500L)),
            "V" to CallStats.Entry("outbound-rtp", mapOf("kind" to "video", "bytesSent" to 9_000L, "headerBytesSent" to 1_000L)),
        )
        assertEquals(812_000.0, CallStats.availableOutgoingBitrate(report)!!, 0.0)
        assertEquals(1_500.0, CallStats.outboundBytes(report, "audio")!!, 0.0)
        assertEquals(10_000.0, CallStats.outboundBytes(report, "video")!!, 0.0)
        assertNull(CallStats.outboundBytes(emptyMap(), "audio"))
    }
}
