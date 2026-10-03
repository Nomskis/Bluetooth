package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.audio.WifiBand
import io.github.nomskis.earshot.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPlanTest {
    private val defaults = AppSettings()

    @Test
    fun onlyActsOnTwoPointFourGigahertzWithBluetoothAudio() {
        assertEquals(RadioPlan.NONE, RadioPlan.decide(WifiBand.GHZ_5, bluetoothAudio = true, defaults))
        assertEquals(RadioPlan.NONE, RadioPlan.decide(null, bluetoothAudio = true, defaults))
        assertEquals(RadioPlan.NONE, RadioPlan.decide(WifiBand.GHZ_2_4, bluetoothAudio = false, defaults))
        val plan = RadioPlan.decide(WifiBand.GHZ_2_4, bluetoothAudio = true, defaults)
        assertEquals(RadioPlan.VIDEO_CAP_KBPS, plan.wifiVideoCapKbps)
        assertFalse(plan.preferCellular) // uses data, so it's opt-in
    }

    @Test
    fun mobileDataLiftsTheCapOnceMediaIsOnIt() {
        val plan = RadioPlan.decide(WifiBand.GHZ_2_4, true, defaults.copy(mobileDataOn24GHz = true))
        assertTrue(plan.preferCellular)
        assertEquals(RadioPlan.VIDEO_CAP_KBPS, plan.videoCapFor(CallPath.WIFI))
        assertEquals(RadioPlan.VIDEO_CAP_KBPS, plan.videoCapFor(null))
        assertNull(plan.videoCapFor(CallPath.CELLULAR))
    }

    @Test
    fun capCanBeTurnedOff() {
        val plan = RadioPlan.decide(WifiBand.GHZ_2_4, true, defaults.copy(bluetoothFriendlyVideo = false))
        assertNull(plan.wifiVideoCapKbps)
        assertFalse(plan.active)
        assertNull(RadioPlan.describe(plan, CallPath.WIFI))
    }

    @Test
    fun describesWhatsHappening() {
        val plan = RadioPlan.decide(WifiBand.GHZ_2_4, true, defaults.copy(mobileDataOn24GHz = true))
        assertTrue(RadioPlan.describe(plan, CallPath.CELLULAR)!!.contains("mobile data"))
        assertTrue(RadioPlan.describe(plan, CallPath.WIFI)!!.contains("lighter video"))
    }

    @Test
    fun readsTheNetworkOfTheSelectedPair() {
        val e = { type: String, members: Map<String, Any?> -> CallStats.Entry(type, members) }
        val report = mapOf(
            "T01" to e("transport", mapOf("selectedCandidatePairId" to "CPb")),
            "CPa" to e("candidate-pair", mapOf("localCandidateId" to "Lwifi", "state" to "succeeded", "nominated" to true)),
            "CPb" to e("candidate-pair", mapOf("localCandidateId" to "Lcell", "state" to "succeeded", "nominated" to true)),
            "Lwifi" to e("local-candidate", mapOf("networkType" to "wifi")),
            "Lcell" to e("local-candidate", mapOf("networkType" to "cellular")),
        )
        assertEquals("cellular", CallStats.selectedNetworkType(report))
        // Without a transport entry, fall back to the nominated pair.
        assertEquals("wifi", CallStats.selectedNetworkType(report - "T01" - "CPb"))
        assertNull(CallStats.selectedNetworkType(emptyMap()))
        assertEquals(CallPath.CELLULAR, RadioPlan.pathFor("cellular"))
        assertEquals(CallPath.WIFI, RadioPlan.pathFor("wifi"))
        assertEquals(CallPath.OTHER, RadioPlan.pathFor("ethernet"))
        assertNull(RadioPlan.pathFor(null))
    }
}
