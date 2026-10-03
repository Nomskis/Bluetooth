package io.github.nomskis.earshot.earbuds

import android.bluetooth.BluetoothDevice
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Wire format of Huawei / Honor earphones (unencrypted on earbuds), as
 * documented by Gadgetbridge:
 *
 *     5A | len u16 BE | 00 | service | command | TLVs | CRC-16/XMODEM BE
 *
 * len counts the 00, service, command and TLVs. A TLV is tag, length as a
 * big-endian 7-bit varint, value. A reply's tag 7F carries an error code;
 * 100000 means success.
 */
object HuaweiFrames {
    data class Packet(val service: Int, val command: Int, val tlv: Map<Int, ByteArray>) {
        val errorCode: Int? get() = tlv[0x7F]?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    }

    const val SERVICE_EARPHONES = 0x2B
    const val COMMAND_LOW_LATENCY = 0x6C
    const val SUCCESS = 100_000

    fun varint(value: Int): ByteArray {
        require(value >= 0)
        val groups = mutableListOf<Int>()
        var v = value
        do {
            groups += v and 0x7F
            v = v ushr 7
        } while (v != 0)
        groups.reverse()
        return ByteArray(groups.size) { i -> (groups[i] or if (i < groups.size - 1) 0x80 else 0).toByte() }
    }

    fun tlv(tag: Int, value: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + varint(value.size) + value

    fun encode(service: Int, command: Int, tlvs: ByteArray): ByteArray {
        val body = byteArrayOf(0, service.toByte(), command.toByte()) + tlvs
        val head = byteArrayOf(0x5A, (body.size shr 8).toByte(), body.size.toByte()) + body
        val crc = Crc16.xmodem(head)
        return head + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    fun parseTlvs(data: ByteArray, from: Int, to: Int): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var i = from
        while (i < to) {
            val tag = data[i].toInt() and 0xFF
            i++
            var length = 0
            while (i < to) {
                val b = data[i].toInt() and 0xFF
                i++
                length = (length shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            if (i + length > to) break
            out[tag] = data.copyOfRange(i, i + length)
            i += length
        }
        return out
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
                if (data[i].toInt() != 0x5A) {
                    i++
                    continue
                }
                if (data.size - i < 6) break
                val length = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
                val total = 3 + length + 2
                if (length < 3) {
                    i++
                    continue
                }
                if (data.size - i < total) break
                val crc = ((data[i + total - 2].toInt() and 0xFF) shl 8) or (data[i + total - 1].toInt() and 0xFF)
                if (crc != Crc16.xmodem(data, i, i + total - 2)) {
                    i++
                    continue
                }
                val service = data[i + 4].toInt() and 0xFF
                val command = data[i + 5].toInt() and 0xFF
                out += Packet(service, command, parseTlvs(data, i + 6, i + total - 2))
                i += total
            }
            buffer.reset()
            buffer.write(data, i, data.size - i)
            return out
        }
    }
}

/** Low-latency mode on Huawei FreeBuds / FreeClip and Honor earbuds. */
class HuaweiDriver : RfcommDriver() {
    override val family = "Huawei / Honor"
    override val experimental = true

    override val serviceUuids: List<UUID> = listOf(SPP_UUID)

    override fun recognizes(device: BluetoothDevice): Boolean {
        val name = nameOf(device)
        return SPP_UUID in advertised(device) && NAMES.any { it in name }
    }

    override fun session(link: ControlLink, enabled: Boolean): DriverResult = HuaweiSession(link).setLowLatency(enabled)

    private companion object {
        val NAMES = listOf("freebuds", "freeclip", "freelace", "huawei", "honor earbuds", "honor choice")
    }
}

class HuaweiSession(private val link: ControlLink) {
    private val reader = PacketReader(link, HuaweiFrames.Decoder())

    fun setLowLatency(enabled: Boolean): DriverResult {
        val tlv = HuaweiFrames.tlv(0x01, byteArrayOf(if (enabled) 1 else 0))
        link.write(HuaweiFrames.encode(HuaweiFrames.SERVICE_EARPHONES, HuaweiFrames.COMMAND_LOW_LATENCY, tlv))
        val reply = reader.await(REPLY_TIMEOUT_MS) {
            it.service == HuaweiFrames.SERVICE_EARPHONES && it.command == HuaweiFrames.COMMAND_LOW_LATENCY
        } ?: return DriverResult.NoAnswer
        val error = reply.errorCode
        return if (error == null || error == HuaweiFrames.SUCCESS) DriverResult.Ok() else DriverResult.Rejected(error)
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 2_000L
    }
}
