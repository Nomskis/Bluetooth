package io.github.nomskis.earshot.earbuds

import android.bluetooth.BluetoothDevice

sealed interface DriverResult {
    /**
     * The earbuds confirmed the change. [wasOn] is the mode before we touched
     * it, when the earbuds told us, so a call can put things back as it found them.
     */
    data class Ok(val wasOn: Boolean? = null) : DriverResult
    /** The earbuds answered but refused (this model may not support it). */
    data class Rejected(val status: Int) : DriverResult
    /** The earbuds said they don't have a low-latency mode. */
    data object Unsupported : DriverResult
    /** Connected, but no answer: probably not supported on this model. */
    data object NoAnswer : DriverResult
    /** Couldn't open the control channel; usually the brand's own app is holding it. */
    data object ChannelBusy : DriverResult
    data class Failed(val reason: String) : DriverResult
}

/**
 * Talks to one family of earbuds over their companion-app control channel.
 * Add a driver here for each brand whose protocol is documented; the rest of
 * the app doesn't need to change.
 */
interface EarbudDriver {
    /** Shown to the user, e.g. "OPPO / OnePlus / realme". */
    val family: String

    /** True when the driver is new and hasn't been confirmed on real earbuds yet. */
    val experimental: Boolean get() = false

    /** Cheap check from the device's advertised services and name; no connection. */
    fun recognizes(device: BluetoothDevice): Boolean

    /** Switches the earbuds' own low-latency ("game") mode. */
    suspend fun setLowLatency(device: BluetoothDevice, enabled: Boolean): DriverResult
}

object EarbudDrivers {
    /** Most specific first: brand-only service UUIDs before shared serial-port ones. */
    val all: List<EarbudDriver> = listOf(
        OppoFamilyDriver(),
        NothingDriver(),
        XiaomiDriver(),
        HuaweiDriver(),
        EarFunDriver(),
    )

    fun find(device: BluetoothDevice): EarbudDriver? = all.firstOrNull { runCatching { it.recognizes(device) }.getOrDefault(false) }

    /** For the "which earbuds work" list in the tuner. */
    val familyNames: List<String> get() = all.map { it.family }
}
