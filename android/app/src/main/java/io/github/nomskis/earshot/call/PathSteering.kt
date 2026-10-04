package io.github.nomskis.earshot.call

/**
 * Moves the call off Wi-Fi that is up but badly degraded, and back once it
 * recovers. Crowded gym Wi-Fi drops packets without ever going silent, so
 * ICE, which only gives up on a path that stops answering, never leaves it.
 *
 * ICE keeps pinging every working path (the one in use each second, the
 * standbys every two), so each path's own ping success rate is a
 * like-for-like measure of how it's doing. When the Wi-Fi path loses a fifth
 * of its pings while the mobile-data path loses almost none, the trouble is
 * this phone's Wi-Fi, not the other side's network, and mobile data is the
 * better path. Only used with mobile data as a backup (the default).
 */
class PathSteering(
    private val windowSamples: Int = 5,
    private val minHoldMs: Long = 60_000,
) {
    /** One candidate pair from a stats report (cumulative counters). */
    data class CandidatePair(val id: String, val networkType: String, val requestsSent: Double, val responsesReceived: Double)

    private data class Counts(val sent: Double, val received: Double)

    private val lastById = HashMap<String, Counts>()
    /** Per network type, the last few intervals' ping counts. */
    private val windows = HashMap<String, ArrayDeque<Counts>>()

    var prefersCellular = false
        private set
    private var switchedAt = 0L

    /** Feeds one stats interval; returns the new preference when it changes. */
    fun update(pairs: List<CandidatePair>, nowMs: Long): Boolean? {
        val interval = HashMap<String, Counts>()
        for (pair in pairs) {
            val last = lastById[pair.id]
            lastById[pair.id] = Counts(pair.requestsSent, pair.responsesReceived)
            if (last == null || pair.requestsSent < last.sent || pair.responsesReceived < last.received) continue
            val sent = pair.requestsSent - last.sent
            val received = pair.responsesReceived - last.received
            val type = pair.networkType.lowercase()
            val sum = interval[type] ?: Counts(0.0, 0.0)
            interval[type] = Counts(sum.sent + sent, sum.received + received)
        }
        lastById.keys.retainAll(pairs.map { it.id }.toSet())
        for (type in windows.keys + interval.keys) {
            val window = windows.getOrPut(type) { ArrayDeque() }
            window.addLast(interval[type] ?: Counts(0.0, 0.0))
            while (window.size > windowSamples) window.removeFirst()
        }

        val wifi = loss(WIFI)
        val cellular = loss(CELLULAR)
        if (!prefersCellular) {
            if (wifi != null && cellular != null && wifi >= BAD_LOSS && cellular <= GOOD_LOSS) {
                prefersCellular = true
                switchedAt = nowMs
                return true
            }
        } else if (nowMs - switchedAt >= minHoldMs && (wifi != null && wifi <= GOOD_LOSS || cellular != null && cellular >= BAD_LOSS)) {
            // Wi-Fi is healthy again, or mobile data has got as bad: let ICE choose as usual.
            prefersCellular = false
            switchedAt = nowMs
            return false
        }
        return null
    }

    /** Share of pings lost on this network over the window, or null without enough of them to judge. */
    private fun loss(type: String): Double? {
        val window = windows[type] ?: return null
        val sent = window.sumOf { it.sent }
        if (sent < MIN_PINGS) return null
        val received = window.sumOf { it.received }
        return (1 - received / sent).coerceIn(0.0, 1.0)
    }

    companion object {
        const val WIFI = "wifi"
        const val CELLULAR = "cellular"
        const val BAD_LOSS = 0.2
        const val GOOD_LOSS = 0.05
        /** About 10 s of pings on a standby path. */
        const val MIN_PINGS = 4.0
    }
}
