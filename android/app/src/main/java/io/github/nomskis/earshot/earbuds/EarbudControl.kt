package io.github.nomskis.earshot.earbuds

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.nomskis.earshot.audio.EarbudApps
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Finds the connected earbuds and the driver that can switch their game mode. */
class EarbudControl(context: Context) {
    private val appContext = context.applicationContext
    val context: Context get() = appContext

    data class Target(val device: BluetoothDevice, val name: String, val driver: EarbudDriver?)

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentTarget(): Target? {
        if (!hasPermission()) return null
        val device = connectedA2dpDevice() ?: return null
        return Target(device, runCatching { device.name }.getOrNull() ?: "Bluetooth earbuds", EarbudDrivers.find(device))
    }

    suspend fun setGameMode(enabled: Boolean): Pair<Target?, DriverResult?> {
        val target = currentTarget() ?: return null to null
        val driver = target.driver ?: return target to null
        return target to driver.setLowLatency(target.device, enabled)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedA2dpDevice(): BluetoothDevice? {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val ok = adapter.getProfileProxy(
                    appContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            val device = (proxy as? BluetoothA2dp)?.connectedDevices?.firstOrNull()
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                            if (cont.isActive) cont.resume(device)
                        }

                        override fun onServiceDisconnected(profile: Int) = Unit
                    },
                    BluetoothProfile.A2DP,
                )
                if (!ok && cont.isActive) cont.resume(null)
            }
        }
    }
}

/** The brand apps that hold each family's control channel when they're running. */
private val COMPANIONS = mapOf(
    "OPPO / OnePlus / realme" to listOf("com.realme.link", "com.heytap.headset"),
    "Nothing / CMF" to listOf("com.nothing.smartcenter"),
    "Xiaomi / Redmi" to listOf("com.mi.earphone"),
    "Huawei / Honor" to listOf("com.huawei.smarthome"),
    "Soundcore" to listOf("com.oceanwing.soundcore"),
)

/**
 * [describe], naming the installed brand app when the control channel was
 * busy, since that app is almost always the one holding it.
 */
fun DriverResult.describe(context: Context, family: String?): String {
    if (this != DriverResult.ChannelBusy) return describe()
    val packages = COMPANIONS[family].orEmpty()
    val holder = EarbudApps.installed(context).firstOrNull { it.packageName in packages }
        ?: return describe()
    return "${holder.name} is probably connected to the earbuds. Force-stop it (Settings › Apps › ${holder.name} › Force stop) and try again."
}

fun DriverResult.describe(): String = when (this) {
    is DriverResult.Ok -> "Done."
    is DriverResult.Rejected -> if (status == OppoSession.NOT_APPLIED) {
        "The earbuds acknowledged but didn't switch; this model may not allow it from other apps."
    } else {
        "The earbuds refused (code $status); this model may not allow it from other apps."
    }
    DriverResult.Unsupported -> "These earbuds don't have a low-latency mode."
    DriverResult.NoAnswer -> "The earbuds didn't answer; this model may not support switching it from other apps."
    DriverResult.ChannelBusy -> "Couldn't reach the earbuds' control channel. Close the earbuds' own app and try again."
    is DriverResult.Failed -> "Failed: $reason"
}
