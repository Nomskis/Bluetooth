package io.github.nomskis.earshot.call

import kotlin.math.roundToInt

/**
 * Where her voice spends its time on the way to your ear, live from the
 * call's stats plus what's known about the earbuds. Each part is either
 * measured during the call or a stated estimate.
 */
data class DelayBreakdown(
    /** Her microphone, encoder and packetisation (not visible from here; estimated). */
    val senderMs: Int,
    /** One way through the network: half the round trip of the connection in use. */
    val networkMs: Int?,
    /** Waiting in our jitter buffer, averaged over the last few seconds. */
    val jitterBufferMs: Int?,
    /** From the app to your ear: Android's audio path, Bluetooth and the earbuds. */
    val playoutMs: Int?,
    /** True when [playoutMs] came from a delay-tuner measurement rather than Android's own estimate. */
    val playoutMeasured: Boolean,
    /** Share of her audio packets lost over the last interval, 0..100 (before repair by RED and concealment). */
    val lossPercent: Double? = null,
    /** Through a TURN relay rather than direct; null until known. */
    val relayed: Boolean? = null,
) {
    /**
     * Enough loss, network delay or jitter that the call will stutter or lag
     * noticeably, so it's the connection rather than the earbuds. Same
     * thresholds as web/js/delay.js.
     */
    val weakConnection: Boolean
        get() = (lossPercent ?: 0.0) >= WEAK_LOSS_PERCENT || (networkMs ?: 0) >= WEAK_NETWORK_MS || (jitterBufferMs ?: 0) >= WEAK_BUFFER_MS

    /** Null until the parts that vary are known. */
    val totalMs: Int? get() = if (networkMs == null || jitterBufferMs == null || playoutMs == null) null else senderMs + networkMs + jitterBufferMs + playoutMs

    companion object {
        /**
         * 10 ms capture buffer + 10 ms packet + Opus look-ahead (6.5 ms) and
         * encode time; the other side sends 10 ms packets because we ask.
         */
        const val SENDER_ESTIMATE_MS = 30
        const val WEAK_LOSS_PERCENT = 8.0
        const val WEAK_NETWORK_MS = 300
        const val WEAK_BUFFER_MS = 250
    }
}

/**
 * Turns successive stats reports into a [DelayBreakdown]. The jitter-buffer
 * counters are cumulative, so the average over the last interval comes from
 * the difference between two reports.
 */
class DelayTracker {
    private var lastDelaySeconds: Double? = null
    private var lastEmitted: Double? = null
    private var jitterMs: Int? = null
    private var lastReceived: Double? = null
    private var lastLost: Double? = null
    private var lossPercent: Double? = null

    /** [playoutMs] is the app-to-ear figure in use, [measured] whether it came from the delay tuner. */
    fun update(report: Map<String, CallStats.Entry>, playoutMs: Double?, measured: Boolean): DelayBreakdown {
        val rtt = CallStats.roundTripSeconds(report)
        val (delay, emitted) = CallStats.audioJitterBuffer(report) ?: (null to null)
        if (delay != null && emitted != null) {
            val prevDelay = lastDelaySeconds
            val prevEmitted = lastEmitted
            if (prevDelay != null && prevEmitted != null && emitted > prevEmitted && delay >= prevDelay) {
                jitterMs = ((delay - prevDelay) / (emitted - prevEmitted) * 1000).roundToInt()
            } else if (prevDelay == null && emitted > 0) {
                jitterMs = (delay / emitted * 1000).roundToInt()
            }
            lastDelaySeconds = delay
            lastEmitted = emitted
        }
        CallStats.audioPackets(report)?.let { (received, lost) ->
            val prevReceived = lastReceived
            val prevLost = lastLost
            if (prevReceived != null && prevLost != null && received >= prevReceived && lost >= prevLost) {
                val expected = (received - prevReceived) + (lost - prevLost)
                if (expected > 0) lossPercent = (lost - prevLost) / expected * 100
            }
            lastReceived = received
            lastLost = lost
        }
        return DelayBreakdown(
            senderMs = DelayBreakdown.SENDER_ESTIMATE_MS,
            networkMs = rtt?.let { (it * 1000 / 2).roundToInt() },
            jitterBufferMs = jitterMs,
            playoutMs = playoutMs?.roundToInt(),
            playoutMeasured = measured,
            lossPercent = lossPercent,
            relayed = CallStats.relayed(report),
        )
    }
}
