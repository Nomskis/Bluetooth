package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPlanTest {
    @Test
    fun fullQualityUntilThePhoneIsActuallyOverheating() {
        for (status in 0..2) assertNull("status $status", ThermalPlan.forStatus(status)) // none, light, moderate
        val severe = ThermalPlan.forStatus(ThermalPlan.SEVERE)!!
        val critical = ThermalPlan.forStatus(6)!! // shutdown counts as critical
        assertTrue(severe.maxKbps > critical.maxKbps)
        assertTrue(severe.maxFps > critical.maxFps)
        assertTrue(critical.scaleDownBy >= severe.scaleDownBy)
    }

    @Test
    fun combinesWithTheRadioCap() {
        assertEquals(800, ThermalPlan.tighter(800, 1_000))
        assertEquals(500, ThermalPlan.tighter(800, 500))
        assertEquals(800, ThermalPlan.tighter(800, null))
        assertEquals(500, ThermalPlan.tighter(null, 500))
        assertNull(ThermalPlan.tighter(null, null))
    }
}
