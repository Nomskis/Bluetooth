package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.call.MediaBudget.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBudgetTest {
    // The scenarios below are worked out for 10 ms packets, the most per-packet overhead;
    // a call starts at 20 ms and the real rate is measured (packetsPerSecond).
    private val budget = MediaBudget(redundancy = 3).apply { packetsPerSecond = 100.0 }
    private var now = 0L

    /** One 2 s stats interval; by default the voice sends what its level costs. */
    private fun tick(available: Double?, audio: Double? = budget.wireBps(budget.level), video: Boolean = true): MediaBudget.Changes {
        now += 2_000
        return budget.update(available, audio, video, now)
    }

    @Test
    fun hdVoiceWithItsCopiesIsAboutAQuarterMegabit() {
        assertEquals(249_000.0, budget.wireBps(Level.FULL), 2_000.0)
        assertEquals(137_000.0, budget.wireBps(Level.LOW), 2_000.0)
        // At the 20 ms packets a call starts with, half the headers.
        val fresh = MediaBudget(redundancy = 3)
        assertEquals(50.0, fresh.packetsPerSecond, 0.0)
        assertEquals(220_000.0, fresh.wireBps(Level.FULL), 2_000.0)
    }

    @Test
    fun aGoodConnectionTouchesNothing() {
        repeat(100) { assertEquals(MediaBudget.Changes(), tick(2_000_000.0)) }
        assertEquals(Level.FULL, budget.level)
        assertNull(budget.voiceCapBps)
        assertNull(budget.videoCapBps)
        assertFalse(budget.videoPaused)
    }

    @Test
    fun videoGetsWhatTheVoiceReallyLeaves() {
        // WebRTC would hand video 800 kbps minus the ~100 it reserves for voice; the voice really sends ~250.
        assertTrue(tick(800_000.0).video)
        val cap = budget.videoCapBps!!
        assertEquals(800_000 / 1.1 - budget.wireBps(Level.FULL), cap.toDouble(), 10_000.0)
        // Small moves of the estimate don't touch the encoder; real ones do.
        assertFalse(tick(810_000.0).video)
        assertTrue(tick(1_000_000.0).video)
        assertTrue(budget.videoCapBps!! > cap)
        // Plenty of room: no cap at all.
        assertTrue(tick(3_000_000.0).video)
        assertNull(budget.videoCapBps)
        assertEquals(Level.FULL, budget.level)
    }

    @Test
    fun aStarvedConnectionStepsTheVoiceDownBeforeTouchingVideo() {
        assertFalse(tick(300_000.0).voice) // one bad interval could be a blip
        assertTrue(tick(300_000.0).voice)
        assertEquals(Level.REDUCED, budget.level)
        assertEquals(32_000, budget.voiceCapBps)
        // Still too little for that plus some video: down again.
        assertFalse(tick(250_000.0).voice)
        assertTrue(tick(250_000.0).voice)
        assertEquals(Level.LOW, budget.level)
        assertEquals(20_000, budget.voiceCapBps)
        // Video keeps going on what's left.
        repeat(5) { tick(250_000.0) }
        assertFalse(budget.videoPaused)
        assertEquals(Level.LOW, budget.level)
    }

    @Test
    fun whenEvenTheLeanestVoiceLeavesTooLittleVideoPauses() {
        tick(250_000.0); tick(250_000.0); tick(250_000.0); tick(250_000.0)
        assertEquals(Level.LOW, budget.level)
        assertFalse(tick(180_000.0).video) // one bad interval could be a blip
        assertTrue(tick(180_000.0).video)
        assertTrue(budget.videoPaused)
        assertNull(budget.videoCapBps)
        // The voice itself stays where it fits, and doesn't go below LOW.
        repeat(5) { tick(100_000.0, video = true) }
        assertEquals(Level.LOW, budget.level)
    }

    @Test
    fun aSuddenCollapsePausesVideoWithinFourSeconds() {
        tick(2_000_000.0)
        tick(170_000.0)
        assertTrue(tick(170_000.0).video)
        assertTrue(budget.videoPaused)
        // And then the voice comes down to what fits on its own.
        repeat(6) { tick(170_000.0) }
        assertEquals(Level.LOW, budget.level)
    }

    @Test
    fun pausedVideoComesBackAsAProbeAndAFailedOneWaitsLonger() {
        tick(150_000.0); tick(150_000.0)
        assertTrue(budget.videoPaused)
        val pausedAt = now
        // The estimate stays low while only the voice is sent; video is tried again after a while.
        var resumedAt = -1L
        while (resumedAt < 0) if (tick(150_000.0).video && !budget.videoPaused) resumedAt = now
        assertTrue(resumedAt - pausedAt >= MediaBudget.MIN_HOLD_MS)
        // It gets a moment for the estimate to grow with it, then doesn't hold.
        assertFalse(tick(150_000.0).video)
        assertFalse(tick(150_000.0).video)
        var pausedAgainAt = -1L
        while (pausedAgainAt < 0) if (tick(150_000.0).video && budget.videoPaused) pausedAgainAt = now
        assertTrue(pausedAgainAt - resumedAt >= MediaBudget.PROBE_GRACE_MS)
        // So the next try waits twice as long.
        var secondResume = -1L
        while (secondResume < 0) if (tick(150_000.0).video && !budget.videoPaused) secondResume = now
        assertTrue(secondResume - pausedAgainAt >= 2 * MediaBudget.MIN_HOLD_MS)
    }

    @Test
    fun videoThatComesBackToRoomStays() {
        tick(150_000.0); tick(150_000.0)
        assertTrue(budget.videoPaused)
        while (budget.videoPaused) tick(150_000.0)
        // The connection has recovered: video stays, and gets a cap from the real estimate.
        repeat(40) { tick(900_000.0) }
        assertFalse(budget.videoPaused)
        assertNotNull(budget.videoCapBps)
    }

    @Test
    fun withTheCameraOffThereIsNoVideoToShareWith() {
        tick(150_000.0); tick(150_000.0)
        assertTrue(budget.videoPaused)
        // Camera off: nothing paused or capped any more, and it starts afresh when it's back.
        assertTrue(tick(150_000.0, video = false).video)
        assertFalse(budget.videoPaused)
        assertNull(budget.videoCapBps)
        repeat(5) { tick(150_000.0, video = false) }
        assertFalse(budget.videoPaused)
    }

    @Test
    fun itComesBackOnceThereIsRoomAgain() {
        tick(250_000.0); tick(250_000.0); tick(250_000.0); tick(250_000.0)
        assertEquals(Level.LOW, budget.level)
        // Room again, but it waits a while before stepping up.
        var changedAt = -1L
        repeat(30) { if (tick(1_000_000.0).voice && changedAt < 0) changedAt = now }
        assertEquals(Level.FULL, budget.level)
        assertTrue(changedAt - 8_000 >= MediaBudget.MIN_HOLD_MS)
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
        while (firstUp < 0) if (tick(250_000.0, video = false).voice) firstUp = now
        assertEquals(Level.FULL, budget.level)
        val reducedAgainAt = run {
            tick(200_000.0, video = false)
            assertTrue(tick(200_000.0, video = false).voice)
            now
        }
        assertEquals(Level.REDUCED, budget.level)
        var secondUp = -1L
        while (secondUp < 0) if (tick(250_000.0, video = false).voice) secondUp = now
        assertTrue(secondUp - reducedAgainAt >= 2 * MediaBudget.MIN_HOLD_MS)
    }

    @Test
    fun withoutAnEstimateNothingChanges() {
        repeat(20) { assertEquals(MediaBudget.Changes(), tick(null)) }
        repeat(20) { assertEquals(MediaBudget.Changes(), tick(100_000.0, audio = null)) }
        assertEquals(Level.FULL, budget.level)
        assertFalse(budget.videoPaused)
    }

    @Test
    fun statsGiveTheEstimateAndWhatWasSent() {
        val report = mapOf(
            "T1" to CallStats.Entry("transport", mapOf("selectedCandidatePairId" to "P1")),
            "P1" to CallStats.Entry("candidate-pair", mapOf("state" to "succeeded", "availableOutgoingBitrate" to 812_000.0)),
            "A" to CallStats.Entry("outbound-rtp", mapOf("kind" to "audio", "bytesSent" to 1_000L, "headerBytesSent" to 500L, "packetsSent" to 20L)),
            "V" to CallStats.Entry("outbound-rtp", mapOf("kind" to "video", "bytesSent" to 9_000L, "headerBytesSent" to 1_000L, "packetsSent" to 12L)),
        )
        assertEquals(812_000.0, CallStats.availableOutgoingBitrate(report)!!, 0.0)
        assertEquals(1_500.0, CallStats.outboundBytes(report, "audio")!!, 0.0)
        assertEquals(10_000.0, CallStats.outboundBytes(report, "video")!!, 0.0)
        assertEquals(20.0, CallStats.outboundPackets(report, "audio")!!, 0.0)
        assertNull(CallStats.outboundBytes(emptyMap(), "audio"))
        assertNull(CallStats.outboundPackets(emptyMap(), "audio"))
    }
}
