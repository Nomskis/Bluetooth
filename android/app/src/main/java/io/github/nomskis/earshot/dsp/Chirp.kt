package io.github.nomskis.earshot.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A short Hann-windowed linear frequency sweep. Sweeps correlate into one
 * sharp peak (good timing precision) and survive noise, music and the small
 * speakers inside earbuds much better than clicks or tones.
 */
object Chirp {
    const val DEFAULT_START_HZ = 1_500.0
    const val DEFAULT_END_HZ = 7_000.0
    const val DEFAULT_DURATION_MS = 30.0

    fun generate(
        sampleRate: Int,
        durationMs: Double = DEFAULT_DURATION_MS,
        startHz: Double = DEFAULT_START_HZ,
        endHz: Double = DEFAULT_END_HZ,
        amplitude: Double = 1.0,
    ): DoubleArray {
        val n = (sampleRate * durationMs / 1000.0).toInt()
        val seconds = n.toDouble() / sampleRate
        val rate = (endHz - startHz) / seconds
        return DoubleArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val phase = 2 * PI * (startHz * t + 0.5 * rate * t * t)
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            amplitude * window * sin(phase)
        }
    }

    fun toPcm16(samples: DoubleArray): ShortArray = ShortArray(samples.size) { i ->
        (samples[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
    }
}
