package io.github.nomskis.earshot.call

/**
 * Protection for an overheating phone, nothing more: video stays at full
 * quality until Android reports severe or critical heat, when it throttles
 * hard and starts shutting the camera off. Encoding less then keeps the
 * video going instead of losing it, and the voice is left alone.
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
        const val SEVERE = 3
        const val CRITICAL = 4

        fun forStatus(status: Int): ThermalPlan? = when {
            status >= CRITICAL -> ThermalPlan(maxKbps = 400, scaleDownBy = 2.0, maxFps = 15)
            status >= SEVERE -> ThermalPlan(maxKbps = 800, scaleDownBy = 1.5, maxFps = 24)
            else -> null
        }

        /** The tighter of two bitrate caps, either of which may be absent. */
        fun tighter(a: Int?, b: Int?): Int? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}
