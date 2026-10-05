package io.github.nomskis.earshot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Zooming into a shared screen: the point under your fingers stays put, and it never slides off. */
class ScreenZoomTest {
    // A portrait screen fitted into a 1000x2000 view: 900x2000 with bars at the sides.
    private val content = 900f to 2000f
    private val view = 1000f to 2000f

    private fun ScreenZoom.zoom(factor: Float, fx: Float, fy: Float, px: Float = 0f, py: Float = 0f) =
        zoomed(factor, fx, fy, px, py, content.first, content.second, view.first, view.second)

    @Test
    fun pinchingKeepsThePointUnderTheFingers() {
        // Zoom 2x at 100 px right of and 200 px below the centre.
        val z = ScreenZoom().zoom(2f, 100f, 200f)
        assertEquals(2f, z.scale)
        // That point was 100/1 from the centre in the picture; now it's 2 * (100 - x0) + x == 100.
        assertEquals(-100f, z.x)
        assertEquals(-200f, z.y)
    }

    @Test
    fun itNeverSlidesPastTheEdges() {
        val z = ScreenZoom().zoom(2f, 0f, 0f, px = 5_000f, py = -5_000f)
        // At 2x the picture is 1800x4000 in a 1000x2000 view: 400 px spare sideways, 1000 up and down.
        assertEquals(400f, z.x)
        assertEquals(-1000f, z.y)
        // Back at 1x it's centred again.
        assertEquals(ScreenZoom(1f, 0f, 0f), z.zoom(0.1f, 0f, 0f))
    }

    @Test
    fun doubleTapZoomsInAtThePointAndBackOut() {
        val z = ScreenZoom().doubleTapped(200f, -300f, content.first, content.second, view.first, view.second)
        assertEquals(ScreenZoom.DOUBLE_TAP_SCALE, z.scale)
        assertEquals(-300f, z.x)
        assertEquals(450f, z.y)
        assertEquals(ScreenZoom(), z.doubleTapped(0f, 0f, content.first, content.second, view.first, view.second))
    }

    @Test
    fun zoomStaysBetweenTheWholeScreenAndSixTimes() {
        assertEquals(ScreenZoom.MAX_SCALE, ScreenZoom().zoom(100f, 0f, 0f).scale)
        assertEquals(1f, ScreenZoom().zoom(0.2f, 0f, 0f).scale)
    }

    @Test
    fun aHeldFingerLandsOnTheRightSpotOfTheSharedPicture() {
        // A 1080 x 2400 phone screen fitted into a 1080 x 2000 view: 900 x 2000, 90 px in from each side.
        fun at(zoom: ScreenZoom, x: Float, y: Float) = zoom.frameAt(x, y, 1080f, 2000f, 1080f, 2400f)
        assertEquals(0.5f to 0.5f, at(ScreenZoom(), 540f, 1000f))
        assertEquals(0f to 0f, at(ScreenZoom(), 90f, 0f))
        // Beside the picture, or before the first frame: nowhere.
        assertNull(at(ScreenZoom(), 50f, 500f))
        assertNull(ScreenZoom().frameAt(540f, 1000f, 1080f, 2000f, 0f, 0f))
        // Zoomed in twice about the centre: 450 px right of it is a quarter of the picture over.
        assertEquals(0.75f to 0.5f, at(ScreenZoom(scale = 2f), 990f, 1000f))
        // And moved 100 px right: the centre of the picture moved with it.
        assertEquals(0.5f to 0.5f, at(ScreenZoom(scale = 2f, x = 100f), 640f, 1000f))
    }
}
