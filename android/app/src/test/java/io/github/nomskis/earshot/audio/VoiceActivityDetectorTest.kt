package io.github.nomskis.earshot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class VoiceActivityDetectorTest {
    private val random = Random(5)
    private fun noise(level: Double) = level * (0.7 + 0.6 * random.nextDouble())

    @Test
    fun detectsSpeechWithinTwentyMilliseconds() {
        val vad = VoiceActivityDetector()
        repeat(100) { assertNull(vad.process(noise(0.004))) } // a quiet room for 1 s
        assertNull(vad.process(0.08))
        assertEquals(true, vad.process(0.09)) // second loud 10 ms frame
        assertTrue(vad.speaking)
    }

    @Test
    fun shortPausesDontEndSpeechButLongOnesDo() {
        val vad = VoiceActivityDetector()
        repeat(50) { vad.process(noise(0.004)) }
        repeat(20) { vad.process(0.1) }
        repeat(15) { assertNull(vad.process(noise(0.004))) } // 150 ms gap between words
        assertTrue(vad.speaking)
        repeat(20) { vad.process(0.1) }
        var ended = false
        repeat(40) { if (vad.process(noise(0.004)) == false) ended = true }
        assertTrue(ended)
        assertFalse(vad.speaking)
    }

    @Test
    fun adaptsToASteadyNoisyBackground() {
        val vad = VoiceActivityDetector()
        // A loud but steady background (a fan, a gym) eventually stops counting as speech.
        var lastState: Boolean? = null
        repeat(3_000) { vad.process(noise(0.03))?.let { lastState = it } }
        assertFalse(vad.speaking)
        assertEquals(false, lastState ?: false)
        // Speech on top of it is still caught.
        vad.process(0.25)
        assertEquals(true, vad.process(0.25))
    }

    @Test
    fun neverTriggersOnNearSilence() {
        val vad = VoiceActivityDetector()
        repeat(1_000) { assertNull(vad.process(0.0005 * random.nextDouble())) }
    }

    @Test
    fun computesRmsOfPcm16() {
        val frames = 480
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { i -> buffer.putShort((if (i % 2 == 0) 16_384 else -16_384).toShort()) }
        buffer.flip()
        assertEquals(0.5, VoiceActivityDetector.rmsPcm16(buffer, frames, channels = 2), 1e-6)
    }

    @Test
    fun ringBufferKeepsTheMostRecentAudio() {
        val ring = PcmRingBuffer(sampleRate = 10, seconds = 2) // 20 samples
        ring.write(ShortArray(15) { it.toShort() })
        ring.write(ShortArray(10) { (100 + it).toShort() })
        val last = ring.last(1.0)
        assertEquals((100..109).map { it.toShort() }, last.toList())
        assertEquals(20, ring.last(5.0).size)
        assertEquals(5.toShort(), ring.last(5.0).first()) // 25 written into 20: samples 0-4 dropped
        ring.clear()
        assertEquals(0, ring.last(1.0).size)
    }
}
