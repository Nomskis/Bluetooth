package io.github.nomskis.earshot.earbuds

/** The two CRC-16 variants earbud protocols use. */
object Crc16 {
    /** CRC-16/MODBUS: reflected polynomial 0xA001, start 0xFFFF. Check value 0x4B37. */
    fun modbus(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /** CRC-16/XMODEM: polynomial 0x1021, start 0. Check value 0x31C3. */
    fun xmodem(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
        }
        return crc and 0xFFFF
    }
}
