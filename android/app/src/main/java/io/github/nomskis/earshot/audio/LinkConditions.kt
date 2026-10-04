package io.github.nomskis.earshot.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

enum class WifiBand { GHZ_2_4, GHZ_5, GHZ_6 }

/**
 * Radio conditions that affect Bluetooth audio. Phones run Wi-Fi and
 * Bluetooth on one combo chip that shares the 2.4 GHz band, so Wi-Fi traffic
 * there (a video call, say) costs Bluetooth airtime and retransmissions.
 */
object LinkConditions {

    /** The band of the current Wi-Fi connection, or null when not on Wi-Fi. */
    fun wifiBand(context: Context): WifiBand? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val frequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (caps.transportInfo as? WifiInfo)?.frequency
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.frequency
        }
        return frequency?.let(::bandFor)
    }

    /**
     * The phone is connected to Wi-Fi (or Ethernet) that Android has checked
     * reaches the internet, whether or not it's the phone's default network:
     * phones that move their own traffic to mobile data when Wi-Fi seems slow,
     * or a VPN, make another network the default while Wi-Fi still works. Not a
     * Wi-Fi waiting at a login page or without internet: the call needs mobile
     * data then, like everything else on the phone.
     */
    fun hasWorkingWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION") // Still the one call that lists every network, default or not.
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            local && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    /** "wifi" (or Ethernet) or "cellular" for the phone's own network now; null when there's none or it's something else. */
    fun networkKind(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> null
        }
    }

    fun bandFor(frequencyMhz: Int): WifiBand? = when (frequencyMhz) {
        in 2_400..2_500 -> WifiBand.GHZ_2_4
        in 4_900..5_900 -> WifiBand.GHZ_5
        in 5_925..7_125 -> WifiBand.GHZ_6
        else -> null
    }
}
