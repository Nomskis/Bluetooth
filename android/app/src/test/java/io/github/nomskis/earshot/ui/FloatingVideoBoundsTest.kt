package io.github.nomskis.earshot.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingVideoBoundsTest {
    // A 1080x2400 screen, a 300x430 video, bars of 80 and 60, 40 at the sides.
    private val bounds = FloatingVideoBounds(IntSize(300, 430), IntSize(1080, 2400), top = 80, bottom = 60, sides = 40)

    @Test
    fun theSmallVideoGoesWhereverItsDraggedOnTheScreen() {
        assertEquals(Offset(500f, 900f), bounds.clamp(Offset(500f, 900f)))
    }

    @Test
    fun itStaysOnTheScreenAndClearOfTheBars() {
        assertEquals(Offset(40f, 80f), bounds.clamp(Offset(-500f, -500f)))
        assertEquals(Offset(1080f - 300 - 40, 2400f - 430 - 60), bounds.clamp(Offset(5000f, 5000f)))
    }
}
