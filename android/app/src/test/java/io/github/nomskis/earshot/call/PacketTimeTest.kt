package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.call.PacketTime.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTimeTest {
    // Most of these follow the steps from the shortest packets; a call starts at 20 ms (below).
    private var packetTime = PacketTime(Step.SHORT)
    private var now = 0L
    private var received = 0.0
    private var lost = 0.0
    private var concealed = 0.0
    private var total = 0.0

    /** One 2 s stats interval of 48 kHz audio arriving in [packetTime]'s packets. */
    private fun tick(loss: Double, concealment: Double): Boolean {
        now += 2_000
        val packets = 2_000.0 / packetTime.ms
        lost += packets * loss
        received += packets * (1 - loss)
        total += 96_000.0
        concealed += 96_000.0 * concealment
        return packetTime.update(PacketTime.Counters(received, lost, concealed, total), now)
    }

    @Test
    fun aCallStartsAtTwentyMillisecondsAndAsksForTenOnlyOnceItsCalm() {
        packetTime = PacketTime()
        assertEquals(20, packetTime.ms)
        assertFalse(tick(0.0, 0.0)) // the first report only sets the baseline
        var downAt = -1L
        val calmFrom = now
        while (downAt < 0) if (tick(0.0, 0.0)) downAt = now
        assertEquals(10, packetTime.ms)
        assertTrue(downAt - calmFrom >= PacketTime.CALM_MS)
    }

    @Test
    fun aRoughStartGoesStraightToFortyMilliseconds() {
        packetTime = PacketTime()
        tick(0.0, 0.0)
        tick(0.06, 0.03)
        assertTrue(tick(0.06, 0.03))
        assertEquals(40, packetTime.ms)
    }

    @Test
    fun aCleanLinkKeepsTenMillisecondPackets() {
        repeat(100) { assertFalse(tick(0.0, 0.0)) }
        assertEquals(10, packetTime.ms)
    }

    @Test
    fun lossThatRedRepairsChangesNothing() {
        // Plenty of loss, but nothing left to conceal: the copies are doing their job.
        repeat(50) { assertFalse(tick(0.08, 0.0)) }
        // And concealment without loss is the jitter buffer's business, not packet length.
        repeat(50) { assertFalse(tick(0.0, 0.05)) }
        assertEquals(Step.SHORT, packetTime.step)
    }

    @Test
    fun gapsTheCopiesCantCoverAskForLongerPacketsStepByStep() {
        assertFalse(tick(0.0, 0.0)) // the first report only sets the baseline
        assertFalse(tick(0.06, 0.03)) // one rough interval could be a blip
        assertTrue(tick(0.06, 0.03))
        assertEquals(20, packetTime.ms)
        // The change gets time to arrive and show before the next one.
        var next = -1L
        val changedAt = now
        while (next < 0) if (tick(0.06, 0.03)) next = now
        assertEquals(40, packetTime.ms)
        assertTrue(next - changedAt >= PacketTime.SETTLE_MS)
        // And no further.
        repeat(30) { assertFalse(tick(0.2, 0.1)) }
        assertEquals(Step.LONG, packetTime.step)
    }

    @Test
    fun aCalmMinuteStepsBackDownOneAtATime() {
        tick(0.0, 0.0); tick(0.06, 0.03); tick(0.06, 0.03)
        assertEquals(20, packetTime.ms)
        var downAt = -1L
        val calmFrom = now
        while (downAt < 0) if (tick(0.002, 0.0)) downAt = now
        assertEquals(10, packetTime.ms)
        assertTrue(downAt - calmFrom >= PacketTime.CALM_MS)
    }

    @Test
    fun aStepDownThatDoesntHoldMakesTheNextOneWaitLonger() {
        tick(0.0, 0.0); tick(0.06, 0.03); tick(0.06, 0.03)
        var downAt = -1L
        while (downAt < 0) if (tick(0.0, 0.0)) downAt = now
        assertEquals(10, packetTime.ms)
        // Rough again straight away: back up once the step down has settled...
        var upAt = -1L
        while (upAt < 0) if (tick(0.06, 0.03)) upAt = now
        assertEquals(20, packetTime.ms)
        assertTrue(upAt - downAt < PacketTime.FAILED_MS)
        // ...and the next calm stretch has to last twice as long.
        val calmFrom = now
        var secondDown = -1L
        while (secondDown < 0) if (tick(0.0, 0.0)) secondDown = now
        assertTrue(secondDown - calmFrom >= 2 * PacketTime.CALM_MS)
    }

    @Test
    fun aRadioSharedWithEarbudsGetsTwentyMillisecondPacketsAtLeast() {
        tick(0.0, 0.0)
        packetTime.floor = Step.MEDIUM
        assertEquals(20, packetTime.ms)
        // A calm link doesn't take it below the floor...
        repeat(100) { assertFalse(tick(0.0, 0.0)) }
        assertEquals(20, packetTime.ms)
        // ...but a rough one still goes longer.
        tick(0.06, 0.03)
        assertTrue(tick(0.06, 0.03))
        assertEquals(40, packetTime.ms)
        // Off the shared radio, it comes back down a step at a time once calm.
        packetTime.floor = Step.SHORT
        var steps = 0
        repeat(200) { if (tick(0.0, 0.0)) steps++ }
        assertEquals(2, steps)
        assertEquals(10, packetTime.ms)
    }

    @Test
    fun aNewConnectionsCountersStartAFreshBaseline() {
        tick(0.0, 0.0); tick(0.06, 0.03); tick(0.06, 0.03)
        assertEquals(20, packetTime.ms)
        // New connection: counters back at zero. Nothing is read into the drop.
        received = 0.0; lost = 0.0; concealed = 0.0; total = 0.0
        assertFalse(tick(0.0, 0.0))
        assertEquals(20, packetTime.ms)
    }

    @Test
    fun statsGiveTheCounters() {
        val report = mapOf(
            "I" to CallStats.Entry(
                "inbound-rtp",
                mapOf("kind" to "audio", "packetsReceived" to 950L, "packetsLost" to 50, "concealedSamples" to 4_800L, "totalSamplesReceived" to 480_000L),
            ),
        )
        assertEquals(PacketTime.Counters(950.0, 50.0, 4_800.0, 480_000.0), CallStats.inboundAudioCounters(report))
        assertEquals(null, CallStats.inboundAudioCounters(emptyMap()))
    }
}
