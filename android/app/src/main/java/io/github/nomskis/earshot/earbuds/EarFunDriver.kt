package io.github.nomskis.earshot.earbuds

import android.bluetooth.BluetoothDevice
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Qualcomm GAIA framing, as EarFun uses it (documented by Gadgetbridge):
 *
 *     FF | version | flags | payload len | vendor u16 BE | command u16 BE | payload [| checksum]
 *
 * Flags bit 0 means a one-byte checksum follows. Acks set the command's top
 * bit and start their payload with a status byte (0 = success).
 */
object GaiaFrames {
    data class Packet(val vendor: Int, val command: Int, val payload: ByteArray) {
        val isAck: Boolean get() = command and 0x8000 != 0
        val requestCommand: Int get() = command and 0x7FFF
        val status: Int? get() = if (isAck && payload.isNotEmpty()) payload[0].toInt() and 0xFF else null

        override fun equals(other: Any?) = other is Packet && vendor == other.vendor && command == other.command && payload.contentEquals(other.payload)
        override fun hashCode() = (31 * vendor + command) * 31 + payload.contentHashCode()
    }

    const val VERSION = 0x04

    fun encode(vendor: Int, command: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(
            0xFF.toByte(), VERSION.toByte(), 0, payload.size.toByte(),
            (vendor shr 8).toByte(), vendor.toByte(), (command shr 8).toByte(), command.toByte(),
        ) + payload

    class Decoder : FrameDecoder<Packet> {
        private val buffer = ByteArrayOutputStream()

        fun feed(bytes: ByteArray): List<Packet> = feed(bytes, bytes.size)

        override fun feed(bytes: ByteArray, count: Int): List<Packet> {
            buffer.write(bytes, 0, count)
            val data = buffer.toByteArray()
            val out = mutableListOf<Packet>()
            var i = 0
            while (i < data.size) {
                if (data[i].toInt() and 0xFF != 0xFF) {
                    i++
                    continue
                }
                if (data.size - i < 8) break
                val version = data[i + 1].toInt() and 0xFF
                if (version !in 1..4) {
                    i++
                    continue
                }
                val hasChecksum = data[i + 2].toInt() and 1 != 0
                val length = data[i + 3].toInt() and 0xFF
                val total = 8 + length + if (hasChecksum) 1 else 0
                if (data.size - i < total) break
                val vendor = ((data[i + 4].toInt() and 0xFF) shl 8) or (data[i + 5].toInt() and 0xFF)
                val command = ((data[i + 6].toInt() and 0xFF) shl 8) or (data[i + 7].toInt() and 0xFF)
                out += Packet(vendor, command, data.copyOfRange(i + 8, i + 8 + length))
                i += total
            }
            buffer.reset()
            buffer.write(data, i, data.size - i)
            return out
        }
    }
}

/** Game mode on EarFun earbuds (Air Pro 4, Air S, Free Pro 3 and kin). */
class EarFunDriver : RfcommDriver() {
    override val family = "EarFun"
    override val experimental = true

    override val serviceUuids: List<UUID> = listOf(SPP_UUID, GAIA_UUID)

    override fun recognizes(device: BluetoothDevice): Boolean {
        val uuids = advertised(device)
        return (SPP_UUID in uuids || GAIA_UUID in uuids) && "earfun" in nameOf(device)
    }

    override fun session(link: ControlLink, enabled: Boolean): DriverResult = EarFunSession(link).setGameMode(enabled)

    private companion object {
        val GAIA_UUID: UUID = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5")
    }
}

class EarFunSession(private val link: ControlLink) {
    private val reader = PacketReader(link, GaiaFrames.Decoder())

    private fun request(command: Int, payload: ByteArray = ByteArray(0)): GaiaFrames.Packet? {
        link.write(GaiaFrames.encode(VENDOR, command, payload))
        return reader.await(REPLY_TIMEOUT_MS) { it.isAck && it.requestCommand == command }
    }

    /** The reply is [status, value]. */
    private fun readState(): Boolean? {
        val reply = request(GET_GAME_MODE) ?: return null
        if (reply.status != 0 || reply.payload.size < 2) return null
        return reply.payload[1].toInt() == 1
    }

    fun setGameMode(enabled: Boolean): DriverResult {
        val wasOn = readState()
        if (wasOn == enabled) return DriverResult.Ok(wasOn)
        val ack = request(SET_GAME_MODE, byteArrayOf(if (enabled) 1 else 0)) ?: return DriverResult.NoAnswer
        val status = ack.status ?: 0
        if (status != 0) return DriverResult.Rejected(status)
        val after = readState()
        return if (after == null || after == enabled) DriverResult.Ok(wasOn) else DriverResult.Rejected(OppoSession.NOT_APPLIED)
    }

    private companion object {
        const val VENDOR = 0x000A
        const val SET_GAME_MODE = 0x0312
        const val GET_GAME_MODE = 0x0313
        const val REPLY_TIMEOUT_MS = 1_500L
    }
}
