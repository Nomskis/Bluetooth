package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSteeringTest {
    /** Simulates ICE: the Wi-Fi pair is pinged every second, the mobile-data pair every two. */
    private class Pings {
        var wifiSent = 0.0
        var wifiReceived = 0.0
        var cellSent = 0.0
        var cellReceived = 0.0

        fun interval(wifiLoss: Double, cellLoss: Double = 0.0): List<PathSteering.CandidatePair> {
            wifiSent += 2
            wifiReceived += 2 * (1 - wifiLoss)
            cellSent += 1
            cellReceived += 1 - cellLoss
            return listOf(
                PathSteering.CandidatePair("w", "wifi", wifiSent, wifiReceived),
                PathSteering.CandidatePair("c", "cellular", cellSent, cellReceived),
            )
        }
    }

    @Test
    fun movesToMobileDataWhenOnlyTheWifiPathIsLosingPings() {
        val steering = PathSteering(minHoldMs = 60_000)
        val pings = Pings()
        var now = 0L
        // Healthy Wi-Fi: nothing happens.
        repeat(6) { assertNull(steering.update(pings.interval(wifiLoss = 0.0), now)); now += 2_000 }
        // Wi-Fi starts dropping a third of its pings; mobile data is fine.
        var decision: Boolean? = null
        repeat(6) { if (decision == null) decision = steering.update(pings.interval(wifiLoss = 0.35), now); now += 2_000 }
        assertEquals(true, decision)
        assertTrue(steering.prefersCellular)

        // Wi-Fi recovers at once, but the switch holds for a minute to avoid flapping.
        repeat(10) { assertNull(steering.update(pings.interval(wifiLoss = 0.0), now)); now += 2_000 }
        now += 40_000
        assertEquals(false, steering.update(pings.interval(wifiLoss = 0.0), now))
        assertFalse(steering.prefersCellular)
    }

    @Test
    fun staysWhenBothPathsSufferBecauseThenItIsTheOtherSide() {
        val steering = PathSteering()
        val pings = Pings()
        var now = 0L
        repeat(12) { assertNull(steering.update(pings.interval(wifiLoss = 0.4, cellLoss = 0.4), now)); now += 2_000 }
        assertFalse(steering.prefersCellular)
    }

    @Test
    fun needsAWorkingMobileDataPath() {
        val steering = PathSteering()
        var sent = 0.0
        var received = 0.0
        repeat(12) {
            sent += 2
            received += 1
            assertNull(steering.update(listOf(PathSteering.CandidatePair("w", "wifi", sent, received)), it * 2_000L))
        }
    }

    @Test
    fun aNewConnectionResettingTheCountersIsNotMistakenForLoss() {
        val steering = PathSteering()
        val pings = Pings()
        repeat(5) { steering.update(pings.interval(0.0), it * 2_000L) }
        // New peer connection: counters start again from zero under new pair ids.
        val fresh = listOf(
            PathSteering.CandidatePair("w2", "wifi", 1.0, 0.0),
            PathSteering.CandidatePair("c2", "cellular", 1.0, 1.0),
        )
        assertNull(steering.update(fresh, 12_000))
        assertFalse(steering.prefersCellular)
    }

    @Test
    fun readsPairsFromTheStatsReport() {
        val e = { type: String, members: Map<String, Any?> -> CallStats.Entry(type, members) }
        val report = mapOf(
            "P1" to e("candidate-pair", mapOf("state" to "succeeded", "localCandidateId" to "L1", "requestsSent" to java.math.BigInteger.TEN, "responsesReceived" to java.math.BigInteger.valueOf(8))),
            "P2" to e("candidate-pair", mapOf("state" to "failed", "localCandidateId" to "L2", "requestsSent" to 5, "responsesReceived" to 0)),
            "L1" to e("local-candidate", mapOf("networkType" to "wifi")),
            "L2" to e("local-candidate", mapOf("networkType" to "cellular")),
        )
        assertEquals(listOf(PathSteering.CandidatePair("P1", "wifi", 10.0, 8.0)), CallStats.candidatePairs(report))
    }
}
