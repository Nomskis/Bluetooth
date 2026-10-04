package io.github.nomskis.earshot.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LessDataTest {
    @Test
    fun capsVideoOnlyWhenOnAndNeverLoosensATighterCap() {
        assertEquals(null, LessData.videoKbps(on = false, cap = null))
        assertEquals(800, LessData.videoKbps(on = false, cap = 800))
        assertEquals(LessData.VIDEO_KBPS, LessData.videoKbps(on = true, cap = null))
        assertEquals(LessData.VIDEO_KBPS, LessData.videoKbps(on = true, cap = 800))
        // An overheating phone's tighter cap stays.
        assertEquals(150, LessData.videoKbps(on = true, cap = 150))
        assertEquals(LessData.VIDEO_FPS, LessData.videoFps(on = true, cap = 30))
        assertEquals(10, LessData.videoFps(on = true, cap = 10))
        assertEquals(null, LessData.videoFps(on = false, cap = null))
        assertEquals(VideoQuality.LOW, LessData.capture(on = true, chosen = VideoQuality.FULL_HD))
        assertEquals(VideoQuality.HIGH, LessData.capture(on = false, chosen = VideoQuality.HIGH))
    }
}
