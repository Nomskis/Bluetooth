package io.github.nomskis.earshot.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Keeps mobile data up while the phone is on Wi-Fi, so WebRTC can gather
 * candidates on it and carry the call there. Android may otherwise let the
 * cellular link sleep once Wi-Fi is connected. Released when the call ends.
 */
class CellularStandby(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var available: Boolean = false
        private set

    @Synchronized
    fun acquire() {
        if (callback != null || cm == null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available = true
                Log.i(TAG, "Mobile data ready alongside Wi-Fi")
            }

            override fun onLost(network: Network) {
                available = false
            }

            override fun onUnavailable() {
                available = false
                Log.i(TAG, "No mobile data available")
            }
        }
        runCatching { cm.requestNetwork(request, cb) }
            .onSuccess { callback = cb }
            .onFailure { Log.w(TAG, "Could not request mobile data", it) }
    }

    @Synchronized
    fun release() {
        val cb = callback ?: return
        callback = null
        available = false
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    private companion object {
        const val TAG = "EarshotCellular"
    }
}
