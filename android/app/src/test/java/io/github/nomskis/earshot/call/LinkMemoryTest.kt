package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkMemoryTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day
    private val sam = "1D8ANuTJStR4AyHh0kwUw6"
    private val leila = "8kQw0FJZcA2mT7yR1vN4bE"

    @Test
    fun theNextCallStartsALittleUnderWhatHeld() {
        val memory = LinkMemory(sam, "wifi", sendEstimateBps = 600_000, packetMs = 20, voiceLevel = "REDUCED", atMillis = now)
        assertEquals(480_000, memory.startBitrateBps)
        assertEquals(PacketTime.Step.MEDIUM, memory.packetStep)
        assertEquals(MediaBudget.Level.REDUCED, memory.level)
        // Within sane bounds either way.
        assertEquals(LinkMemory.MIN_START_BPS, memory.copy(sendEstimateBps = 50_000).startBitrateBps)
        assertEquals(LinkMemory.MAX_START_BPS, memory.copy(sendEstimateBps = 20_000_000).startBitrateBps)
        assertNull(memory.copy(sendEstimateBps = null).startBitrateBps)
        // Anything unexpected falls back to the usual start.
        assertEquals(PacketTime.Step.SHORT, memory.copy(packetMs = 33).packetStep)
        assertEquals(MediaBudget.Level.FULL, memory.copy(voiceLevel = "LOUD").level)
    }

    @Test
    fun keptPerContactAndNetworkForTwoWeeks() {
        val home = LinkMemory(sam, "wifi", 900_000, atMillis = now - day)
        val mobile = LinkMemory(sam, "cellular", 300_000, atMillis = now - 2 * day)
        val other = LinkMemory(leila, "wifi", 2_000_000, atMillis = now - 3 * day)
        val memories = listOf(home, mobile, other)
        assertEquals(home, LinkMemories.find(memories, sam, "wifi", now))
        assertEquals(mobile, LinkMemories.find(memories, sam, "cellular", now))
        assertNull(LinkMemories.find(memories, sam, null, now))
        assertNull(LinkMemories.find(memories, null, "wifi", now))
        assertNull(LinkMemories.find(memories, sam, "wifi", now + LinkMemories.KEEP_MS))
    }

    @Test
    fun aNewCallReplacesTheOldMemoryAndStaleOnesGo() {
        val old = LinkMemory(sam, "wifi", 900_000, atMillis = now - day)
        val stale = LinkMemory(leila, "wifi", 2_000_000, atMillis = now - 20 * day)
        val fresh = LinkMemory(sam, "wifi", 400_000, packetMs = 40, atMillis = now)
        val updated = LinkMemories.upsert(listOf(old, stale), fresh, now)
        assertEquals(listOf(fresh), updated)
        assertEquals(updated, LinkMemories.decode(LinkMemories.encode(updated)))
        assertEquals(emptyList<LinkMemory>(), LinkMemories.decode("not json"))
        assertEquals(emptyList<LinkMemory>(), LinkMemories.decode(null))
    }

    @Test
    fun theLearnerKeepsWhatTheRouteReliablyCarried() {
        val learner = LinkLearner(windowSamples = 20)
        repeat(LinkLearner.MIN_SAMPLES - 1) { learner.addEstimate(1_000_000.0) }
        assertNull(learner.sendEstimateBps) // too soon to say
        learner.addEstimate(1_000_000.0)
        assertEquals(1_000_000, learner.sendEstimateBps)
        // A dip a quarter of the time sets the figure, not the best moments.
        val dipping = LinkLearner(windowSamples = 20)
        repeat(15) { dipping.addEstimate(1_500_000.0) }
        repeat(5) { dipping.addEstimate(300_000.0) }
        assertEquals(300_000, dipping.sendEstimateBps)
        // Only the last few minutes count.
        repeat(20) { dipping.addEstimate(800_000.0) }
        assertEquals(800_000, dipping.sendEstimateBps)
    }

    @Test
    fun aCallStartsWhereTheLastOneSettled() {
        assertEquals(20, PacketTime(PacketTime.Step.MEDIUM).ms)
        val budget = MediaBudget(redundancy = 3, startLevel = MediaBudget.Level.REDUCED)
        assertEquals(32_000, budget.voiceCapBps)
        // It still probes back up once the route allows it.
        var now = 0L
        repeat(30) { now += 2_000; budget.update(5_000_000.0, budget.wireBps(budget.level), wantsVideo = true, nowMs = now) }
        assertEquals(MediaBudget.Level.FULL, budget.level)
    }
}
