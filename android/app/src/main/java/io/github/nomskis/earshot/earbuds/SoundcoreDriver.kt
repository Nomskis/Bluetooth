package io.github.nomskis.earshot.earbuds

import android.bluetooth.BluetoothDevice
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Wire format of Soundcore (Anker) earbuds, as documented by OpenSCQ30:
 *
 *     08 EE 00 00 00 | command (2 bytes) | total length u16 LE | body | checksum
 *
 * from the phone, `09 FF 00 00 01` as the prefix from the earbuds. The
 * length counts the whole frame; the checksum is the low byte of the sum of
 * every byte before it. The earbuds answer a command with a frame carrying
 * the same command.
 */
object SoundcoreFrames {
    data class Packet(val command: Int, val body: ByteArray) {
        override fun equals(other: Any?) = other is Packet && command == other.command && body.contentEquals(other.body)
        override fun hashCode() = 31 * command + body.contentHashCode()
    }

    private val OUTBOUND = byteArrayOf(0x08, 0xEE.toByte(), 0, 0, 0)
    private val INBOUND = byteArrayOf(0x09, 0xFF.toByte(), 0, 0, 1)
    private const val HEADER = 5 + 2 + 2

    /** Gaming mode on/off: body [1 / 0]. */
    const val SET_GAMING_MODE = 0x0187

    fun encode(command: Int, body: ByteArray, prefix: ByteArray = OUTBOUND): ByteArray {
        val length = HEADER + body.size + 1
        val frame = ByteArrayOutputStream().apply {
            write(prefix)
            write((command shr 8) and 0xFF); write(command and 0xFF)
            write(length and 0xFF); write((length shr 8) and 0xFF)
            write(body)
        }.toByteArray()
        return frame + byteArrayOf(checksum(frame, frame.size).toByte())
    }

    /** What the earbuds would send; for tests. */
    fun encodeInbound(command: Int, body: ByteArray): ByteArray = encode(command, body, INBOUND)

    fun checksum(data: ByteArray, count: Int): Int {
        var sum = 0
        for (i in 0 until count) sum += data[i].toInt() and 0xFF
        return sum and 0xFF
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
                if (data.size - i < INBOUND.size) break
                if ((0 until INBOUND.size).any { data[i + it] != INBOUND[it] }) {
                    i++
                    continue
                }
                if (data.size - i < HEADER) break
                val length = (data[i + 7].toInt() and 0xFF) or ((data[i + 8].toInt() and 0xFF) shl 8)
                if (length < HEADER + 1) {
                    i++
                    continue
                }
                if (data.size - i < length) break
                val frame = data.copyOfRange(i, i + length)
                if (checksum(frame, length - 1) != (frame[length - 1].toInt() and 0xFF)) {
                    i++
                    continue
                }
                val command = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
                out += Packet(command, frame.copyOfRange(HEADER, length - 1))
                i += length
            }
            buffer.reset()
            buffer.write(data, i, data.size - i)
            return out
        }
    }
}

/** Gaming mode on Soundcore earbuds that have it (Liberty 4 family, Space A40 and others). */
class SoundcoreDriver : RfcommDriver() {
    override val family = "Soundcore"
    override val experimental = true

    override val serviceUuids: List<UUID> = listOf(SPP_UUID)

    override fun recognizes(device: BluetoothDevice): Boolean {
        val uuids = advertised(device)
        return uuids.any(::isVendorUuid) || (SPP_UUID in uuids && "soundcore" in nameOf(device))
    }

    override suspend fun setLowLatency(device: BluetoothDevice, enabled: Boolean): DriverResult {
        // Soundcore's own service when the earbuds advertise one, else the serial port.
        val vendor = advertised(device).firstOrNull(::isVendorUuid)
        return if (vendor != null) SoundcoreVendorChannel(vendor).setLowLatency(device, enabled) else super.setLowLatency(device, enabled)
    }

    override fun session(link: ControlLink, enabled: Boolean): DriverResult = SoundcoreSession(link).setGamingMode(enabled)

    /** Same driver, pinned to the vendor service UUID the earbuds advertise. */
    private class SoundcoreVendorChannel(uuid: UUID) : RfcommDriver() {
        override val family = "Soundcore"
        override val serviceUuids = listOf(uuid, SPP_UUID)
        override fun recognizes(device: BluetoothDevice) = true
        override fun session(link: ControlLink, enabled: Boolean) = SoundcoreSession(link).setGamingMode(enabled)
    }

    companion object {
        private val VENDOR_UUID: UUID = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e700000")

        /** Soundcore's vendor services share the UUID's first 108 bits. */
        fun isVendorUuid(uuid: UUID): Boolean =
            uuid.mostSignificantBits == VENDOR_UUID.mostSignificantBits &&
                (uuid.leastSignificantBits and 0xFFFFF.inv().toLong()) == (VENDOR_UUID.leastSignificantBits and 0xFFFFF.inv().toLong())
    }
}

class SoundcoreSession(private val link: ControlLink) {
    private val reader = PacketReader(link, SoundcoreFrames.Decoder())

    /** The state isn't readable model-independently, so success is the earbuds' answer to the switch. */
    fun setGamingMode(enabled: Boolean): DriverResult {
        link.write(SoundcoreFrames.encode(SoundcoreFrames.SET_GAMING_MODE, byteArrayOf(if (enabled) 1 else 0)))
        reader.await(REPLY_TIMEOUT_MS) { it.command == SoundcoreFrames.SET_GAMING_MODE } ?: return DriverResult.NoAnswer
        return DriverResult.Ok()
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 2_000L
    }
}
