package io.github.nomskis.earshot.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Builds realistic fake microphone recordings for the sonar tests. */
internal object SonarTestSignals {
    const val RATE = 48_000

    /** The chirp, evaluated at a continuous time so it can start between samples. */
    fun chirpValue(tSeconds: Double, durationMs: Double = Chirp.DEFAULT_DURATION_MS): Double {
        val n = (RATE * durationMs / 1000.0).toInt()
        val seconds = n.toDouble() / RATE
        if (tSeconds < 0 || tSeconds >= seconds) return 0.0
        val f0 = Chirp.DEFAULT_START_HZ
        val rate = (Chirp.DEFAULT_END_HZ - f0) / seconds
        val phase = 2 * PI * (f0 * tSeconds + 0.5 * rate * tSeconds * tSeconds)
        val window = 0.5 - 0.5 * cos(2 * PI * (tSeconds * RATE) / (n - 1))
        return window * sin(phase)
    }

    /**
     * Adds a chirp starting at a fractional sample position, plus a quieter
     * echo a few milliseconds later (sound bouncing inside the ear tip).
     */
    fun addChirp(into: DoubleArray, startSample: Double, gain: Double, echoGain: Double = 0.4, echoMs: Double = 2.5) {
        val first = startSample.toInt().coerceAtLeast(0)
        val last = (startSample + RATE * 0.05).toInt().coerceAtMost(into.size - 1)
        for (i in first..last) {
            val t = (i - startSample) / RATE
            into[i] += gain * chirpValue(t) + gain * echoGain * chirpValue(t - echoMs / 1000)
        }
    }

    /** Gym-ish background: noise plus a bass line and a hi-hat-like tone. */
    fun background(samples: Int, noise: Double, seed: Int = 7): DoubleArray {
        val random = Random(seed)
        return DoubleArray(samples) { i ->
            val t = i.toDouble() / RATE
            noise * (random.nextDouble() * 2 - 1) +
                0.2 * sin(2 * PI * 110 * t) * (0.6 + 0.4 * sin(2 * PI * 2 * t)) +
                0.05 * sin(2 * PI * 9_000 * t)
        }
    }
}
