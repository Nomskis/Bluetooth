package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun smallVideoIsEncodedInSoftwareSoTemporalLayersWorkWhateverTheCodec() {
        // video_encoder_software_fallback_wrapper.cc: ParseFieldTrial({resolution_threshold_px},
        // "WebRTC-Video-EncoderFallbackSettings"); unlike the VP8-only trial, any codec switches.
        val config = trials().getValue("WebRTC-Video-EncoderFallbackSettings")
        val (key, value) = config.split(':')
        assertEquals("resolution_threshold_px", key)
        val maxPixels = value.toInt()
        // 360p and below in software, 540p and up in hardware; above WebRTC's 320x180 floor.
        assertTrue(640 * 360 <= maxPixels && 960 * 540 > maxPixels)
        assertTrue(maxPixels > 320 * 180)
        // It takes precedence over the VP8-only trial, which would only confuse; it isn't set.
        assertFalse(trials().containsKey("WebRTC-VP8-Forced-Fallback-Encoder-v2"))
    }

    @Test
    fun gapsAreConcealedByOpusItself() {
        // audio_decoder_opus.cc: field_trial::IsEnabled("WebRTC-Audio-OpusGeneratePlc"), which
        // only looks for a value starting with "Enabled".
        assertTrue(trials().getValue(WebRtcTuning.OPUS_CONCEALMENT).startsWith("Enabled"))
    }

    @Test
    fun aKeyframeGoesOutAheadOfTheStaleVideoQueuedBeforeIt() {
        assertTrue(trials().getValue(WebRtcTuning.KEYFRAME_FLUSHING).startsWith("Enabled"))
    }

    @Test
    fun theJitterBufferHasRoomForALongStall() {
        // At 10 ms packets; WebRTC accepts 20 and up.
        assertTrue(WebRtcTuning.JITTER_BUFFER_MAX_PACKETS * 10 >= 1_000)
    }
}
