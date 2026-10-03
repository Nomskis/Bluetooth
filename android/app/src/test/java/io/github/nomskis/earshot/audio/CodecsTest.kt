package io.github.nomskis.earshot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsTest {
    @Test
    fun namesTheStandardCodecs() {
        assertEquals("SBC", Codecs.name(0))
        assertEquals("AAC", Codecs.name(1))
        assertEquals("LDAC", Codecs.name(4))
        assertEquals("Codec 11", Codecs.name(11))
    }

    @Test
    fun decodesFlagFields() {
        assertEquals(48_000, Codecs.sampleRate(0x02))
        assertEquals(96_000, Codecs.sampleRate(0x08 or 0x02))
        assertEquals(44_100, Codecs.sampleRate(0x01))
        assertNull(Codecs.sampleRate(0))
        assertEquals(24, Codecs.bitsPerSample(0x02))
        assertNull(Codecs.bitsPerSample(0))
    }

    @Test
    fun summaryReadsNaturally() {
        val info = CodecInfo("Buds", "AAC", 44_100, 16, emptyList(), 0)
        assertEquals("AAC · 44.1 kHz · 16-bit", info.summary)
        assertEquals("LHDC V5 · 48 kHz", CodecInfo(null, "LHDC V5", 48_000, null, emptyList(), 0).summary)
    }

    @Test
    fun knowsWhichCodecsAreHighResolution() {
        assertTrue(Codecs.isHighResolution("LDAC"))
        assertTrue(Codecs.isHighResolution("LHDC V5"))
        assertFalse(Codecs.isHighResolution("AAC"))
        assertFalse(Codecs.isHighResolution("SBC"))
    }
}
