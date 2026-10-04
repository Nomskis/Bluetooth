package io.github.nomskis.earshot.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedCallTest {
    @Test
    fun offeredOnlyWhileRecent() {
        val call = InterruptedCall("gym", withVideo = true, aliveAtMillis = 1_000_000)
        assertTrue(call.isRecent(1_000_000 + 60_000))
        assertTrue(call.isRecent(1_000_000 + InterruptedCall.REJOIN_WINDOW_MS))
        assertFalse(call.isRecent(1_000_000 + InterruptedCall.REJOIN_WINDOW_MS + 1))
        assertFalse(call.isRecent(999_000)) // clock went backwards
    }
}
