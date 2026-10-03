package io.github.nomskis.earshot.earbuds

import android.bluetooth.BluetoothDevice
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Wire format of Nothing and CMF earbuds and headphones, as documented by
 * open-source clients (BudsLink, Gadgetbridge):
 *
 *     55 60 01 | type u16 LE | payload len u16 LE | seq | payload | CRC-16/MODBUS LE
 *
 * Bit 0x20 of the second byte says a CRC follows. Replies come back with the
 * request's type minus its top bit (GET C041 -> 4041, SET F040 -> 7040).
 */
object NothingFrames {
    data class Packet(val type: Int, val seq: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Packet && type == other.type && seq == other.seq && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * (31 * type + seq) + payload.contentHashCode()
    }

    private const val MAGIC = 0x55
    private const val HEADER = 8

    fun encode(type: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val frame = ByteArrayOutputStream().apply {
            write(MAGIC); write(0x60); write(0x01)
            write(type and 0xFF); write((type shr 8) and 0xFF)
            write(payload.size and 0xFF); write((payload.size shr 8) and 0xFF)
            write(seq and 0xFF)
            write(payload)
        }.toByteArray()
        val crc = Crc16.modbus(frame)
        return frame + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    class Decoder : FrameDecoder<Packet> {
        private val buffer = ByteArrayOutputStream()

        fun feed(bytes: ByteArray): List<Packet> = feed(bytes, bytes.size)

        override fun feed(bytes: ByteArray, count: Int): List<Packet> {
            buffer.write(bytes, 0, count)
            val data = buffer.toByteArray()
            val out = mutableListOf<Packet>()
            var i = 0
            while (i < data.size) {
                if (data[i].toInt() and 0xFF != MAGIC) {
                    i++
                    continue
                }
                if (data.size - i < HEADER) break
                val hasCrc = data[i + 1].toInt() and 0x20 != 0
                val length = (data[i + 5].toInt() and 0xFF) or ((data[i + 6].toInt() and 0xFF) shl 8)
                val total = HEADER + length + if (hasCrc) 2 else 0
                if (data.size - i < total) break
                if (hasCrc) {
                    val expected = Crc16.modbus(data, i, i + HEADER + length)
                    val actual = (data[i + HEADER + length].toInt() and 0xFF) or ((data[i + HEADER + length + 1].toInt() and 0xFF) shl 8)
                    if (expected != actual) {
                        i++ // not a real frame start; resync
                        continue
                    }
                }
                val type = (data[i + 3].toInt() and 0xFF) or ((data[i + 4].toInt() and 0xFF) shl 8)
                out += Packet(type, data[i + 7].toInt() and 0xFF, data.copyOfRange(i + HEADER, i + HEADER + length))
                i += total
            }
            buffer.reset()
            buffer.write(data, i, data.size - i)
            return out
        }
    }

    object Types {
        const val LATENCY_GET = 0xC041
        const val LATENCY_REPLY = 0x4041
        const val LATENCY_SET = 0xF040
        const val LATENCY_NOTIFY = 0x7040
        /** Protocol version query; harmless, used to wake older firmware. */
        const val PROTOCOL_GET = 0xC001
    }
}

/** Low-lag mode on Nothing Ear / Ear (a) / Ear (open) / Headphone and CMF Buds. */
class NothingDriver : RfcommDriver() {
    override val family = "Nothing / CMF"

    override val serviceUuids: List<UUID> = listOf(CONTROL_UUID)

    override fun recognizes(device: BluetoothDevice): Boolean = CONTROL_UUID in advertised(device)

    override fun session(link: ControlLink, enabled: Boolean): DriverResult = NothingSession(link).setLowLatency(enabled)

    private companion object {
        val CONTROL_UUID: UUID = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")
    }
}

class NothingSession(private val link: ControlLink) {
    private val reader = PacketReader(link, NothingFrames.Decoder())
    private var seq = 0

    private fun send(type: Int, payload: ByteArray = ByteArray(0)): Int {
        seq = if (seq >= 250) 1 else seq + 1
        link.write(NothingFrames.encode(type, seq, payload))
        return seq
    }

    private fun readState(timeoutMs: Long): Boolean? {
        send(NothingFrames.Types.LATENCY_GET)
        val reply = reader.await(timeoutMs) {
            (it.type == NothingFrames.Types.LATENCY_REPLY || it.type == NothingFrames.Types.LATENCY_NOTIFY) && it.payload.isNotEmpty()
        } ?: return null
        return reply.payload[0].toInt() == 1
    }

    fun setLowLatency(enabled: Boolean): DriverResult {
        var wasOn = readState(REPLY_TIMEOUT_MS)
        if (wasOn == null) {
            // Some firmware wants a protocol query first.
            send(NothingFrames.Types.PROTOCOL_GET)
            reader.await(REPLY_TIMEOUT_MS) { true }
            wasOn = readState(REPLY_TIMEOUT_MS)
        }
        if (wasOn == enabled) return DriverResult.Ok(wasOn)

        // 1 = on, 2 = off.
        send(NothingFrames.Types.LATENCY_SET, byteArrayOf(if (enabled) 1 else 2, 0))
        val ack = reader.await(REPLY_TIMEOUT_MS) { it.type == NothingFrames.Types.LATENCY_NOTIFY || it.type == NothingFrames.Types.LATENCY_REPLY }
        val after = readState(REPLY_TIMEOUT_MS)
        return when {
            after == enabled -> DriverResult.Ok(wasOn)
            after != null -> DriverResult.Rejected(OppoSession.NOT_APPLIED)
            ack != null -> DriverResult.Ok(wasOn)
            else -> DriverResult.NoAnswer
        }
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 1_500L
    }
}
