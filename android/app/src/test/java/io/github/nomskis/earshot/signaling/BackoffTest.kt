package io.github.nomskis.earshot.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    @Test
    fun growsExponentiallyAndCaps() {
        val noJitter = Backoff(baseMs = 500, maxMs = 8_000, jitter = 0.0)
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..6).map(noJitter::delayFor))
        assertEquals(8_000L, noJitter.delayFor(1_000))
    }

    @Test
    fun jitterStaysInRange() {
        val backoff = Backoff(baseMs = 1_000, maxMs = 1_000, jitter = 0.25, random = Random(42))
        repeat(100) {
            val d = backoff.delayFor(1)
            assertTrue("$d out of range", d in 750..1_250)
        }
    }
}
