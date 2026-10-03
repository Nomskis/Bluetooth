package io.github.nomskis.earshot.audio

/**
 * Decisions for the tuner's one-tap "Find my fastest setup", kept apart
 * from the measuring so they can be tested.
 */
object FastestSetup {
    /** Below this, a difference is within the meter's run-to-run spread; not worth a switch. */
    const val MEANINGFUL_MS = 15.0

    data class Result(
        val baselineMs: Double?,
        val gameModeMs: Double?,
        /** Fastest codec measured (name, ms), if a codec sweep ran. */
        val bestCodec: Pair<String, Double>?,
    ) {
        /** Earbud game mode measurably helps. */
        val useGameMode: Boolean
            get() = baselineMs != null && gameModeMs != null && gameModeMs < baselineMs - MEANINGFUL_MS

        /** The delay calls will see with everything worth using turned on. */
        val bestMs: Double?
            get() = listOfNotNull(
                baselineMs,
                gameModeMs?.takeIf { useGameMode },
                bestCodec?.second,
            ).minOrNull()

        val savedMs: Double?
            get() {
                val base = baselineMs ?: return null
                val best = bestMs ?: return null
                return (base - best).takeIf { it >= MEANINGFUL_MS }
            }

        fun summary(): String {
            val best = bestMs ?: return "Couldn't measure. Hold the earbud's speaker against the phone's microphone for the whole test."
            val parts = mutableListOf<String>()
            if (useGameMode) parts += "earbud game mode"
            bestCodec?.takeIf { (_, ms) -> baselineMs == null || ms < baselineMs - MEANINGFUL_MS }?.let { parts += "${it.first} during calls" }
            val saved = savedMs
            return when {
                parts.isEmpty() -> "Your setup is already the fastest found: ${best.toInt()} ms."
                saved != null -> "Fastest: ${parts.joinToString(" + ")} at ${best.toInt()} ms, ${saved.toInt()} ms faster. Earshot will use it for calls."
                else -> "Fastest: ${parts.joinToString(" + ")} at ${best.toInt()} ms. Earshot will use it for calls."
            }
        }
    }
}
