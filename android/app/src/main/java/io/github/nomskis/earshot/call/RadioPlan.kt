package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.audio.WifiBand
import io.github.nomskis.earshot.settings.AppSettings

/** Which network the call's media is actually flowing over, from WebRTC's stats. */
enum class CallPath { WIFI, CELLULAR, OTHER }

/**
 * How the call should treat the radio it shares with Bluetooth.
 *
 * Phones run Wi-Fi and Bluetooth on one combo chip, and on 2.4 GHz they take
 * turns on the same antenna. Every Wi-Fi packet of a video call is time the
 * earbuds' A2DP stream can't use: retransmissions go up, and earbuds with
 * adaptive buffers respond by buffering (and delaying) more. 5 and 6 GHz
 * Wi-Fi and mobile data don't share that band.
 *
 * Works the same for every pair of classic Bluetooth earbuds.
 */
data class RadioPlan(
    /** Ask WebRTC to carry media over mobile data when it can. */
    val preferCellular: Boolean,
    /** Cap for video in both directions while the call runs over 2.4 GHz Wi-Fi; null = no cap. */
    val wifiVideoCapKbps: Int?,
    /**
     * Mark the call's packets for Wi-Fi's priority queue (DSCP EF/AF42, which
     * phones' Wi-Fi drivers map to the WMM video access category). Busy Wi-Fi
     * then lets them through ahead of everyone's best-effort traffic: less
     * queueing on the way out, less jitter for the other side to buffer.
     * Off on 2.4 GHz next to Bluetooth audio, where priority Wi-Fi traffic
     * could take airtime from the earbuds.
     */
    val priorityMarking: Boolean = true,
) {
    /** The cap to apply right now, given where media is actually flowing. */
    fun videoCapFor(path: CallPath?): Int? = if (path == CallPath.CELLULAR) null else wifiVideoCapKbps

    val active: Boolean get() = preferCellular || wifiVideoCapKbps != null

    companion object {
        /**
         * Enough for clear 540p-720p video, a third to half of what WebRTC
         * would otherwise use, so the radio is free most of the time.
         */
        const val VIDEO_CAP_KBPS = 800

        val NONE = RadioPlan(preferCellular = false, wifiVideoCapKbps = null)

        fun decide(band: WifiBand?, bluetoothAudio: Boolean, settings: AppSettings): RadioPlan {
            if (!bluetoothAudio || band != WifiBand.GHZ_2_4) return NONE
            return RadioPlan(
                preferCellular = settings.mobileDataOn24GHz,
                wifiVideoCapKbps = if (settings.bluetoothFriendlyVideo) VIDEO_CAP_KBPS else null,
                priorityMarking = false,
            )
        }

        /** Maps the stats "networkType" of the local candidate in use. */
        fun pathFor(networkType: String?): CallPath? = when (networkType?.lowercase()) {
            null, "" -> null
            "wifi" -> CallPath.WIFI
            "cellular" -> CallPath.CELLULAR
            else -> CallPath.OTHER
        }

        /** One line for the call screen, or null when there's nothing worth saying. */
        fun describe(plan: RadioPlan, path: CallPath?): String? = when {
            !plan.active -> null
            path == CallPath.CELLULAR -> "On mobile data: the Bluetooth radio is all your earbuds'"
            plan.preferCellular && plan.wifiVideoCapKbps != null -> "2.4 GHz Wi-Fi: lighter video until mobile data takes over"
            plan.preferCellular -> "2.4 GHz Wi-Fi: moving the call to mobile data"
            else -> "2.4 GHz Wi-Fi: lighter video so your earbuds stay smooth"
        }
    }
}
