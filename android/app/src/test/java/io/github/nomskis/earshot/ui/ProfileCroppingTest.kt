package io.github.nomskis.earshot.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileCroppingTest {
    // A 2000 x 1000 photo in a 1000 px square: it just covers it at 1x, 1000 px wider than the square.

    @Test
    fun atFirstTheMiddleSquareIsTaken() {
        assertEquals(Triple(500, 0, 1000), ProfileCropping.crop(2000, 1000, 1000f, 1f, Offset.Zero))
    }

    @Test
    fun movingAndZoomingTakeWhatShowsInTheSquare() {
        // Moved 500 px right: the left end shows.
        assertEquals(Triple(0, 0, 1000), ProfileCropping.crop(2000, 1000, 1000f, 1f, Offset(500f, 0f)))
        // Twice as big: half as much of it, from the middle.
        assertEquals(Triple(750, 250, 500), ProfileCropping.crop(2000, 1000, 1000f, 2f, Offset.Zero))
    }

    @Test
    fun itCantBeMovedSoTheSquareShowsNothing() {
        assertEquals(Offset(500f, 0f), ProfileCropping.clamp(Offset(800f, 300f), 2000, 1000, 1000f, 1f))
        assertEquals(Offset(-1500f, 500f), ProfileCropping.clamp(Offset(-4000f, 900f), 2000, 1000, 1000f, 2f))
    }
}
