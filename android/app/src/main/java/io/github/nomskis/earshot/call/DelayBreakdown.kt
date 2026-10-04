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
    /** Share of her audio NetEq still had to make up over the last interval, after every repair, 0..100. */
    val concealedPercent: Double? = null,
    /** Share of our audio packets lost on the way to her, as her side last reported it (RTCP), 0..100. */
    val sendLossPercent: Double? = null,
    /** How hard our side had to squeeze what we send ([MediaBudget]): FAIR leaner voice, POOR video paused. */
    val sendSqueeze: LinkQuality? = null,
    /** The audio packet length we ask her for: 10 ms, longer on a rough link ([PacketTime]). */
    val packetMs: Int = 10,
) {
    /**
     * How well her voice gets to us. On a call between two countries this is
     * mostly her uplink: weak Wi-Fi or mobile data on her side shows here.
     */
    val fromThem: LinkQuality?
        get() {
            if (lossPercent == null && concealedPercent == null && jitterBufferMs == null && networkMs == null) return null
            val loss = lossPercent ?: 0.0
            val concealed = concealedPercent ?: 0.0
            val buffer = jitterBufferMs ?: 0
            val network = networkMs ?: 0
            return when {
                loss >= WEAK_LOSS_PERCENT || concealed >= POOR_CONCEALED_PERCENT || buffer >= WEAK_BUFFER_MS || network >= WEAK_NETWORK_MS -> LinkQuality.POOR
                loss >= FAIR_LOSS_PERCENT || concealed >= FAIR_CONCEALED_PERCENT || buffer >= FAIR_BUFFER_MS || network >= FAIR_NETWORK_MS -> LinkQuality.FAIR
                else -> LinkQuality.GOOD
            }
        }

    /** How well our voice gets to her: what her side reports back, and how much we had to squeeze. */
    val toThem: LinkQuality?
        get() {
            if (sendLossPercent == null && sendSqueeze == null && networkMs == null) return null
            val loss = sendLossPercent ?: 0.0
            val network = networkMs ?: 0
            return when {
                loss >= WEAK_LOSS_PERCENT || sendSqueeze == LinkQuality.POOR || network >= WEAK_NETWORK_MS -> LinkQuality.POOR
                loss >= FAIR_LOSS_PERCENT || sendSqueeze == LinkQuality.FAIR || network >= FAIR_NETWORK_MS -> LinkQuality.FAIR
                else -> LinkQuality.GOOD
            }
        }

    /**
     * Enough loss, network delay or jitter, either way, that the call will
     * stutter or lag noticeably, so it's the connection rather than the
     * earbuds. Same thresholds as web/js/delay.js.
     */
    val weakConnection: Boolean
        get() = fromThem == LinkQuality.POOR || toThem == LinkQuality.POOR

    /** Null until the parts that vary are known. */
    val totalMs: Int? get() = if (networkMs == null || jitterBufferMs == null || playoutMs == null) null else senderMs + networkMs + jitterBufferMs + playoutMs

    companion object {
        /**
         * 10 ms capture buffer + 10 ms packet + Opus look-ahead (6.5 ms) and
         * encode time; the other side sends 10 ms packets because we ask.
         */
        const val SENDER_ESTIMATE_MS = 30

        /** The same with the packet length we ask for: longer on a rough link ([PacketTime]). */
        fun senderEstimateMs(packetMs: Int): Int = SENDER_ESTIMATE_MS - 10 + packetMs
        const val WEAK_LOSS_PERCENT = 8.0
        const val WEAK_NETWORK_MS = 300
        const val WEAK_BUFFER_MS = 250
        const val POOR_CONCEALED_PERCENT = 3.0
        const val FAIR_LOSS_PERCENT = 2.0
        const val FAIR_CONCEALED_PERCENT = 0.5
        const val FAIR_BUFFER_MS = 120
        const val FAIR_NETWORK_MS = 150
    }
}

/** One direction of the call at a glance. */
enum class LinkQuality { GOOD, FAIR, POOR }

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
    private var lastConcealed: Double? = null
    private var lastSamples: Double? = null
    private var concealedPercent: Double? = null

    /**
     * [playoutMs] is the app-to-ear figure in use, [measured] whether it came from the delay tuner,
     * [packetMs] the audio packet length we ask them for.
     */
    fun update(report: Map<String, CallStats.Entry>, playoutMs: Double?, measured: Boolean, packetMs: Int = 10): DelayBreakdown {
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
        CallStats.audioConcealment(report)?.let { (concealed, samples) ->
            val prevConcealed = lastConcealed
            val prevSamples = lastSamples
            if (prevConcealed != null && prevSamples != null && samples > prevSamples && concealed >= prevConcealed) {
                concealedPercent = (concealed - prevConcealed) / (samples - prevSamples) * 100
            }
            lastConcealed = concealed
            lastSamples = samples
        }
        return DelayBreakdown(
            senderMs = DelayBreakdown.senderEstimateMs(packetMs),
            networkMs = rtt?.let { (it * 1000 / 2).roundToInt() },
            jitterBufferMs = jitterMs,
            playoutMs = playoutMs?.roundToInt(),
            playoutMeasured = measured,
            lossPercent = lossPercent,
            relayed = CallStats.relayed(report),
            concealedPercent = concealedPercent,
            sendLossPercent = CallStats.sendLossFraction(report)?.let { it * 100 },
            packetMs = packetMs,
        )
    }
}
