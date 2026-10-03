package io.github.nomskis.earshot.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/** A detected copy of the template inside a recording. */
data class Peak(
    /** Start of the template in the recording, in (fractional) samples. */
    val position: Double,
    /** Normalized correlation at the peak, 0..1. */
    val score: Double,
    /** Peak score divided by the strongest competing peak in the window. */
    val sharpness: Double,
)

/**
 * Normalized cross-correlation between a recording and a known template,
 * computed with FFTs. A score of 1 means the recording contains an exact
 * (scaled) copy of the template at that position.
 */
class MatchedFilter(private val recording: DoubleArray, template: DoubleArray) {
    private val templateLength = template.size
    private val scores: DoubleArray

    init {
        require(template.isNotEmpty() && recording.size >= template.size) { "recording shorter than template" }
        val size = Fft.nextPowerOfTwo(recording.size + template.size)
        val aRe = DoubleArray(size).also { recording.copyInto(it) }
        val aIm = DoubleArray(size)
        val bRe = DoubleArray(size).also { template.copyInto(it) }
        val bIm = DoubleArray(size)
        Fft.forward(aRe, aIm)
        Fft.forward(bRe, bIm)
        // Correlation = IFFT(A * conj(B)).
        for (i in 0 until size) {
            val re = aRe[i] * bRe[i] + aIm[i] * bIm[i]
            val im = aIm[i] * bRe[i] - aRe[i] * bIm[i]
            aRe[i] = re
            aIm[i] = im
        }
        Fft.inverse(aRe, aIm)

        val templateNorm = sqrt(template.sumOf { it * it })
        // Prefix sums of squares give the recording's energy under every window.
        val energy = DoubleArray(recording.size + 1)
        for (i in recording.indices) energy[i + 1] = energy[i] + recording[i] * recording[i]

        val lags = recording.size - templateLength + 1
        scores = DoubleArray(lags) { lag ->
            val windowEnergy = energy[lag + templateLength] - energy[lag]
            val denom = templateNorm * sqrt(windowEnergy)
            if (denom <= 1e-12) 0.0 else abs(aRe[lag]) / denom
        }
    }

    val size: Int get() = scores.size

    fun score(lag: Int): Double = scores[lag]

    /**
     * Best match whose start lies in [from, until). Returns null when nothing
     * in the window looks like the template.
     */
    fun findPeak(from: Int, until: Int, minScore: Double = 0.25, minSharpness: Double = 1.5): Peak? {
        val start = from.coerceAtLeast(0)
        val end = until.coerceAtMost(scores.size)
        if (end - start < 3) return null

        var best = start
        for (i in start until end) if (scores[i] > scores[best]) best = i
        val bestScore = scores[best]
        if (bestScore < minScore) return null

        // The strongest competitor at least half a template away (so not the same peak).
        val guard = templateLength / 2
        var competitor = 0.0
        for (i in start until end) {
            if (abs(i - best) > guard && scores[i] > competitor) competitor = scores[i]
        }
        val sharpness = if (competitor <= 1e-9) Double.POSITIVE_INFINITY else bestScore / competitor
        if (sharpness < minSharpness) return null

        return Peak(position = best + parabolicOffset(best), score = bestScore, sharpness = sharpness)
    }

    /** Sub-sample peak position from a parabola through the peak and its neighbours. */
    private fun parabolicOffset(i: Int): Double {
        if (i <= 0 || i >= scores.size - 1) return 0.0
        val a = scores[i - 1]
        val b = scores[i]
        val c = scores[i + 1]
        val denom = a - 2 * b + c
        if (abs(denom) < 1e-12) return 0.0
        return (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
    }
}
