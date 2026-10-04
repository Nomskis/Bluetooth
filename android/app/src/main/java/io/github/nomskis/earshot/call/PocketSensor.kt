package io.github.nomskis.earshot.call

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow

/** The proximity sensor, as "is something covering the phone": a pocket, or lying face down. */
class PocketSensor(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    fun covered(): Flow<Boolean> {
        val sensor = proximity ?: return emptyFlow()
        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(isNear(event.values[0], sensor.maximumRange))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensors?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { sensors?.unregisterListener(listener) }
        }.distinctUntilChanged()
    }

    companion object {
        /** Same rule as Android's own proximity screen-off: closer than 5 cm and than the sensor's range. */
        fun isNear(distance: Float, maximumRange: Float): Boolean =
            distance >= 0f && distance < minOf(NEAR_CM, maximumRange)

        private const val NEAR_CM = 5f
    }
}
