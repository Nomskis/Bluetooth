package io.github.nomskis.earshot.earbuds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarbudProtocolsTest {
    // ---- shared ----

    @Test
    fun crcCheckValues() {
        val check = "123456789".toByteArray()
        assertEquals(0x4B37, Crc16.modbus(check))
        assertEquals(0x31C3, Crc16.xmodem(check))
    }

    // ---- OPPO / OnePlus / realme ----

    @Test
    fun oppoCapabilitiesFromARealHelloReply() {
        // OnePlus Buds 4 hello reply, as published by QuickBuds: no game sound, so switch 06.
        val caps = OppoFrames.Capabilities.parse(hex("00 FF 77 5A EA 67 0E 20 07"))!!
        assertTrue(caps.featureSwitch)
        assertFalse(caps.gameSound)
        assertEquals(0x06, OppoFrames.gameModeId(caps, null))
    }

    @Test
    fun oppoPicksTheSwitchTheEarbudsList() {
        val gameSound = OppoFrames.Capabilities(featureSwitch = true, gameSound = true)
        assertEquals(0x28, OppoFrames.gameModeId(gameSound, null))
        assertEquals(0x06, OppoFrames.gameModeId(gameSound, mapOf(0x06 to 0)))
        assertEquals(0x28, OppoFrames.gameModeId(null, mapOf(0x28 to 1)))
        val states = OppoFrames.parseFeatureStates(hex("00 02 06 01 1B 00"))!!
        assertEquals(mapOf(0x06 to 1, 0x1B to 0), states)
    }

    /** realme / OPPO earbuds as the vendor app sees them; [gameId] is the model's switch. */
    private fun oppoEarbuds(gameId: Int, initial: Int, honours: Boolean = true): FakeEarbuds {
        var value = initial
        val decoder = OppoFrames.Decoder()
        return FakeEarbuds { bytes ->
            decoder.feed(bytes).map { p ->
                val reply = p.command or 0x8000
                when (p.command) {
                    OppoFrames.Commands.HELLO ->
                        OppoFrames.encode(reply, p.seq, if (gameId == 0x28) hex("00 80 00 00 00 00 00 02 00") else hex("00 80"))
                    OppoFrames.Commands.FEATURE_STATES -> {
                        val asked = p.payload.drop(1).map { it.toInt() and 0xFF }.filter { it == gameId }
                        val body = byteArrayOf(0, asked.size.toByte()) + asked.flatMap { listOf(it.toByte(), value.toByte()) }
                        OppoFrames.encode(reply, p.seq, body)
                    }
                    OppoFrames.Commands.FEATURE_SWITCH -> {
                        if (honours && (p.payload[0].toInt() and 0xFF) == gameId) value = p.payload[1].toInt()
                        OppoFrames.encode(reply, p.seq, hex("00"))
                    }
                    else -> OppoFrames.encode(reply, p.seq, hex("01"))
                }
            }
        }
    }

    @Test
    fun oppoTurnsGameModeOnAndReportsTheOldState() {
        val buds = oppoEarbuds(gameId = 0x06, initial = 0)
        assertEquals(DriverResult.Ok(wasOn = false), OppoSession(buds).setGameMode(true))
        // The switch went to 06, value 1.
        val switch = OppoFrames.Decoder().feed(buds.written.reduce(ByteArray::plus)).single { it.command == OppoFrames.Commands.FEATURE_SWITCH }
        assertArrayEquals(hex("06 01"), switch.payload)
    }

    @Test
    fun oppoUsesSwitch28OnGameSoundModels() {
        val buds = oppoEarbuds(gameId = 0x28, initial = 0)
        assertEquals(DriverResult.Ok(wasOn = false), OppoSession(buds).setGameMode(true))
        val switch = OppoFrames.Decoder().feed(buds.written.reduce(ByteArray::plus)).single { it.command == OppoFrames.Commands.FEATURE_SWITCH }
        assertArrayEquals(hex("28 01"), switch.payload)
    }

    @Test
    fun oppoLeavesItAloneWhenAlreadyOn() {
        val buds = oppoEarbuds(gameId = 0x06, initial = 1)
        assertEquals(DriverResult.Ok(wasOn = true), OppoSession(buds).setGameMode(true))
        assertTrue(OppoFrames.Decoder().feed(buds.written.reduce(ByteArray::plus)).none { it.command == OppoFrames.Commands.FEATURE_SWITCH })
    }

    @Test
    fun oppoCatchesAnAckThatDidNothing() {
        val buds = oppoEarbuds(gameId = 0x06, initial = 0, honours = false)
        assertEquals(DriverResult.Rejected(OppoSession.NOT_APPLIED), OppoSession(buds).setGameMode(true))
    }

    @Test
    fun oppoSilenceIsNoAnswer() {
        assertEquals(DriverResult.NoAnswer, OppoSession(FakeEarbuds { emptyList() }).setGameMode(true))
    }

    // ---- Nothing / CMF ----

    @Test
    fun nothingFramesCarryAModbusCrc() {
        val frame = NothingFrames.encode(NothingFrames.Types.LATENCY_SET, 7, hex("01 00"))
        assertArrayEquals(hex("55 60 01 40 F0 02 00 07 01 00"), frame.copyOfRange(0, 10))
        val crc = Crc16.modbus(frame, 0, 10)
        assertEquals(crc and 0xFF, frame[10].toInt() and 0xFF)
        assertEquals(crc shr 8, frame[11].toInt() and 0xFF)
        val decoded = NothingFrames.Decoder().feed(hex("00 13") + frame).single()
        assertEquals(NothingFrames.Types.LATENCY_SET, decoded.type)
        assertArrayEquals(hex("01 00"), decoded.payload)
    }

    @Test
    fun nothingDropsCorruptFrames() {
        val frame = NothingFrames.encode(NothingFrames.Types.LATENCY_REPLY, 1, hex("01"))
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertTrue(NothingFrames.Decoder().feed(frame).isEmpty())
    }

    @Test
    fun nothingSessionSwitchesAndVerifies() {
        var on = false
        val decoder = NothingFrames.Decoder()
        val buds = FakeEarbuds { bytes ->
            decoder.feed(bytes).mapNotNull { p ->
                when (p.type) {
                    NothingFrames.Types.LATENCY_GET -> NothingFrames.encode(NothingFrames.Types.LATENCY_REPLY, p.seq, byteArrayOf(if (on) 1 else 0))
                    NothingFrames.Types.LATENCY_SET -> {
                        on = p.payload[0].toInt() == 1
                        NothingFrames.encode(NothingFrames.Types.LATENCY_NOTIFY, p.seq, byteArrayOf(if (on) 1 else 2, 0))
                    }
                    else -> null
                }
            }
        }
        assertEquals(DriverResult.Ok(wasOn = false), NothingSession(buds).setLowLatency(true))
        assertTrue(on)
        assertEquals(DriverResult.Ok(wasOn = true), NothingSession(buds).setLowLatency(false))
        assertFalse(on)
    }

    // ---- Xiaomi / Redmi ----

    @Test
    fun xiaomiAuthMatchesReferenceVectors() {
        // Produced by two independent open-source implementations (Gadgetbridge, BudsLink).
        assertEquals("BC A5 90 5B C8 49 39 2E 7B F9 FD CD C5 70 EF 77", XiaomiAuth.respond(ByteArray(16)).toHex())
        assertEquals(
            "FA CD 31 A7 EC 31 3D 13 A4 CE C6 4D 52 D2 7E 21",
            XiaomiAuth.respond(ByteArray(16) { (it + 1).toByte() }).toHex(),
        )
        assertEquals(
            "52 54 62 4D 57 B8 68 F8 58 02 14 BE 74 48 BD A5",
            XiaomiAuth.respond(ByteArray(16) { (0xF0 xor (it * 37)).toByte() }).toHex(),
        )
    }

    @Test
    fun xiaomiFramesRoundTrip() {
        val request = XiaomiFrames.encode(XiaomiFrames.PHONE_REQUEST, XiaomiFrames.Opcodes.GET_CONFIG, 9, hex("00 2F"))
        assertArrayEquals(hex("FE DC BA C4 F3 00 03 09 00 2F EF"), request)
        val response = XiaomiFrames.encode(XiaomiFrames.RESPONSE, XiaomiFrames.Opcodes.GET_CONFIG, 9, hex("00 03 2F 01"))
        assertArrayEquals(hex("FE DC BA 04 F3 00 06 00 09 00 03 2F 01 EF"), response)
        val packets = XiaomiFrames.Decoder().feed(request + response)
        assertEquals(2, packets.size)
        assertNull(packets[0].status)
        assertEquals(0, packets[1].status)
        assertEquals(9, packets[1].seq)
        assertArrayEquals(hex("00 03 2F 01"), packets[1].payload)
    }

    @Test
    fun xiaomiSessionAuthenticatesBothWaysThenSwitches() {
        var on = false
        var authenticated = false
        val budsChallenge = ByteArray(16) { (0x40 + it).toByte() }
        val decoder = XiaomiFrames.Decoder()
        val buds = FakeEarbuds { bytes ->
            decoder.feed(bytes).flatMap { p ->
                val ok = { payload: ByteArray -> XiaomiFrames.encode(XiaomiFrames.RESPONSE, p.opcode, p.seq, payload) }
                when {
                    p.opcode == XiaomiFrames.Opcodes.AUTH_CHALLENGE && p.isRequest ->
                        listOf(ok(byteArrayOf(1) + XiaomiAuth.respond(p.payload.copyOfRange(1, 17))))
                    p.opcode == XiaomiFrames.Opcodes.AUTH_CONFIRM && p.isRequest ->
                        listOf(ok(byteArrayOf(1)), XiaomiFrames.encode(0xC0, XiaomiFrames.Opcodes.AUTH_CHALLENGE, 0x30, byteArrayOf(1) + budsChallenge))
                    p.opcode == XiaomiFrames.Opcodes.AUTH_CHALLENGE -> {
                        // Our answer to their challenge.
                        authenticated = p.payload.copyOfRange(1, 17).contentEquals(XiaomiAuth.respond(budsChallenge))
                        listOf(XiaomiFrames.encode(0xC0, XiaomiFrames.Opcodes.AUTH_CONFIRM, 0x31, hex("01 00")))
                    }
                    p.opcode == XiaomiFrames.Opcodes.AUTH_CONFIRM -> emptyList()
                    !authenticated -> listOf(XiaomiFrames.encode(XiaomiFrames.RESPONSE, p.opcode, p.seq) .also { it[7] = 1 })
                    p.opcode == XiaomiFrames.Opcodes.GET_CONFIG -> listOf(ok(byteArrayOf(0, 3, 0x2F, if (on) 1 else 0)))
                    p.opcode == XiaomiFrames.Opcodes.SET_CONFIG -> {
                        on = p.payload[3].toInt() == 1
                        listOf(ok(ByteArray(0)))
                    }
                    else -> emptyList()
                }
            }
        }
        val session = XiaomiSession(buds) { it.fill(7) }
        assertEquals(DriverResult.Ok(wasOn = false), session.setLowLatency(true))
        assertTrue(authenticated)
        assertTrue(session.earbudsVerified)
        assertTrue(on)
    }

    // ---- Huawei / Honor ----

    @Test
    fun huaweiLowLatencyFrame() {
        val frame = HuaweiFrames.encode(HuaweiFrames.SERVICE_EARPHONES, HuaweiFrames.COMMAND_LOW_LATENCY, HuaweiFrames.tlv(1, hex("01")))
        assertArrayEquals(hex("5A 00 06 00 2B 6C 01 01 01"), frame.copyOfRange(0, 9))
        val crc = Crc16.xmodem(frame, 0, 9)
        assertEquals(crc shr 8, frame[9].toInt() and 0xFF)
        assertEquals(crc and 0xFF, frame[10].toInt() and 0xFF)
        assertArrayEquals(hex("81 00"), HuaweiFrames.varint(128))
        assertArrayEquals(hex("7F"), HuaweiFrames.varint(127))
    }

    @Test
    fun huaweiReadsTheErrorCode() {
        val ok = HuaweiFrames.encode(0x2B, 0x6C, HuaweiFrames.tlv(0x7F, hex("00 01 86 A0")))
        val refused = HuaweiFrames.encode(0x2B, 0x6C, HuaweiFrames.tlv(0x7F, hex("00 01 86 A3")))
        assertEquals(DriverResult.Ok(), HuaweiSession(FakeEarbuds { listOf(ok) }).setLowLatency(true))
        assertEquals(DriverResult.Rejected(100_003), HuaweiSession(FakeEarbuds { listOf(refused) }).setLowLatency(true))
    }

    // ---- EarFun (GAIA) ----

    @Test
    fun earFunGameModeFrames() {
        assertArrayEquals(hex("FF 04 00 01 00 0A 03 12 01"), GaiaFrames.encode(0x000A, 0x0312, hex("01")))
        var on = false
        val decoder = GaiaFrames.Decoder()
        val buds = FakeEarbuds { bytes ->
            decoder.feed(bytes).map { p ->
                when (p.command) {
                    0x0313 -> GaiaFrames.encode(0x000A, 0x8313, byteArrayOf(0, if (on) 1 else 0))
                    0x0312 -> {
                        on = p.payload[0].toInt() == 1
                        GaiaFrames.encode(0x000A, 0x8312, hex("00"))
                    }
                    else -> GaiaFrames.encode(0x000A, p.command or 0x8000, hex("01"))
                }
            }
        }
        assertEquals(DriverResult.Ok(wasOn = false), EarFunSession(buds).setGameMode(true))
        assertTrue(on)
    }
}
