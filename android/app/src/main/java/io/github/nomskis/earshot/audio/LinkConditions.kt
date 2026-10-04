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
     * The phone's own network is Wi-Fi (or Ethernet) that Android has checked
     * reaches the internet. Not a Wi-Fi still waiting at a login page: Android
     * sends traffic over mobile data then, and so must the call.
     */
    fun onWorkingWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return local && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun bandFor(frequencyMhz: Int): WifiBand? = when (frequencyMhz) {
        in 2_400..2_500 -> WifiBand.GHZ_2_4
        in 4_900..5_900 -> WifiBand.GHZ_5
        in 5_925..7_125 -> WifiBand.GHZ_6
        else -> null
    }
}
