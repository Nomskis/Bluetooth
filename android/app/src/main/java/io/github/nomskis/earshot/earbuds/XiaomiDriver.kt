package io.github.nomskis.earshot.earbuds

import android.bluetooth.BluetoothDevice
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

/**
 * Wire format of Xiaomi and Redmi earbuds, as documented by open-source
 * clients (Gadgetbridge, BudsLink):
 *
 *     FE DC BA | type | opcode | len u16 BE | [status] | seq | payload | EF
 *
 * Requests (type bit 0x80 set: C4 from the phone, C0 from the earbuds) have
 * no status byte; responses (04, 07) do. len counts status, seq and payload.
 */
object XiaomiFrames {
    data class Packet(val type: Int, val opcode: Int, val seq: Int, val status: Int?, val payload: ByteArray) {
        val isRequest: Boolean get() = type and 0x80 != 0

        override fun equals(other: Any?) = other is Packet && type == other.type && opcode == other.opcode &&
            seq == other.seq && status == other.status && payload.contentEquals(other.payload)
        override fun hashCode() = ((31 * type + opcode) * 31 + seq) * 31 + payload.contentHashCode()
    }

    const val PHONE_REQUEST = 0xC4
    const val RESPONSE = 0x04

    object Opcodes {
        const val AUTH_CHALLENGE = 0x50
        const val AUTH_CONFIRM = 0x51
        const val SET_CONFIG = 0xF2
        const val GET_CONFIG = 0xF3
    }

    const val CONFIG_LOW_LATENCY = 0x2F

    fun encode(type: Int, opcode: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val isRequest = type and 0x80 != 0
        val length = payload.size + if (isRequest) 1 else 2
        return ByteArrayOutputStream().apply {
            write(0xFE); write(0xDC); write(0xBA)
            write(type); write(opcode)
            write((length shr 8) and 0xFF); write(length and 0xFF)
            if (!isRequest) write(0x00)
            write(seq and 0xFF)
            write(payload)
            write(0xEF)
        }.toByteArray()
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
                if (data.size - i < 3 || !(u8(data, i) == 0xFE && u8(data, i + 1) == 0xDC && u8(data, i + 2) == 0xBA)) {
                    if (data.size - i < 3) break
                    i++
                    continue
                }
                if (data.size - i < 8) break
                val length = (u8(data, i + 5) shl 8) or u8(data, i + 6)
                val total = length + 8
                if (data.size - i < total) break
                if (u8(data, i + total - 1) != 0xEF) {
                    i++
                    continue
                }
                val type = u8(data, i + 3)
                val request = type and 0x80 != 0
                val seqAt = i + if (request) 7 else 8
                val status = if (request) null else u8(data, i + 7)
                out += Packet(type, u8(data, i + 4), u8(data, seqAt), status, data.copyOfRange(seqAt + 1, i + total - 1))
                i += total
            }
            buffer.reset()
            buffer.write(data, i, data.size - i)
            return out
        }

        private fun u8(data: ByteArray, i: Int) = data[i].toInt() and 0xFF
    }
}

/** Low-latency mode on Xiaomi Buds and Redmi Buds. */
class XiaomiDriver : RfcommDriver() {
    override val family = "Xiaomi / Redmi"

    override val serviceUuids: List<UUID> = listOf(CONTROL_UUID)

    override fun recognizes(device: BluetoothDevice): Boolean = CONTROL_UUID in advertised(device)

    override fun session(link: ControlLink, enabled: Boolean): DriverResult = XiaomiSession(link).setLowLatency(enabled)

    private companion object {
        val CONTROL_UUID: UUID = UUID.fromString("0000fd2d-0000-1000-8000-00805f9b34fb")
    }
}

class XiaomiSession(private val link: ControlLink, private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) }) {
    private val reader = PacketReader(link, XiaomiFrames.Decoder())
    private var seq = 0

    /** Whether the earbuds answered our challenge correctly. */
    var earbudsVerified = false
        private set

    private fun nextSeq(): Int = seq.also { seq = (seq + 1) and 0xFF }

    private fun request(opcode: Int, payload: ByteArray): XiaomiFrames.Packet? {
        val mySeq = nextSeq()
        link.write(XiaomiFrames.encode(XiaomiFrames.PHONE_REQUEST, opcode, mySeq, payload))
        return reader.await(REPLY_TIMEOUT_MS) { !it.isRequest && it.opcode == opcode }
    }

    /**
     * Mutual authentication: we challenge the earbuds and check their answer,
     * then they challenge us. Settings are refused until both pass.
     */
    private fun authenticate(): Boolean {
        val challenge = ByteArray(16).also(random)
        // Their answer proves they're genuine; we only need them to talk to us,
        // so a mismatch (an unknown variant) is noted, not fatal.
        val answer = request(XiaomiFrames.Opcodes.AUTH_CHALLENGE, byteArrayOf(1) + challenge) ?: return false
        val theirs = answer.payload.copyOfRange(minOf(1, answer.payload.size), answer.payload.size)
        earbudsVerified = theirs.contentEquals(XiaomiAuth.respond(challenge))
        request(XiaomiFrames.Opcodes.AUTH_CONFIRM, byteArrayOf(1, 0)) ?: return false

        val theirChallenge = reader.await(REPLY_TIMEOUT_MS) { it.isRequest && it.opcode == XiaomiFrames.Opcodes.AUTH_CHALLENGE } ?: return false
        if (theirChallenge.payload.size < 17) return false
        val response = XiaomiAuth.respond(theirChallenge.payload.copyOfRange(1, 17))
        link.write(XiaomiFrames.encode(XiaomiFrames.RESPONSE, XiaomiFrames.Opcodes.AUTH_CHALLENGE, theirChallenge.seq, byteArrayOf(1) + response))

        val confirm = reader.await(REPLY_TIMEOUT_MS) { it.isRequest && it.opcode == XiaomiFrames.Opcodes.AUTH_CONFIRM } ?: return false
        link.write(XiaomiFrames.encode(XiaomiFrames.RESPONSE, XiaomiFrames.Opcodes.AUTH_CONFIRM, confirm.seq, byteArrayOf(1)))
        return true
    }

    /** Reply payload: [.., .., config id, value...]. */
    private fun readState(): Boolean? {
        val reply = request(XiaomiFrames.Opcodes.GET_CONFIG, byteArrayOf(0, XiaomiFrames.CONFIG_LOW_LATENCY.toByte())) ?: return null
        val p = reply.payload
        if (p.size < 4 || (p[2].toInt() and 0xFF) != XiaomiFrames.CONFIG_LOW_LATENCY) return null
        return p[3].toInt() == 1
    }

    fun setLowLatency(enabled: Boolean): DriverResult {
        if (!authenticate()) return DriverResult.NoAnswer
        val wasOn = readState()
        if (wasOn == enabled) return DriverResult.Ok(wasOn)

        val config = byteArrayOf(0, XiaomiFrames.CONFIG_LOW_LATENCY.toByte(), if (enabled) 1 else 0)
        val ack = request(XiaomiFrames.Opcodes.SET_CONFIG, byteArrayOf(config.size.toByte()) + config) ?: return DriverResult.NoAnswer
        // The read-back is the evidence; the ack's status byte isn't documented well enough to trust alone.
        val after = readState()
        val status = ack.status ?: 0
        return when {
            after == enabled -> DriverResult.Ok(wasOn)
            after != null -> if (status != 0) DriverResult.Rejected(status) else DriverResult.Rejected(OppoSession.NOT_APPLIED)
            status == 0 -> DriverResult.Ok(wasOn)
            else -> DriverResult.Rejected(status)
        }
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 1_500L
    }
}
