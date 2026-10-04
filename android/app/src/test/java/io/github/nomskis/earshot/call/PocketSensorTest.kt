package io.github.nomskis.earshot.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketSensorTest {
    @Test
    fun nearMeansCloserThanFiveCentimetresAndTheSensorRange() {
        // Most phones' sensors are binary: 0 when covered, their maximum range when not.
        assertTrue(PocketSensor.isNear(0f, 5f))
        assertFalse(PocketSensor.isNear(5f, 5f))
        assertTrue(PocketSensor.isNear(0f, 1f))
        assertFalse(PocketSensor.isNear(1f, 1f))
        // Ranging sensors.
        assertTrue(PocketSensor.isNear(3f, 10f))
        assertFalse(PocketSensor.isNear(8f, 10f))
        assertFalse(PocketSensor.isNear(-1f, 5f))
    }
}
