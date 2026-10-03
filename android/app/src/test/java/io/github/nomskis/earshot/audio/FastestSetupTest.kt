package io.github.nomskis.earshot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastestSetupTest {
    @Test
    fun turnsOnGameModeWhenItClearlyHelps() {
        val r = FastestSetup.Result(baselineMs = 240.0, gameModeMs = 130.0, bestCodec = null)
        assertTrue(r.useGameMode)
        assertEquals(130.0, r.bestMs!!, 0.0)
        assertEquals(110.0, r.savedMs!!, 0.0)
        assertTrue(r.summary().contains("earbud game mode"))
    }

    @Test
    fun ignoresDifferencesWithinTheMetersSpread() {
        val r = FastestSetup.Result(baselineMs = 200.0, gameModeMs = 192.0, bestCodec = "AAC" to 195.0)
        assertFalse(r.useGameMode)
        assertNull(r.savedMs)
        assertTrue(r.summary().startsWith("Your setup is already the fastest"))
    }

    @Test
    fun combinesGameModeAndCodec() {
        val r = FastestSetup.Result(baselineMs = 260.0, gameModeMs = 150.0, bestCodec = "SBC" to 120.0)
        assertEquals(120.0, r.bestMs!!, 0.0)
        assertTrue(r.summary().contains("earbud game mode + SBC during calls"))
    }

    @Test
    fun saysSoWhenNothingMeasured() {
        assertTrue(FastestSetup.Result(null, null, null).summary().startsWith("Couldn't measure"))
    }
}
