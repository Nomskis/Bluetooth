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
    fun asksForTheAudioPacketLengthOnly() {
        // A browser peer may have asked for 10 ms; ours asks for 20 back.
        val tuned = SdpTuning.preferAudioPacketTime(offer.replace("a=ptime:20", "a=ptime:10"), ms = 20)
        val lines = tuned.split("\r\n")
        val audioIndex = lines.indexOfFirst { it.startsWith("m=audio") }
        val videoIndex = lines.indexOfFirst { it.startsWith("m=video") }
        assertEquals("a=ptime:20", lines[audioIndex + 1])
        assertEquals(1, lines.count { it.startsWith("a=ptime:") })
        assertFalse(lines.subList(videoIndex, lines.size).any { it.startsWith("a=ptime") })
        assertTrue(tuned.endsWith("\r\n"))
        // Everything else is untouched.
        assertEquals(offer.replace("a=ptime:20\r\n", ""), tuned.replace("a=ptime:20\r\n", ""))
        assertEquals(20, WebRtcTuning.AUDIO_PACKET_MS)
    }

    @Test
    fun isIdempotent() {
        val once = SdpTuning.preferAudioPacketTime(offer)
        assertEquals(once, SdpTuning.preferAudioPacketTime(once))
    }

    @Test
    fun letsLostVoicePacketsBeResent() {
        val tuned = SdpTuning.enableAudioNack(offer)
        val lines = tuned.split("\r\n")
        // Right after Opus's rtpmap, for Opus only (not RED, not video).
        assertEquals("a=rtcp-fb:111 nack", lines[lines.indexOf("a=rtpmap:111 opus/48000/2") + 1])
        assertEquals(1, lines.count { it.contains("nack") })
        assertEquals(tuned, SdpTuning.enableAudioNack(tuned))
        assertEquals("v=0\r\n", SdpTuning.enableAudioNack("v=0\r\n"))
    }

    @Test
    fun findsTheFirstAudioCodec() {
        assertEquals("red", SdpTuning.firstAudioCodec(offer))
        assertEquals("opus", SdpTuning.firstAudioCodec(offer.replace("SAVPF 63 111 9", "SAVPF 111 63 9")))
        assertNull(SdpTuning.firstAudioCodec("v=0\r\n"))
        assertNull(SdpTuning.firstAudioCodec(null))
    }

    @Test
    fun capsIncomingVideoInTheVideoSectionOnly() {
        val withC = offer.replace("m=video 9 UDP/TLS/RTP/SAVPF 96\r\n", "m=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\n")
        for (sdp in listOf(offer, withC)) {
            val lines = SdpTuning.capVideoBandwidth(sdp, 800).split("\r\n")
            val video = lines.indexOfFirst { it.startsWith("m=video") }
            assertEquals(1, lines.count { it == "b=AS:800" })
            assertEquals(1, lines.count { it == "b=TIAS:800000" })
            assertTrue(lines.indexOf("b=AS:800") > video)
            // b= follows c= when there is one (RFC 4566 order).
            val c = lines.withIndex().firstOrNull { it.index > video && it.value.startsWith("c=") }?.index
            if (c != null) assertEquals(c + 1, lines.indexOf("b=AS:800"))
            // The audio section is untouched.
            assertFalse(lines.subList(0, video).any { it.startsWith("b=") })
        }
    }

    @Test
    fun replacesOrRemovesAnExistingCap() {
        val capped = SdpTuning.capVideoBandwidth(offer, 800)
        val recapped = SdpTuning.capVideoBandwidth(capped, 500)
        assertFalse(recapped.contains("b=AS:800"))
        assertTrue(recapped.contains("b=AS:500"))
        val removed = SdpTuning.capVideoBandwidth(capped, null)
        assertFalse(removed.contains("b=AS"))
        assertFalse(removed.contains("b=TIAS"))
        assertEquals(offer, removed)
    }

    @Test
    fun asksForHdVoiceOnTheOpusLine() {
        val tuned = SdpTuning.preferHdVoice(offer)
        assertTrue(tuned.contains("a=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=48000"))
        // Only the Opus line changes, and applying it twice changes nothing more.
        assertEquals(tuned, SdpTuning.preferHdVoice(tuned))
        assertTrue(tuned.contains("a=fmtp:63 111/111"))
        // An Opus line without fmtp gets one.
        val bare = offer.replace("a=fmtp:111 minptime=10;useinbandfec=1\r\n", "")
        assertTrue(SdpTuning.preferHdVoice(bare).contains("a=rtpmap:111 opus/48000/2\r\na=fmtp:111 maxaveragebitrate=48000"))
        assertEquals("v=0\r\n", SdpTuning.preferHdVoice("v=0\r\n"))
    }
}
