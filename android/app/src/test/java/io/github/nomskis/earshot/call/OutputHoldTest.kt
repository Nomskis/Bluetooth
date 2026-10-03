package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputHoldTest {
    @Test
    fun earbudsLeavingHoldsTheirVoiceUntilTheyAreBack() {
        val hold = OutputHold(startedPersonal = true)
        assertNull(hold.onOutput(true))
        assertEquals(true, hold.onOutput(false)) // earbuds died: speaker
        assertNull(hold.onOutput(false)) // still the speaker
        assertEquals(false, hold.onOutput(true)) // back in
        assertEquals(true, hold.onOutput(false)) // and gone again
    }

    @Test
    fun aCallStartedOnTheSpeakerIsNeverHeld() {
        val hold = OutputHold(startedPersonal = false)
        assertNull(hold.onOutput(false))
        assertNull(hold.onOutput(true)) // earbuds connected later
        assertEquals(true, hold.onOutput(false)) // from then on they count
    }
}
