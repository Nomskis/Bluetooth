package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class DelayTrackerTest {
    private fun report(rttSeconds: Double, bufferSeconds: Double, emitted: Long) = mapOf(
        "T" to CallStats.Entry("transport", mapOf("selectedCandidatePairId" to "P")),
        "P" to CallStats.Entry("candidate-pair", mapOf("currentRoundTripTime" to rttSeconds, "localCandidateId" to "L")),
        // WebRTC hands uint64 counters to Java as BigInteger.
        "I" to CallStats.Entry(
            "inbound-rtp",
            mapOf("kind" to "audio", "jitterBufferDelay" to bufferSeconds, "jitterBufferEmittedCount" to BigInteger.valueOf(emitted)),
        ),
        "V" to CallStats.Entry("inbound-rtp", mapOf("kind" to "video", "jitterBufferDelay" to 99.0, "jitterBufferEmittedCount" to BigInteger.ONE)),
    )

    @Test
    fun addsUpTheParts() {
        val tracker = DelayTracker()
        // 48 000 samples emitted, 2 400 sample-seconds of buffering: 50 ms average so far.
        val first = tracker.update(report(0.060, 2_400.0, 48_000), playoutMs = 180.0, measured = true)
        assertEquals(30, first.networkMs)
        assertEquals(50, first.jitterBufferMs)
        assertEquals(180, first.playoutMs)
        assertEquals(DelayBreakdown.SENDER_ESTIMATE_MS + 30 + 50 + 180, first.totalMs)

        // The next 96 000 samples waited 40 ms each: the recent average, not the lifetime one.
        val second = tracker.update(report(0.060, 2_400.0 + 96_000 * 0.040, 144_000), playoutMs = 180.0, measured = true)
        assertEquals(40, second.jitterBufferMs)
    }

    @Test
    fun flagsAWeakConnection() {
        val fine = DelayBreakdown(30, networkMs = 40, jitterBufferMs = 60, playoutMs = 200, playoutMeasured = false, lossPercent = 1.0)
        assertEquals(false, fine.weakConnection)
        assertEquals(true, fine.copy(lossPercent = 12.0).weakConnection)
        assertEquals(true, fine.copy(networkMs = 350).weakConnection)
        assertEquals(true, fine.copy(jitterBufferMs = 300).weakConnection)
        assertEquals(false, fine.copy(networkMs = null, jitterBufferMs = null, lossPercent = null).weakConnection)
    }

    @Test
    fun saysWhichWayTheConnectionIsWeak() {
        val fine = DelayBreakdown(30, networkMs = 40, jitterBufferMs = 60, playoutMs = 200, playoutMeasured = false, lossPercent = 1.0, sendLossPercent = 0.5)
        assertEquals(LinkQuality.GOOD, fine.fromThem)
        assertEquals(LinkQuality.GOOD, fine.toThem)
        // Their uplink struggling: loss on what reaches us, or audio that had to be made up.
        assertEquals(LinkQuality.FAIR, fine.copy(lossPercent = 3.0).fromThem)
        assertEquals(LinkQuality.POOR, fine.copy(concealedPercent = 4.0).fromThem)
        assertEquals(LinkQuality.GOOD, fine.copy(concealedPercent = 4.0).toThem)
        // Ours: what their side reports back, or how much we had to squeeze.
        assertEquals(LinkQuality.POOR, fine.copy(sendLossPercent = 10.0).toThem)
        assertEquals(LinkQuality.FAIR, fine.copy(sendSqueeze = LinkQuality.FAIR).toThem)
        assertEquals(LinkQuality.POOR, fine.copy(sendSqueeze = LinkQuality.POOR).toThem)
        assertEquals(LinkQuality.GOOD, fine.copy(sendLossPercent = 10.0).fromThem)
        assertEquals(true, fine.copy(sendLossPercent = 10.0).weakConnection)
        // A long, slow path is both ways.
        assertEquals(LinkQuality.POOR, fine.copy(networkMs = 320).fromThem)
        assertEquals(LinkQuality.POOR, fine.copy(networkMs = 320).toThem)
        // Nothing known yet.
        val unknown = DelayBreakdown(30, networkMs = null, jitterBufferMs = null, playoutMs = null, playoutMeasured = false)
        assertNull(unknown.fromThem)
        assertNull(unknown.toThem)
    }

    @Test
    fun readsConcealmentAndWhatTheirSideReportsBack() {
        val tracker = DelayTracker()
        val report = { concealed: Long, total: Long ->
            mapOf(
                "I" to CallStats.Entry("inbound-rtp", mapOf("kind" to "audio", "concealedSamples" to BigInteger.valueOf(concealed), "totalSamplesReceived" to BigInteger.valueOf(total))),
                "R" to CallStats.Entry("remote-inbound-rtp", mapOf("kind" to "audio", "fractionLost" to 0.04)),
            )
        }
        assertNull(tracker.update(report(0, 96_000), null, false).concealedPercent) // no interval yet
        val next = tracker.update(report(1_920, 192_000), null, false, packetMs = 20)
        assertEquals(2.0, next.concealedPercent!!, 1e-9)
        assertEquals(4.0, next.sendLossPercent!!, 1e-9)
        assertEquals(20, next.packetMs)
        assertEquals(DelayBreakdown.SENDER_ESTIMATE_MS + 10, next.senderMs)
    }

    @Test
    fun saysWhetherTheConnectionIsRelayed() {
        val direct = report(0.05, 10.0, 500) +
            ("L" to CallStats.Entry("local-candidate", mapOf("candidateType" to "srflx")))
        assertEquals(false, DelayTracker().update(direct, null, false).relayed)
        val relayed = direct + ("P" to CallStats.Entry("candidate-pair", mapOf("localCandidateId" to "L", "remoteCandidateId" to "R"))) +
            ("R" to CallStats.Entry("remote-candidate", mapOf("candidateType" to "relay")))
        assertEquals(true, DelayTracker().update(relayed, null, false).relayed)
        assertNull(DelayTracker().update(report(0.05, 10.0, 500), null, false).relayed)
    }

    @Test
    fun survivesAReconnectResettingTheCounters() {
        val tracker = DelayTracker()
        tracker.update(report(0.05, 4_800.0, 96_000), null, false)
        val afterReset = tracker.update(report(0.05, 10.0, 500), null, false)
        assertEquals(50, afterReset.jitterBufferMs) // keeps the last good figure
        val next = tracker.update(report(0.05, 10.0 + 1_000 * 0.03, 1_500), null, false)
        assertEquals(30, next.jitterBufferMs)
    }

    @Test
    fun totalWaitsForEveryVaryingPart() {
        val partial = DelayTracker().update(report(0.04, 100.0, 2_000), playoutMs = null, measured = false)
        assertNull(partial.totalMs)
        assertNull(CallStats.audioJitterBuffer(emptyMap()))
        assertNull(CallStats.roundTripSeconds(emptyMap()))
    }

    @Test
    fun reportsRecentPacketLoss() {
        val tracker = DelayTracker()
        val report = { received: Long, lost: Long ->
            mapOf(
                "I" to CallStats.Entry(
                    "inbound-rtp",
                    mapOf(
                        "kind" to "audio",
                        "packetsReceived" to BigInteger.valueOf(received),
                        "packetsLost" to lost.toInt(), // a signed 32-bit counter in the stats
                        "jitterBufferDelay" to 0.0,
                        "jitterBufferEmittedCount" to BigInteger.ONE,
                    ),
                ),
            )
        }
        assertNull(tracker.update(report(1_000, 10), null, false).lossPercent) // no interval yet
        assertEquals(2.0, tracker.update(report(1_196, 14), null, false).lossPercent!!, 1e-9)
        assertEquals(0.0, tracker.update(report(1_396, 14), null, false).lossPercent!!, 1e-9)
    }
}
