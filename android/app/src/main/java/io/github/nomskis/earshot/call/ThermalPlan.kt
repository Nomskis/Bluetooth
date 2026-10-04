package io.github.nomskis.earshot.call

/**
 * Lighter outgoing video as the phone heats up. An hour of video at the gym,
 * in a warm room or a pocket, gets phones throttled, and past a point
 * Android shuts the camera off. Encoding less (fewer pixels, frames and
 * bits) is the biggest heat source a call can turn down, and the voice is
 * left alone.
 *
 * Levels are PowerManager's thermal status (API 29+).
 */
data class ThermalPlan(
    val maxKbps: Int,
    val scaleDownBy: Double,
    val maxFps: Int,
) {
    companion object {
        // PowerManager.THERMAL_STATUS_* values.
        const val MODERATE = 2
        const val SEVERE = 3
        const val CRITICAL = 4

        fun forStatus(status: Int): ThermalPlan? = when {
            status >= CRITICAL -> ThermalPlan(maxKbps = 250, scaleDownBy = 2.0, maxFps = 10)
            status >= SEVERE -> ThermalPlan(maxKbps = 500, scaleDownBy = 1.5, maxFps = 15)
            status >= MODERATE -> ThermalPlan(maxKbps = 1_000, scaleDownBy = 1.0, maxFps = 24)
            else -> null
        }

        /** Battery at or below this, not charging: lighter video so the call lasts. */
        const val LOW_BATTERY_PERCENT = 15
        val LOW_BATTERY = ThermalPlan(maxKbps = 500, scaleDownBy = 1.5, maxFps = 15)

        /** The lighter of the heat and battery plans, either of which may be absent. */
        fun lighter(a: ThermalPlan?, b: ThermalPlan?): ThermalPlan? = listOfNotNull(a, b).minByOrNull { it.maxKbps }

        /** The tighter of two bitrate caps, either of which may be absent. */
        fun tighter(a: Int?, b: Int?): Int? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}
