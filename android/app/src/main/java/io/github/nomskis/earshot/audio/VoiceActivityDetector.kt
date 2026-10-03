package io.github.nomskis.earshot.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A small, fast voice detector for 10 ms frames of decoded call audio.
 *
 * It tracks the noise floor (drops quickly, rises slowly) and calls it speech
 * when the level stays clearly above that floor. Speech starts after
 * [attackFrames] loud frames (20 ms by default, quick enough to beat the
 * Bluetooth delay by a wide margin) and ends after [hangoverFrames] quiet ones,
 * so short pauses between words don't flicker.
 */
class VoiceActivityDetector(
    private val attackFrames: Int = 2,
    private val hangoverFrames: Int = 30,
    /** Absolute floor, about -50 dBFS, so near-silence never counts. */
    private val minLevel: Double = 0.003,
    /** How far above the noise floor speech has to be. */
    private val ratio: Double = 3.0,
) {
    var speaking: Boolean = false
        private set

    private var noiseFloor = 0.01
    private var loud = 0
    private var quiet = 0

    /** Feeds one frame's RMS level (0..1). Returns the new state when it changes, else null. */
    fun process(rms: Double): Boolean? {
        noiseFloor = if (rms < noiseFloor) {
            noiseFloor * 0.9 + rms * 0.1
        } else {
            noiseFloor * 0.998 + rms * 0.002
        }
        val threshold = max(minLevel, noiseFloor * ratio)
        if (rms > threshold) {
            loud++
            quiet = 0
        } else {
            quiet++
            loud = 0
        }
        if (!speaking && loud >= attackFrames) {
            speaking = true
            return true
        }
        if (speaking && quiet >= hangoverFrames) {
            speaking = false
            return false
        }
        return null
    }

    companion object {
        /** RMS of 16-bit little-endian PCM, all channels mixed, as 0..1. */
        fun rmsPcm16(data: java.nio.ByteBuffer, frames: Int, channels: Int): Double {
            val samples = frames * channels
            if (samples <= 0) return 0.0
            var sum = 0.0
            val base = data.position()
            for (i in 0 until samples) {
                val lo = data.get(base + 2 * i).toInt() and 0xff
                val hi = data.get(base + 2 * i + 1).toInt()
                val v = ((hi shl 8) or lo).toShort() / 32768.0
                sum += v * v
            }
            return sqrt(sum / samples)
        }
    }
}
