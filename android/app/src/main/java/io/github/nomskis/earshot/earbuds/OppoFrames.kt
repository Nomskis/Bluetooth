package io.github.nomskis.earshot.earbuds

import java.io.ByteArrayOutputStream

/**
 * Wire format of the control channel shared by OPPO, OnePlus and realme
 * earbuds (the protocol behind HeyMelody and realme Link), as documented by
 * independent open-source reverse-engineering projects:
 *
 *     AA <varint len> 00 00 | <cmd u16 LE> <seq u8> <payload len u16 LE> <payload>
 *
 * where len counts the two zero bytes plus the inner packet. Replies use the
 * same frame with the command's high bit set, and their payload starts with a
 * status byte (0 = OK).
 */
object OppoFrames {
    const val START: Int = 0xAA

    data class Packet(val command: Int, val seq: Int, val payload: ByteArray) {
        val isReply: Boolean get() = command and 0x8000 != 0
        val requestCommand: Int get() = command and 0x7FFF
        /** First payload byte of a reply; 0 means the earbuds accepted the command. */
        val status: Int? get() = if (isReply && payload.isNotEmpty()) payload[0].toInt() and 0xFF else null

        override fun equals(other: Any?) =
            other is Packet && command == other.command && seq == other.seq && payload.contentEquals(other.payload)

        override fun hashCode() = 31 * (31 * command + seq) + payload.contentHashCode()
    }

    fun encode(command: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val inner = ByteArrayOutputStream().apply {
            write(command and 0xFF)
            write((command shr 8) and 0xFF)
            write(seq and 0xFF)
            write(payload.size and 0xFF)
            write((payload.size shr 8) and 0xFF)
            write(payload)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(START)
            write(varint(inner.size + 2))
            write(0)
            write(0)
            write(inner)
        }.toByteArray()
    }

    /** Unsigned LEB128, 7 bits per byte. */
    fun varint(value: Int): ByteArray {
        require(value >= 0)
        val out = ByteArrayOutputStream()
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
        return out.toByteArray()
    }

    /**
     * Pulls complete packets out of a byte stream. Feed it whatever the
     * socket returns; partial frames wait for more bytes, garbage is skipped.
     */
    class Decoder : FrameDecoder<Packet> {
        private val buffer = ByteArrayOutputStream()

        fun feed(bytes: ByteArray): List<Packet> = feed(bytes, bytes.size)

        override fun feed(bytes: ByteArray, count: Int): List<Packet> {
            buffer.write(bytes, 0, count)
            val data = buffer.toByteArray()
            val packets = mutableListOf<Packet>()
            var i = 0
            while (i < data.size) {
                if (data[i].toInt() and 0xFF != START) {
                    i++
                    continue
                }
                // Length varint.
                var length = 0
                var shift = 0
                var j = i + 1
                var complete = false
                while (j < data.size && shift <= 28) {
                    val b = data[j].toInt() and 0xFF
                    length = length or ((b and 0x7F) shl shift)
                    j++
                    if (b and 0x80 == 0) {
                        complete = true
                        break
                    }
                    shift += 7
                }
                if (!complete) {
                    if (j - i > 5) {
                        i++ // not a real length; resync on the next start byte
                        continue
                    }
                    break // need more bytes
                }
                if (data.size - j < length) break // frame not complete yet
                val body = data.copyOfRange(j, j + length)
                i = j + length
                if (body.size < 2 + 5) continue // too short to be a packet
                val inner = body.copyOfRange(2, body.size)
                val command = (inner[0].toInt() and 0xFF) or ((inner[1].toInt() and 0xFF) shl 8)
                val seq = inner[2].toInt() and 0xFF
                val payloadLength = (inner[3].toInt() and 0xFF) or ((inner[4].toInt() and 0xFF) shl 8)
                val payload = inner.copyOfRange(5, minOf(inner.size, 5 + payloadLength))
                packets += Packet(command, seq, payload)
            }
            val rest = data.copyOfRange(minOf(i, data.size), data.size)
            buffer.reset()
            buffer.write(rest)
            return packets
        }
    }

    object Commands {
        /** Session hello; the reply is a bitmap of the commands the firmware accepts. */
        const val HELLO = 0x0100
        const val BATTERY = 0x0106
        /** Read feature switches: payload [count, ids...]; reply [status, count, (id, value)...]. */
        const val FEATURE_STATES = 0x010D
        /** Feature switch: payload [feature id, on/off]. */
        const val FEATURE_SWITCH = 0x0403
        /** Game sound type; its presence moves game mode to [FEATURE_GAME_MODE_ALT]. */
        const val GAME_SOUND = 0x0423
        const val FEATURE_GAME_MODE = 0x06
        const val FEATURE_GAME_MODE_ALT = 0x28
    }

    /**
     * Bits of the hello reply we care about (LSB first after the status byte).
     * Bit 7 allows the feature switch; bit 49 means the earbuds have
     * "game sound", and those keep game mode under [Commands.FEATURE_GAME_MODE_ALT].
     */
    data class Capabilities(val featureSwitch: Boolean, val gameSound: Boolean) {
        companion object {
            const val BIT_FEATURE_SWITCH = 7
            const val BIT_GAME_SOUND = 49

            fun parse(helloReply: ByteArray): Capabilities? {
                if (helloReply.size < 2 || helloReply[0].toInt() != 0) return null
                fun bit(n: Int): Boolean {
                    val index = 1 + n / 8
                    return index < helloReply.size && (helloReply[index].toInt() shr (n % 8)) and 1 == 1
                }
                return Capabilities(featureSwitch = bit(BIT_FEATURE_SWITCH), gameSound = bit(BIT_GAME_SOUND))
            }
        }
    }

    /** Feature switch values from a [Commands.FEATURE_STATES] reply; only ids the firmware has. */
    fun parseFeatureStates(reply: ByteArray): Map<Int, Int>? {
        if (reply.size < 2 || reply[0].toInt() != 0) return null
        val count = reply[1].toInt() and 0xFF
        val out = LinkedHashMap<Int, Int>()
        var i = 2
        repeat(count) {
            if (i + 1 >= reply.size) return out
            out[reply[i].toInt() and 0xFF] = reply[i + 1].toInt() and 0xFF
            i += 2
        }
        return out
    }

    /**
     * Which switch holds game mode. The earbuds' own list wins; when it
     * lists both or neither, the capability bitmap decides.
     */
    fun gameModeId(capabilities: Capabilities?, states: Map<Int, Int>?): Int {
        val main = states?.containsKey(Commands.FEATURE_GAME_MODE) == true
        val alt = states?.containsKey(Commands.FEATURE_GAME_MODE_ALT) == true
        return when {
            main && !alt -> Commands.FEATURE_GAME_MODE
            alt && !main -> Commands.FEATURE_GAME_MODE_ALT
            capabilities?.gameSound == true -> Commands.FEATURE_GAME_MODE_ALT
            else -> Commands.FEATURE_GAME_MODE
        }
    }
}
