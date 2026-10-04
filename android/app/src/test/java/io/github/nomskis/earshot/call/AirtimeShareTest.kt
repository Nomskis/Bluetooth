package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.call.AirtimeShare.Companion.STARVED
import io.github.nomskis.earshot.call.AirtimeShare.Companion.TIGHT
import io.github.nomskis.earshot.call.AirtimeShare.Companion.WIFI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirtimeShareTest {
    private val share = AirtimeShare()
    private var now = 0L

    private fun tick(uplink: String?, network: String? = WIFI): Boolean {
        now += 2_000
        return share.update(network, uplink, now)
    }

    @Test
    fun aFineUplinkOrMobileDataNeverTouchesOurVideo() {
        repeat(50) { assertFalse(tick(null)) }
        // Mobile data has its own uplink channel: our downstream can't starve it.
        repeat(50) { assertFalse(tick(STARVED, network = "cellular")) }
        assertNull(share.capKbps)
    }

    @Test
    fun theirStrugglingWifiGetsLighterVideoFromUsAfterAFewSeconds() {
        assertFalse(tick(TIGHT)) // a blip is not a reason
        assertFalse(tick(TIGHT))
        assertTrue(tick(TIGHT))
        assertEquals(AirtimeShare.TIGHT_KBPS, share.capKbps)
        // Worse: lighter still.
        assertTrue(tick(STARVED))
        assertEquals(AirtimeShare.STARVED_KBPS, share.capKbps)
    }

    @Test
    fun whenItHelpsTheCapStaysUntilTheirUplinkHasBeenFineForAMinute() {
        repeat(3) { tick(STARVED) }
        assertEquals(AirtimeShare.STARVED_KBPS, share.capKbps)
        // Their side recovers step by step, past the trial time.
        repeat(10) { tick(STARVED) }
        repeat(20) { tick(TIGHT) }
        assertEquals(AirtimeShare.STARVED_KBPS, share.capKbps)
        var liftedAt = -1L
        val fineFrom = now
        while (liftedAt < 0) if (tick(null)) liftedAt = now
        assertNull(share.capKbps)
        assertTrue(liftedAt - fineFrom >= AirtimeShare.RELEASE_AFTER_MS)
    }

    @Test
    fun whenItDoesntHelpItStopsAndLeavesThemAlone() {
        repeat(3) { tick(STARVED) }
        val engagedAt = now
        var liftedAt = -1L
        while (liftedAt < 0) if (tick(STARVED)) liftedAt = now
        assertTrue(liftedAt - engagedAt >= AirtimeShare.TRIAL_MS)
        assertNull(share.capKbps)
        // Not tried again for a while, however bad it stays.
        repeat(60) { assertFalse(tick(STARVED)) }
        // Then once more.
        var again = false
        repeat(200) { if (tick(STARVED)) again = true }
        assertTrue(again)
    }

    @Test
    fun movingOffWifiLiftsIt() {
        repeat(3) { tick(TIGHT) }
        assertEquals(AirtimeShare.TIGHT_KBPS, share.capKbps)
        assertTrue(tick(TIGHT, network = "cellular"))
        assertNull(share.capKbps)
    }

    @Test
    fun whatWeTellThemAboutOurUplink() {
        assertNull(AirtimeShare.uplinkOf(LinkQuality.GOOD, 0.5))
        assertNull(AirtimeShare.uplinkOf(null, null))
        assertEquals(TIGHT, AirtimeShare.uplinkOf(LinkQuality.FAIR, 0.0))
        assertEquals(TIGHT, AirtimeShare.uplinkOf(LinkQuality.GOOD, 3.0))
        assertEquals(STARVED, AirtimeShare.uplinkOf(LinkQuality.POOR, 0.0))
        assertEquals(STARVED, AirtimeShare.uplinkOf(LinkQuality.GOOD, 9.0))
    }
}
