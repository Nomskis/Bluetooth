package io.github.nomskis.earshot.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.nomskis.earshot.dsp.SonarSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * How much Wi-Fi traffic costs the earbuds on this phone. The sonar meter
 * runs twice: once with Wi-Fi quiet, once while the phone sends call-sized
 * traffic to its own router. The router throws that traffic away (UDP to
 * the discard port), so no server is involved and nothing leaves the local
 * network; what matters is that the phone's radio is busy transmitting, as
 * it is during a video call.
 */
object RadioTest {
    /** About what a 720p video call sends. */
    const val LOAD_KBPS = 2_500
    private const val PACKET_BYTES = 1_200
    private const val DISCARD_PORT = 9

    /** A delay change smaller than this is within the meter's spread. */
    const val MEANINGFUL_MS = 15.0

    /** Losing this share of chirps more under load means the audio drops out. */
    const val MEANINGFUL_LOSS = 0.2

    data class Verdict(val text: String, val recommendMobileData: Boolean)

    fun verdict(idle: SonarSummary?, busy: SonarSummary?, band: WifiBand?): Verdict {
        if (idle == null || busy == null) {
            return Verdict("Couldn't measure both times. Keep the earbud against the mic for the whole test.", false)
        }
        val slower = busy.delayMs - idle.delayMs
        val idleLoss = 1.0 - idle.hits.toDouble() / idle.attempts.coerceAtLeast(1)
        val busyLoss = 1.0 - busy.hits.toDouble() / busy.attempts.coerceAtLeast(1)
        val dropouts = busyLoss - idleLoss >= MEANINGFUL_LOSS
        val delayed = slower >= MEANINGFUL_MS
        val where = when (band) {
            WifiBand.GHZ_2_4 -> "on 2.4 GHz Wi-Fi"
            WifiBand.GHZ_5 -> "on 5 GHz Wi-Fi"
            WifiBand.GHZ_6 -> "on 6 GHz Wi-Fi"
            null -> "on this network"
        }
        val effects = buildList {
            if (delayed) add("${slower.toInt()} ms more delay")
            if (dropouts) add("dropouts (${(busyLoss * 100).toInt()}% of test sounds lost)")
        }
        if (effects.isEmpty()) {
            return Verdict("Wi-Fi traffic doesn't affect your earbuds $where: ${idle.delayMs.toInt()} ms quiet, ${busy.delayMs.toInt()} ms busy.", false)
        }
        val advice = if (band == WifiBand.GHZ_2_4) {
            " Use 5 GHz Wi-Fi, or let Earshot carry calls over mobile data on 2.4 GHz."
        } else {
            ""
        }
        return Verdict("Call-sized Wi-Fi traffic $where caused ${effects.joinToString(" and ")}.$advice", band == WifiBand.GHZ_2_4)
    }

    /**
     * Keeps the Wi-Fi radio transmitting at [LOAD_KBPS] towards the router
     * until the returned job is cancelled. Null when not on Wi-Fi.
     */
    fun startLoad(context: Context, scope: CoroutineScope): Job? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val gateway = cm.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway ?: return null
        return scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket()
            try {
                network.bindSocket(socket)
                val packet = DatagramPacket(ByteArray(PACKET_BYTES), PACKET_BYTES, InetSocketAddress(gateway, DISCARD_PORT))
                val intervalNanos = PACKET_BYTES * 8L * 1_000_000L / LOAD_KBPS // ns per packet
                var next = System.nanoTime()
                while (isActive) {
                    runCatching { socket.send(packet) }
                    next += intervalNanos
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            } catch (e: Exception) {
                Log.w("EarshotRadioTest", "Load stopped", e)
            } finally {
                socket.close()
            }
        }
    }
}
