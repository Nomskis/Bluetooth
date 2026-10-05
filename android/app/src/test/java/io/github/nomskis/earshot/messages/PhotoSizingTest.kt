package io.github.nomskis.earshot.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSizingTest {
    @Test
    fun decodesNoBiggerThanNeededButNeverSmaller() {
        // A 12 MP phone photo: decoded at a quarter, still 2000 px on the long side.
        assertEquals(2, PhotoSizing.sampleSize(4000, 3000, PhotoSizing.MAX_EDGE))
        assertEquals(1, PhotoSizing.sampleSize(1600, 1200, PhotoSizing.MAX_EDGE))
        assertEquals(4, PhotoSizing.sampleSize(8000, 6000, PhotoSizing.MAX_EDGE))
    }

    @Test
    fun fitsTheLongSideAndNeverEnlarges() {
        assertEquals(1600 to 1200, PhotoSizing.fit(2000, 1500, 1600))
        assertEquals(900 to 1600, PhotoSizing.fit(1125, 2000, 1600))
        assertEquals(800 to 600, PhotoSizing.fit(800, 600, 1600))
    }

    @Test
    fun turnsAPictureUprightFromItsExifOrientation() {
        assertEquals(0, PhotoSizing.rotation(1))
        assertEquals(90, PhotoSizing.rotation(PhotoSizing.ExifOrientation.ROTATE_90))
        assertEquals(180, PhotoSizing.rotation(PhotoSizing.ExifOrientation.ROTATE_180))
        assertEquals(270, PhotoSizing.rotation(PhotoSizing.ExifOrientation.ROTATE_270))
    }
}
