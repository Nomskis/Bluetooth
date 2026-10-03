package io.github.nomskis.earshot.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {
    @Test
    fun matchesANaiveDft() {
        val random = Random(1)
        for (n in listOf(1, 2, 8, 64, 256)) {
            val re = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
            val im = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
            val expectedRe = DoubleArray(n)
            val expectedIm = DoubleArray(n)
            for (k in 0 until n) for (t in 0 until n) {
                val angle = -2 * PI * k * t / n
                expectedRe[k] += re[t] * cos(angle) - im[t] * sin(angle)
                expectedIm[k] += re[t] * sin(angle) + im[t] * cos(angle)
            }
            Fft.forward(re, im)
            for (k in 0 until n) {
                assertEquals(expectedRe[k], re[k], 1e-9)
                assertEquals(expectedIm[k], im[k], 1e-9)
            }
        }
    }

    @Test
    fun inverseUndoesForward() {
        val random = Random(2)
        val original = DoubleArray(1024) { random.nextDouble() }
        val re = original.copyOf()
        val im = DoubleArray(1024)
        Fft.forward(re, im)
        Fft.inverse(re, im)
        for (i in original.indices) {
            assertEquals(original[i], re[i], 1e-9)
            assertEquals(0.0, im[i], 1e-9)
        }
    }

    @Test
    fun nextPowerOfTwo() {
        assertEquals(1, Fft.nextPowerOfTwo(1))
        assertEquals(1024, Fft.nextPowerOfTwo(1000))
        assertEquals(1024, Fft.nextPowerOfTwo(1024))
    }
}
