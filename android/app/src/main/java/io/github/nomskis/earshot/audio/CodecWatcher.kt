package io.github.nomskis.earshot.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Bluetooth codec in use, as Android announced it. */
data class CodecInfo(
    val device: String?,
    val codec: String,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    /** Codecs the earbuds and phone could also use. */
    val selectable: List<String>,
    val atMillis: Long,
) {
    val summary: String
        get() = buildString {
            append(codec)
            sampleRateHz?.let { append(" · ").append(if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000.0)) }
            bitsPerSample?.let { append(" · ").append(it).append("-bit") }
        }
}

/**
 * Learns which Bluetooth codec the earbuds use, without any special access.
 *
 * Android's Bluetooth service broadcasts ACTION_CODEC_CONFIG_CHANGED with a
 * (public) BluetoothCodecStatus whenever the codec is set up or changed, and
 * only requires BLUETOOTH_CONNECT to receive it (A2dpService.broadcastCodecConfig).
 * So we see the codec when earbuds connect and every time it's switched in
 * Developer options, and the delay tuner can label measurements with it.
 */
class CodecWatcher(context: Context) {
    private val appContext = context.applicationContext
    private val _latest = MutableStateFlow<CodecInfo?>(null)
    val latest: StateFlow<CodecInfo?> = _latest.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) handle(intent)
        }
    }

    /** Starts listening. Safe to call repeatedly, e.g. after the permission is granted. */
    fun start() {
        if (registered || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Sent by the Bluetooth app, i.e. another process: must be exported.
        ContextCompat.registerReceiver(appContext, receiver, IntentFilter(ACTION_CODEC_CONFIG_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("MissingPermission")
    private fun handle(intent: Intent) {
        val status = intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java) ?: return
        val config = status.codecConfig ?: return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        _latest.value = CodecInfo(
            device = runCatching { device?.name }.getOrNull(),
            codec = codecName(config),
            sampleRateHz = Codecs.sampleRate(config.sampleRate),
            bitsPerSample = Codecs.bitsPerSample(config.bitsPerSample),
            selectable = status.codecsSelectableCapabilities.map(::codecName).distinct(),
            atMillis = System.currentTimeMillis(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun codecName(config: BluetoothCodecConfig): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Extended codec types carry vendor codecs (LHDC and friends) with their names.
            runCatching { config.extendedCodecType?.codecName }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return Codecs.name(config.codecType)
    }

    companion object {
        /** BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED (a @SystemApi constant, so spelled out). */
        const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
    }
}

/** Decoding of BluetoothCodecConfig's integer fields. Kept free of Android types so it can be unit tested. */
object Codecs {
    fun name(type: Int): String = when (type) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5 -> "LC3"
        6 -> "Opus"
        else -> "Codec $type"
    }

    /** The reverse of [name]: "SBC" -> 0, "Codec 9" -> 9; null if unknown. */
    fun typeOf(name: String): Int? {
        val n = name.trim()
        (0..6).firstOrNull { name(it).equals(n, ignoreCase = true) }?.let { return it }
        return n.removePrefix("Codec ").toIntOrNull()?.takeIf { n.startsWith("Codec ") }
    }

    /** BluetoothCodecConfig.SAMPLE_RATE_* are bit flags. */
    fun sampleRate(flags: Int): Int? = when {
        flags and 0x20 != 0 -> 192_000
        flags and 0x10 != 0 -> 176_400
        flags and 0x08 != 0 -> 96_000
        flags and 0x04 != 0 -> 88_200
        flags and 0x02 != 0 -> 48_000
        flags and 0x01 != 0 -> 44_100
        else -> null
    }

    /** BluetoothCodecConfig.BITS_PER_SAMPLE_* are bit flags. */
    fun bitsPerSample(flags: Int): Int? = when {
        flags and 0x04 != 0 -> 32
        flags and 0x02 != 0 -> 24
        flags and 0x01 != 0 -> 16
        else -> null
    }

    /** Rough latency class, used to order codec trials: fast first. */
    fun isHighResolution(name: String): Boolean {
        val n = name.lowercase()
        return "ldac" in n || "lhdc" in n || "aptx hd" in n || "hd" == n
    }
}
