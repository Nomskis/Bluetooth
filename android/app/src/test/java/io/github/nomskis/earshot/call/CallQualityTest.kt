package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallQualityTest {
    /** A stats report shaped like WebRTC's, with the numbers that matter here. */
    private fun report(
        rtt: Double = 0.09,
        received: Long,
        lost: Long,
        samples: Long,
        concealed: Long,
        jbDelay: Double,
        jbEmitted: Long,
        freezes: Long = 0,
        freezeSeconds: Double = 0.0,
        relay: Boolean = false,
        limits: Map<String, Double> = mapOf("none" to 10.0, "bandwidth" to 0.0, "cpu" to 0.0),
    ) = mapOf(
        "T" to CallStats.Entry("transport", mapOf("selectedCandidatePairId" to "P")),
        "P" to CallStats.Entry(
            "candidate-pair",
            mapOf("state" to "succeeded", "localCandidateId" to "L", "remoteCandidateId" to "R", "currentRoundTripTime" to rtt, "availableOutgoingBitrate" to 900_000.0),
        ),
        "L" to CallStats.Entry(
            "local-candidate",
            mapOf("networkType" to "wifi", "candidateType" to if (relay) "relay" else "srflx", "relayProtocol" to "tls", "protocol" to "udp"),
        ),
        "R" to CallStats.Entry("remote-candidate", mapOf("candidateType" to "srflx")),
        "A" to CallStats.Entry(
            "inbound-rtp",
            mapOf(
                "kind" to "audio", "packetsReceived" to received, "packetsLost" to lost, "totalSamplesReceived" to samples,
                "concealedSamples" to concealed, "jitterBufferDelay" to jbDelay, "jitterBufferEmittedCount" to jbEmitted, "nackCount" to 3L,
            ),
        ),
        "V" to CallStats.Entry(
            "inbound-rtp",
            mapOf("kind" to "video", "framesPerSecond" to 24.0, "frameHeight" to 480L, "freezeCount" to freezes, "totalFreezesDuration" to freezeSeconds),
        ),
        "O" to CallStats.Entry("outbound-rtp", mapOf("kind" to "video", "codecId" to "C", "qualityLimitationDurations" to limits)),
        "C" to CallStats.Entry("codec", mapOf("mimeType" to "video/VP9")),
    )

    @Test
    fun summarisesACallAcrossAReconnect() {
        val tracker = CallQualityTracker()
        tracker.newConnection()
        tracker.update(report(received = 1_000, lost = 10, samples = 960_000, concealed = 0, jbDelay = 48_000.0, jbEmitted = 960_000))
        tracker.update(report(rtt = 0.15, received = 2_000, lost = 40, samples = 1_920_000, concealed = 9_600, jbDelay = 192_000.0, jbEmitted = 1_920_000))
        // Wi-Fi dropped; the new connection's counters start from zero.
        tracker.reconnected()
        tracker.newConnection()
        tracker.update(
            report(
                received = 500, lost = 0, samples = 480_000, concealed = 0, jbDelay = 24_000.0, jbEmitted = 480_000,
                freezes = 2, freezeSeconds = 1.5, relay = true, limits = mapOf("none" to 6.0, "bandwidth" to 4.0, "cpu" to 0.0),
            ),
        )
        val q = tracker.summary()
        assertEquals("relay (tls)", q.path)
        assertEquals("wifi", q.network)
        assertEquals(110, q.rttMsAvg)
        assertEquals(150, q.rttMsMax)
        // 40 of 2,540 packets lost; 9,600 of 2,400,000 samples made up.
        assertEquals(1.6, q.audioLossPercent!!, 0.05)
        assertEquals(0.4, q.concealedPercent!!, 0.05)
        // 50 ms on the first connection's second interval; the first sample of each only sets a baseline.
        assertEquals(150, q.jitterBufferMsAvg)
        assertEquals(2, q.videoFreezes)
        assertEquals("VP9", q.videoCodec)
        assertEquals(20, q.sendLimitedByBandwidthPercent)
        assertEquals(1, q.reconnects)
        assertEquals(CallQuality.Verdict.OKAY, q.verdict) // the reconnect
    }

    @Test
    fun theReportReadsPlainly() {
        val q = CallQuality(
            path = "direct", network = "wifi", rttMsAvg = 92, rttMsMax = 310, audioLossPercent = 2.4, concealedPercent = 0.3,
            audioNacks = 41, jitterBufferMsAvg = 85, jitterBufferMsMax = 240, videoFreezes = 1, videoFreezeSeconds = 0.6,
            receivedFps = 23.0, receivedHeight = 480, videoCodec = "VP9", sendLimitedByBandwidthPercent = 35,
            sendLimitedByCpuPercent = 0, availableKbpsMin = 310, availableKbpsAvg = 820, voiceReducedPercent = 5,
        )
        val text = q.report(durationSeconds = 754)
        assertTrue(text.contains("Length: 12 min 34 s"))
        assertTrue(text.contains("Route: direct, this phone on wifi"))
        assertTrue(text.contains("Their audio: 2.4% lost on the way, 0.3% made up, 41 resend requests"))
        assertTrue(text.contains("Their video: 480p at 23 fps, 1 freezes (0.6 s)"))
        assertTrue(text.contains("Our voice was lighter to fit the connection 5% of the time"))
        assertEquals(CallQuality.Verdict.GOOD, q.verdict)
    }

    @Test
    fun theReportHasBothSidesAndTheDelayAsHeard() {
        val tracker = CallQualityTracker()
        tracker.context(theirNetwork = null, video = false, mouthToEarMs = 400)
        tracker.context(theirNetwork = "wifi", video = true, mouthToEarMs = 600)
        // Video once is a video call; their network stays known when a later update doesn't say.
        tracker.context(theirNetwork = null, video = false, mouthToEarMs = null)
        val text = tracker.summary().copy(path = "direct", network = "cellular").report()
        assertTrue(text.contains("Call: video"))
        assertTrue(text.contains("Route: direct, this phone on cellular, theirs on wifi"))
        assertTrue(text.contains("Their voice reached you after: 500 ms average"))
        val voice = CallQuality(video = false, videoCodec = "VP8", sendLimitedByBandwidthPercent = 100).report()
        assertTrue(voice.contains("Call: voice"))
        assertTrue(!voice.contains("Our video"))
        // Calls kept from before say neither.
        assertTrue(!CallQuality().report().contains("Call:"))
    }

    @Test
    fun aDirectCallLearnsWhatTheRelayWouldHaveDone() {
        val tracker = CallQualityTracker()
        val standby = mapOf(
            // Relayed on both ends: what "Route through relay" gives.
            "Q" to CallStats.Entry("candidate-pair", mapOf("state" to "succeeded", "localCandidateId" to "LR", "remoteCandidateId" to "RR", "currentRoundTripTime" to 0.07)),
            // Relayed on one end only: not the same route, so not counted.
            "S" to CallStats.Entry("candidate-pair", mapOf("state" to "succeeded", "localCandidateId" to "LR", "remoteCandidateId" to "R", "currentRoundTripTime" to 0.05)),
            "LR" to CallStats.Entry("local-candidate", mapOf("networkType" to "cellular", "candidateType" to "relay")),
            "RR" to CallStats.Entry("remote-candidate", mapOf("candidateType" to "relay")),
        )
        tracker.update(report(rtt = 0.26, received = 100, lost = 0, samples = 96_000, concealed = 0, jbDelay = 4_800.0, jbEmitted = 96_000) + standby)
        val q = tracker.summary()
        // A direct path says which transport it took.
        assertEquals("direct (udp)", q.path)
        assertEquals(70, q.relayRttMsAvg)
        assertTrue(q.report().contains("Relay round trip, checked on the side: 70 ms"))
        // Already on the relay: nothing to compare.
        assertTrue(!q.copy(path = "relay (udp)").report().contains("Relay round trip"))
    }

    @Test
    fun theReportSaysWhetherMobileDataCouldBeUsedAndWhy() {
        val tracker = CallQualityTracker()
        tracker.mobileData("used, no working Wi-Fi when the call connected")
        assertTrue(tracker.summary().report().contains("Mobile data: used, no working Wi-Fi when the call connected"))
        // Calls kept from before the line existed just leave it out.
        assertTrue(!CallQuality(network = "wifi").report().contains("Mobile data"))
    }

    @Test
    fun aCallThatSoundedBadIsPoor() {
        assertEquals(CallQuality.Verdict.POOR, CallQuality(concealedPercent = 4.0).verdict)
        assertEquals(CallQuality.Verdict.POOR, CallQuality(rttMsAvg = 450).verdict)
        assertEquals(CallQuality.Verdict.OKAY, CallQuality(concealedPercent = 1.5).verdict)
    }

    @Test
    fun anEmptyCallHasNoNumbers() {
        val q = CallQualityTracker().summary()
        assertNull(q.rttMsAvg)
        assertNull(q.concealedPercent)
        assertNull(q.videoFreezes)
    }
}
