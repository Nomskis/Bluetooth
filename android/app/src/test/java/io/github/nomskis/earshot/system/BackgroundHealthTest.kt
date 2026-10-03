package io.github.nomskis.earshot.system

import io.github.nomskis.earshot.system.BackgroundHealth.Brand
import io.github.nomskis.earshot.system.BackgroundHealth.StepId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHealthTest {
    @Test
    fun recognisesTheMakers() {
        // POCO and Redmi phones report "Xiaomi" as their manufacturer, but cover the brand names too.
        assertEquals(Brand.XIAOMI, BackgroundHealth.brandOf("Xiaomi"))
        assertEquals(Brand.XIAOMI, BackgroundHealth.brandOf("POCO"))
        assertEquals(Brand.OPPO_REALME, BackgroundHealth.brandOf("realme"))
        assertEquals(Brand.HONOR, BackgroundHealth.brandOf("HONOR"))
        assertEquals(Brand.OTHER, BackgroundHealth.brandOf("Google"))
        assertFalse(BackgroundHealth.isAggressive(Brand.OTHER))
        assertTrue(BackgroundHealth.isAggressive(Brand.XIAOMI))
    }

    @Test
    fun xiaomiNeedsItsOwnBatterySaverAutostartAndALock() {
        val steps = BackgroundHealth.steps(Brand.XIAOMI, ignoringBatteryOptimizations = true)
        assertEquals(
            listOf(StepId.BATTERY_OPTIMIZATION, StepId.OEM_BATTERY, StepId.AUTOSTART, StepId.LOCK_IN_RECENTS),
            steps.map { it.id },
        )
        assertEquals(true, steps.first().done)
        assertTrue(steps.drop(1).all { it.done == null }) // Android can't tell us these
    }

    @Test
    fun stockAndroidOnlyNeedsTheBatteryExemption() {
        val steps = BackgroundHealth.steps(Brand.OTHER, ignoringBatteryOptimizations = false)
        assertEquals(listOf(StepId.BATTERY_OPTIMIZATION), steps.map { it.id })
        assertEquals(false, steps.single().done)
    }
}
