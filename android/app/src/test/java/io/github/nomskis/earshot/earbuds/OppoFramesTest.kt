package io.github.nomskis.earshot.earbuds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OppoFramesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun matchesTheDocumentedBatteryQuery() {
        // Documented raw frame: AA 07 00 00 06 01 [seq] 00 00
        assertArrayEquals(bytes(0xAA, 0x07, 0x00, 0x00, 0x06, 0x01, 0x2A, 0x00, 0x00), OppoFrames.encode(OppoFrames.Commands.BATTERY, 0x2A))
    }

    @Test
    fun encodesTheGameModeSwitch() {
        val frame = OppoFrames.encode(OppoFrames.Commands.FEATURE_SWITCH, 5, bytes(0x06, 0x01))
        assertArrayEquals(bytes(0xAA, 0x09, 0x00, 0x00, 0x03, 0x04, 0x05, 0x02, 0x00, 0x06, 0x01), frame)
    }

    @Test
    fun varintsUseSevenBitsPerByte() {
        assertArrayEquals(bytes(0x05), OppoFrames.varint(5))
        assertArrayEquals(bytes(0x7F), OppoFrames.varint(127))
        assertArrayEquals(bytes(0x80, 0x01), OppoFrames.varint(128))
        assertArrayEquals(bytes(0xAC, 0x02), OppoFrames.varint(300))
    }

    @Test
    fun decodesRepliesAcrossChunksAndSkipsGarbage() {
        val reply = OppoFrames.encode(0x8403, 5, bytes(0x00))
        val big = OppoFrames.encode(0x8100, 1, ByteArray(200) { 1 }) // two-byte length
        val stream = bytes(0x13, 0x37) + reply + big
        val decoder = OppoFrames.Decoder()
        val first = decoder.feed(stream.copyOfRange(0, 6))
        assertTrue(first.isEmpty())
        val rest = decoder.feed(stream.copyOfRange(6, stream.size))
        assertEquals(2, rest.size)
        assertEquals(0x8403, rest[0].command)
        assertEquals(OppoFrames.Commands.FEATURE_SWITCH, rest[0].requestCommand)
        assertEquals(0, rest[0].status)
        assertEquals(200, rest[1].payload.size)
    }
}
