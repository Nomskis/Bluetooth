package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The choices behind a sharp, steady screen share (docs/research/screen-share.md). */
class ScreenTuningTest {
    @Test
    fun aPhoneScreenIsCapturedAtItsOwnSizeOnWifiAndSmallerOnMobileData() {
        assertEquals(1080 to 2400, ScreenTuning.captureSize(1080, 2400, ScreenTuning.MAX_SHORT_SIDE_WIFI))
        assertEquals(720 to 1600, ScreenTuning.captureSize(1080, 2400, ScreenTuning.MAX_SHORT_SIDE_CELLULAR))
        // Turned sideways, the short side is the height.
        assertEquals(1600 to 720, ScreenTuning.captureSize(2400, 1080, ScreenTuning.MAX_SHORT_SIDE_CELLULAR))
        // A 1440p phone, scaled; both sides multiples of 8 for every encoder.
        val (w, h) = ScreenTuning.captureSize(1440, 3120, 1080)
        assertEquals(1080, w)
        assertEquals(0, h % 8)
        assertTrue(h in 2336..2344)
        assertEquals(0 to 0, ScreenTuning.captureSize(0, 100, 720))
    }

    @Test
    fun codecsBestForScreensComeFirst() {
        assertEquals(listOf("AV1", "VP9", "VP8", "H264"), ScreenTuning.codecOrder(av1 = true))
        assertEquals(listOf("VP9", "VP8", "H264"), ScreenTuning.codecOrder(av1 = false))
        assertTrue(ScreenTuning.encodesAv1(sdk = 34, cores = 8))
        assertFalse(ScreenTuning.encodesAv1(sdk = 34, cores = 4))
        assertFalse(ScreenTuning.encodesAv1(sdk = 30, cores = 8))
    }

    @Test
    fun aStillScreenIsSharpenedThenKeptAlive() {
        assertNull(ScreenTuning.repeatEveryMs(100))
        assertEquals(ScreenTuning.SHARPEN_EVERY_MS, ScreenTuning.repeatEveryMs(400))
        assertEquals(ScreenTuning.SHARPEN_EVERY_MS, ScreenTuning.repeatEveryMs(2_200))
        assertEquals(ScreenTuning.KEEPALIVE_EVERY_MS, ScreenTuning.repeatEveryMs(60_000))
        // Well inside the 30 seconds after which Cloudflare drops a silent track.
        assertTrue(ScreenTuning.KEEPALIVE_EVERY_MS < 30_000)
    }

    private fun at(kbit: Double, received: Double, lost: Double, freezes: Double = 0.0) =
        ScreenPace.Counters(bytesReceived = kbit * 1000 / 8, packetsReceived = received, packetsLost = lost, freezeCount = freezes)

    @Test
    fun theViewerAsksForLessWhenItsLinkCantKeepUp() {
        val pace = ScreenPace()
        assertFalse(pace.update(at(0.0, 0.0, 0.0), 0))
        // 1000 kbps arriving with 10% lost: just under what got through.
        assertTrue(pace.update(at(2_000.0, 180.0, 20.0), 2_000))
        assertEquals(800, pace.capKbps)
        // The sharer needs a moment to slow down; no second cut meanwhile.
        assertFalse(pace.update(at(3_000.0, 360.0, 40.0), 4_000))
        // A freeze counts too.
        assertTrue(pace.update(at(4_200.0, 600.0, 40.0, freezes = 1.0), 10_000))
        assertEquals(160, pace.capKbps)
    }

    @Test
    fun aCalmLinkGetsMoreBackOnlyWhileTheCeilingIsInUse() {
        val pace = ScreenPace()
        pace.update(at(0.0, 0.0, 0.0), 0)
        pace.update(at(1_000.0, 90.0, 10.0), 2_000)
        assertEquals(400, pace.capKbps)
        // A still screen: little arrives whatever the cap, so it isn't raised.
        var kbit = 1_000.0
        var received = 90.0
        var now = 2_000L
        repeat(10) {
            kbit += 20
            received += 50
            now += 2_000
            pace.update(at(kbit, received, 10.0), now)
        }
        assertEquals(400, pace.capKbps)
        // Using it, and calm for ten seconds: a quarter more.
        repeat(5) {
            kbit += 0.4 * 2_000
            received += 100
            now += 2_000
            pace.update(at(kbit, received, 10.0), now)
        }
        assertEquals(500, pace.capKbps)
        pace.reset()
        assertNull(pace.capKbps)
    }

    @Test
    fun theScreensStatsAreRead() {
        val report = mapOf(
            "A" to CallStats.Entry("inbound-rtp", mapOf("kind" to "audio", "bytesReceived" to 5.0, "packetsReceived" to 1.0)),
            "V" to CallStats.Entry(
                "inbound-rtp",
                mapOf("kind" to "video", "bytesReceived" to 125_000.0, "packetsReceived" to 140L, "packetsLost" to 3, "freezeCount" to 1L),
            ),
        )
        assertEquals(ScreenPace.Counters(125_000.0, 140.0, 3.0, 1.0), CallStats.inboundVideoCounters(report))
        assertNull(CallStats.inboundVideoCounters(emptyMap()))
    }
}
