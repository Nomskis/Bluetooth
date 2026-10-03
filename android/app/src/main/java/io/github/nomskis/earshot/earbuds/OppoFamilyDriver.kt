package io.github.nomskis.earshot.earbuds

import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * Game mode for OPPO, OnePlus and realme earbuds (one shared protocol, the
 * one behind HeyMelody and realme Link). The exchange, as the vendor app does it:
 *
 *  1. hello, whose reply says which commands the firmware accepts;
 *  2. read the game-mode switches, to learn which id this model uses
 *     (`06` on most, `28` on models with "game sound") and its current value;
 *  3. write the switch, then read it back, because an OK ack alone doesn't
 *     prove the write took.
 *
 * See [OppoFrames] for the wire format and its sources.
 */
class OppoFamilyDriver : RfcommDriver() {
    override val family = "OPPO / OnePlus / realme"

    override val serviceUuids: List<UUID> = listOf(CONTROL_UUID, CONTROL_UUID_ALT, SPP_UUID)

    override fun recognizes(device: BluetoothDevice): Boolean {
        val uuids = advertised(device)
        if (CONTROL_UUID in uuids) return true
        val name = nameOf(device)
        return (CONTROL_UUID_ALT in uuids || SPP_UUID in uuids) && BRANDS.any { it in name }
    }

    override fun session(link: ControlLink, enabled: Boolean): DriverResult = OppoSession(link).setGameMode(enabled)

    private companion object {
        /** The service HeyMelody / realme Link connect to. */
        val CONTROL_UUID: UUID = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5")
        /** Second choice on some models. */
        val CONTROL_UUID_ALT: UUID = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5")
        val BRANDS = listOf("realme", "oppo", "oneplus", "enco", "dizo")
    }
}

/** The protocol exchange itself, separate from Bluetooth so it can be tested. */
class OppoSession(private val link: ControlLink) {
    private val reader = PacketReader(link, OppoFrames.Decoder())
    private var seq = 1

    private fun request(command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = REPLY_TIMEOUT_MS): OppoFrames.Packet? {
        val mySeq = seq
        seq = if (seq >= 0xFE) 1 else seq + 1
        link.write(OppoFrames.encode(command, mySeq, payload))
        return reader.await(timeoutMs) { it.isReply && it.requestCommand == command }
    }

    private fun readSwitches(ids: List<Int>): Map<Int, Int>? {
        val payload = byteArrayOf(ids.size.toByte()) + ByteArray(ids.size) { ids[it].toByte() }
        return request(OppoFrames.Commands.FEATURE_STATES, payload)?.let { OppoFrames.parseFeatureStates(it.payload) }
    }

    fun setGameMode(enabled: Boolean): DriverResult {
        val hello = request(OppoFrames.Commands.HELLO, timeoutMs = HELLO_TIMEOUT_MS)
        val capabilities = hello?.let { OppoFrames.Capabilities.parse(it.payload) }
        if (capabilities != null && !capabilities.featureSwitch) return DriverResult.Unsupported

        val states = readSwitches(listOf(OppoFrames.Commands.FEATURE_GAME_MODE, OppoFrames.Commands.FEATURE_GAME_MODE_ALT))
        // An answer that lists neither id means this model has no game mode.
        if (states != null && states.isNotEmpty() &&
            OppoFrames.Commands.FEATURE_GAME_MODE !in states && OppoFrames.Commands.FEATURE_GAME_MODE_ALT !in states
        ) {
            return DriverResult.Unsupported
        }
        val id = OppoFrames.gameModeId(capabilities, states)
        val wasOn = states?.get(id)?.let { it == 1 }
        if (wasOn == enabled) return DriverResult.Ok(wasOn)

        val ack = request(OppoFrames.Commands.FEATURE_SWITCH, byteArrayOf(id.toByte(), if (enabled) 1 else 0))
            ?: return DriverResult.NoAnswer
        val status = ack.status
        if (status != null && status != 0) return DriverResult.Rejected(status)

        // Read back; firmware can ack a switch it ignores.
        val after = readSwitches(listOf(id))?.get(id) ?: return DriverResult.Ok(wasOn)
        return if ((after == 1) == enabled) DriverResult.Ok(wasOn) else DriverResult.Rejected(NOT_APPLIED)
    }

    companion object {
        const val HELLO_TIMEOUT_MS = 1_000L
        const val REPLY_TIMEOUT_MS = 1_500L
        /** Our code for "acked, but the read-back shows no change". */
        const val NOT_APPLIED = -1
    }
}
