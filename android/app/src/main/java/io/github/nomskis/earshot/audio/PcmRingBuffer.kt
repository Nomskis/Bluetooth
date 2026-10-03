package io.github.nomskis.earshot.audio

/**
 * Keeps the last few seconds of mono 16-bit audio. Written from the audio
 * thread, read rarely (when the user taps replay), so a simple lock is fine.
 */
class PcmRingBuffer(val sampleRate: Int, seconds: Int) {
    private val buffer = ShortArray(sampleRate * seconds)
    private var writeIndex = 0
    private var filled = 0

    @Synchronized
    fun write(samples: ShortArray, count: Int = samples.size) {
        for (i in 0 until count) {
            buffer[writeIndex] = samples[i]
            writeIndex = (writeIndex + 1) % buffer.size
        }
        filled = minOf(buffer.size, filled + count)
    }

    /** The most recent [seconds] of audio, oldest sample first. */
    @Synchronized
    fun last(seconds: Double): ShortArray {
        val count = minOf(filled, (seconds * sampleRate).toInt())
        val out = ShortArray(count)
        var read = (writeIndex - count + buffer.size) % buffer.size
        for (i in 0 until count) {
            out[i] = buffer[read]
            read = (read + 1) % buffer.size
        }
        return out
    }

    @Synchronized
    fun clear() {
        writeIndex = 0
        filled = 0
    }
}
