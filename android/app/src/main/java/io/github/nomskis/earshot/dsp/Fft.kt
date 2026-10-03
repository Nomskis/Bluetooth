package io.github.nomskis.earshot.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iterative radix-2 complex FFT, in place. Sizes must be powers of two. */
object Fft {

    fun nextPowerOfTwo(n: Int): Int {
        require(n in 1..(1 shl 30)) { "size out of range: $n" }
        var size = 1
        while (size < n) size = size shl 1
        return size
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    /** Inverse transform, including the 1/n scaling. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, inverse = true)
        val scale = 1.0 / re.size
        for (i in re.indices) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "size must be a power of two, got $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = (if (inverse) 2 else -2) * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }
}
