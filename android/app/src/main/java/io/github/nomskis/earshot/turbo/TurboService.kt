package io.github.nomskis.earshot.turbo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with the adb shell identity,
 * which holds BLUETOOTH_PRIVILEGED and MODIFY_AUDIO_ROUTING
 * (frameworks/base packages/Shell/AndroidManifest.xml). That is what the
 * @SystemApi calls below need. Every call is defensive: phone makers change
 * these internals, so a failure returns a neutral value instead of crashing.
 */
@SuppressLint("MissingPermission", "PrivateApi", "DiscouragedPrivateApi")
class TurboService(private val context: Context) : ITurboService.Stub() {

    /** A context whose attribution (uid 2000 + com.android.shell) matches this process. */
    private val shellContext: Context by lazy {
        runCatching { context.createPackageContext("com.android.shell", 0) }.getOrDefault(context)
    }

    private val a2dp: BluetoothA2dp? by lazy { connectA2dp() }

    override fun destroy() {
        exitProcess(0)
    }

    override fun dump(service: String): String {
        if (service !in ALLOWED_DUMPS) return ""
        return runCatching {
            val process = ProcessBuilder("dumpsys", service).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (out.length < MAX_DUMP_CHARS) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    out.append(buffer, 0, n)
                }
                out.toString()
            }
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroy()
            text
        }.getOrElse { "dump failed: ${it.message}" }
    }

    override fun setVariableLatency(enabled: Boolean): Boolean = runCatching {
        val am = shellContext.getSystemService(AudioManager::class.java)
        AudioManager::class.java.getMethod("setBluetoothVariableLatencyEnabled", Boolean::class.javaPrimitiveType)
            .invoke(am, enabled)
        AudioManager::class.java.getMethod("isBluetoothVariableLatencyEnabled").invoke(am) as Boolean
    }.getOrElse {
        Log.w(TAG, "setVariableLatency failed", it)
        false
    }

    override fun codecStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
        return runCatching {
            val proxy = a2dp ?: return ""
            val device = activeDevice(proxy) ?: return ""
            val status = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java)
                .invoke(proxy, device) as? BluetoothCodecStatus ?: return ""
            val c = status.codecConfig ?: return ""
            val selectable = status.codecsSelectableCapabilities.map { it.codecType }.distinct().joinToString(",")
            "${c.codecType}:${c.sampleRate}:${c.bitsPerSample}:${c.codecSpecific1}|$selectable"
        }.getOrElse {
            Log.w(TAG, "codecStatus failed", it)
            ""
        }
    }

    override fun setCodec(codecType: Int, codecSpecific1: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val proxy = a2dp ?: return false
            val device = activeDevice(proxy) ?: return false
            if (codecType != SBC) {
                // Anything but SBC is an "optional codec" (the HD audio switch in Bluetooth settings).
                runCatching {
                    BluetoothA2dp::class.java.getMethod("enableOptionalCodecs", BluetoothDevice::class.java).invoke(proxy, device)
                }
            }
            val config = BluetoothCodecConfig.Builder()
                .setCodecType(codecType)
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setCodecSpecific1(codecSpecific1)
                .build()
            BluetoothA2dp::class.java
                .getMethod("setCodecConfigPreference", BluetoothDevice::class.java, BluetoothCodecConfig::class.java)
                .invoke(proxy, device, config)
            true
        }.getOrElse {
            Log.w(TAG, "setCodec failed", it)
            false
        }
    }

    override fun dynamicBufferSupport(): Int = runCatching {
        val proxy = a2dp ?: return 0
        BluetoothA2dp::class.java.getMethod("getDynamicBufferSupport").invoke(proxy) as Int
    }.getOrDefault(0)

    override fun minBufferMillis(codecType: Int): Int = bufferConstraint(codecType, "getMinMillis")

    override fun defaultBufferMillis(codecType: Int): Int = bufferConstraint(codecType, "getDefaultMillis")

    /** BluetoothA2dp.getBufferConstraints().forCodec(type).<getter>(), or -1. */
    private fun bufferConstraint(codecType: Int, getter: String): Int = runCatching {
        val proxy = a2dp ?: return -1
        val constraints = BluetoothA2dp::class.java.getMethod("getBufferConstraints").invoke(proxy) ?: return -1
        val constraint = constraints.javaClass.getMethod("forCodec", Int::class.javaPrimitiveType).invoke(constraints, codecType)
            ?: return -1
        constraint.javaClass.getMethod(getter).invoke(constraint) as Int
    }.getOrDefault(-1)

    override fun setBufferMillis(codecType: Int, millis: Int): Boolean = runCatching {
        val proxy = a2dp ?: return false
        BluetoothA2dp::class.java.getMethod("setBufferLengthMillis", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(proxy, codecType, millis) as Boolean
    }.getOrDefault(false)

    private fun activeDevice(proxy: BluetoothA2dp): BluetoothDevice? =
        runCatching { BluetoothA2dp::class.java.getMethod("getActiveDevice").invoke(proxy) as? BluetoothDevice }.getOrNull()
            ?: proxy.connectedDevices.firstOrNull()

    private fun connectA2dp(): BluetoothA2dp? {
        val adapter = shellContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val latch = CountDownLatch(1)
        var proxy: BluetoothA2dp? = null
        adapter.getProfileProxy(
            shellContext,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    proxy = p as? BluetoothA2dp
                    latch.countDown()
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            },
            BluetoothProfile.A2DP,
        )
        latch.await(3, TimeUnit.SECONDS)
        return proxy
    }

    private companion object {
        const val TAG = "EarshotTurbo"
        const val SBC = 0
        const val MAX_DUMP_CHARS = 2_000_000
        val ALLOWED_DUMPS = setOf("bluetooth_manager", "media.audio_flinger")
    }
}
