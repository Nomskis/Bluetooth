package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcTuningTest {
    /** WebRTC reads field trials as "Name/Value/" pairs; anything else is silently ignored. */
    private fun trials(): Map<String, String> {
        val parts = WebRtcTuning.fieldTrials.split('/')
        assertEquals("ends with a slash", "", parts.last())
        val pairs = parts.dropLast(1)
        assertEquals("names and values come in pairs", 0, pairs.size % 2)
        return pairs.chunked(2).associate { (name, value) -> name to value }
    }

    @Test
    fun redundantAudioIsOnWithTheRedundancyWebRtcParses() {
        // audio_encoder_copy_red.cc: sscanf("Enabled-%zu"), at most 9, else the default of 1.
        val red = trials().getValue("WebRTC-Audio-Red-For-Opus")
        assertTrue(red.startsWith("Enabled")) // what turns RED on
        val level = red.removePrefix("Enabled-").toInt()
        assertTrue(level in 2..9)
        assertEquals(WebRtcTuning.RED_REDUNDANCY, level)
    }

    @Test
    fun theJitterBufferCoversMoreSpikesThanWebRtcsDefault() {
        // delay_manager.cc parses "quantile:<double>"; the default is 0.95.
        val config = trials().getValue("WebRTC-Audio-NetEqDelayManagerConfig")
        val quantile = config.removePrefix("quantile:").toDouble()
        assertTrue(quantile > 0.95 && quantile < 1.0)
    }

    @Test
    fun theJitterBufferHasRoomForALongStall() {
        // At 10 ms packets; WebRTC accepts 20 and up.
        assertTrue(WebRtcTuning.JITTER_BUFFER_MAX_PACKETS * 10 >= 1_000)
    }
}
