package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTuningTest {
    // Trimmed from a real Chrome offer.
    private val offer = listOf(
        "v=0",
        "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1",
        "m=audio 9 UDP/TLS/RTP/SAVPF 63 111 9",
        "c=IN IP4 0.0.0.0",
        "a=mid:0",
        "a=rtpmap:63 red/48000/2",
        "a=fmtp:63 111/111",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=ptime:20",
        "a=rtpmap:9 G722/8000",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "a=mid:1",
        "a=rtpmap:96 VP8/90000",
        "",
    ).joinToString("\r\n")

    @Test
    fun asksForTenMillisecondAudioPacketsOnly() {
        val tuned = SdpTuning.preferLowLatencyAudio(offer)
        val lines = tuned.split("\r\n")
        val audioIndex = lines.indexOfFirst { it.startsWith("m=audio") }
        val videoIndex = lines.indexOfFirst { it.startsWith("m=video") }
        assertEquals("a=ptime:10", lines[audioIndex + 1])
        assertEquals(1, lines.count { it.startsWith("a=ptime:") })
        assertFalse(lines.subList(videoIndex, lines.size).any { it.startsWith("a=ptime") })
        assertTrue(tuned.endsWith("\r\n"))
        // Everything else is untouched.
        assertEquals(offer.replace("a=ptime:20\r\n", ""), tuned.replace("a=ptime:10\r\n", ""))
    }

    @Test
    fun isIdempotent() {
        val once = SdpTuning.preferLowLatencyAudio(offer)
        assertEquals(once, SdpTuning.preferLowLatencyAudio(once))
    }

    @Test
    fun findsTheFirstAudioCodec() {
        assertEquals("red", SdpTuning.firstAudioCodec(offer))
        assertEquals("opus", SdpTuning.firstAudioCodec(offer.replace("SAVPF 63 111 9", "SAVPF 111 63 9")))
        assertNull(SdpTuning.firstAudioCodec("v=0\r\n"))
        assertNull(SdpTuning.firstAudioCodec(null))
    }
}
