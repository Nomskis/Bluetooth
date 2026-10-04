package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPlanTest {
    @Test
    fun videoGetsLighterAsThePhoneHeatsUp() {
        assertNull(ThermalPlan.forStatus(0))
        assertNull(ThermalPlan.forStatus(1)) // light: nothing yet
        val moderate = ThermalPlan.forStatus(ThermalPlan.MODERATE)!!
        val severe = ThermalPlan.forStatus(ThermalPlan.SEVERE)!!
        val critical = ThermalPlan.forStatus(6)!!
        assertTrue(moderate.maxKbps > severe.maxKbps && severe.maxKbps > critical.maxKbps)
        assertTrue(moderate.maxFps > severe.maxFps && severe.maxFps > critical.maxFps)
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
