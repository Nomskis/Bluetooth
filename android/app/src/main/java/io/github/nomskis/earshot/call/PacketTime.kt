package io.github.nomskis.earshot.call

import kotlin.math.min

/**
 * How long the audio packets we ask the other side for should be.
 *
 * 10 ms packets save delay, and each one carries the three before it (RED),
 * so up to 30 ms of consecutive loss is repaired exactly. Weak Wi-Fi and
 * mobile data, especially at the end of a long international path, lose
 * longer runs than that, and then NetEq has to conceal what's missing. Longer
 * packets stretch the same three copies over more time: 20 ms packets repair
 * gaps up to 60 ms, 40 ms ones up to 120 ms, at half or a quarter of the
 * packet rate (less airtime, less per-packet overhead). The cost is 10 or
 * 30 ms more delay, which only matters on a link that's fine anyway.
 *
 * So it's decided from what we receive: when packets go missing and audio
 * still has to be concealed after RED, Opus FEC and resends have done what
 * they can, ask for longer packets; after a calm minute, shorter again. A
 * step back down that doesn't hold makes the next one wait longer. Same rules
 * as web/js/ptime.js.
 */
class PacketTime(start: Step = Step.SHORT) {
    enum class Step(val ms: Int) { SHORT(10), MEDIUM(20), LONG(40) }

    /** Where it starts: shorter after a calm minute if the link has got better ([LinkMemory]). */
    var step = start
        private set
    val ms: Int get() = step.ms

    private var last: Counters? = null
    private var roughSamples = 0
    private var calmSince: Long? = null
    private var changedAt: Long? = null
    private var holdMs = CALM_MS
    /** When it last stepped down, until that has held or failed. */
    private var downAt: Long? = null

    /** Cumulative counters for the audio we receive (RTCInboundRtpStreamStats). */
    data class Counters(val packetsReceived: Double, val packetsLost: Double, val concealedSamples: Double, val totalSamples: Double)

    /** Feeds one stats interval; true when [step] changed. */
    fun update(now: Counters, nowMs: Long): Boolean {
        val before = last
        last = now
        // A new connection's counters start again from zero.
        if (before == null || now.packetsReceived < before.packetsReceived || now.totalSamples < before.totalSamples) return false
        val received = now.packetsReceived - before.packetsReceived
        val lost = (now.packetsLost - before.packetsLost).coerceAtLeast(0.0)
        val samples = now.totalSamples - before.totalSamples
        if (received + lost <= 0 || samples <= 0) return false
        val loss = lost / (received + lost)
        val concealed = (now.concealedSamples - before.concealedSamples).coerceAtLeast(0.0) / samples

        val rough = loss >= ROUGH_LOSS && concealed >= ROUGH_CONCEALED
        roughSamples = if (rough) roughSamples + 1 else 0
        calmSince = if (loss < CALM_LOSS && concealed < CALM_CONCEALED) calmSince ?: nowMs else null
        downAt?.let { if (nowMs - it >= HELD_MS) {
            holdMs = CALM_MS
            downAt = null
        } }

        val sinceChange = changedAt?.let { nowMs - it } ?: Long.MAX_VALUE
        if (roughSamples >= ROUGH_SAMPLES && step != Step.LONG && sinceChange >= SETTLE_MS) {
            // Rough again this soon after stepping down: the next step down waits longer.
            downAt?.let { if (nowMs - it < FAILED_MS) holdMs = min(holdMs * 2, MAX_HOLD_MS) }
            downAt = null
            change(Step.entries[step.ordinal + 1], nowMs)
            return true
        }
        val calmFor = calmSince?.let { nowMs - it } ?: return false
        if (step != Step.SHORT && calmFor >= holdMs && sinceChange >= holdMs) {
            change(Step.entries[step.ordinal - 1], nowMs)
            downAt = nowMs
            return true
        }
        return false
    }

    private fun change(next: Step, nowMs: Long) {
        step = next
        changedAt = nowMs
        roughSamples = 0
        calmSince = null
    }

    companion object {
        /** Packets lost on the way, before any repair. */
        const val ROUGH_LOSS = 0.03
        /** Share of received audio NetEq still had to make up. */
        const val ROUGH_CONCEALED = 0.015
        /** Consecutive rough intervals (2 s each) before asking for longer packets. */
        const val ROUGH_SAMPLES = 2
        const val CALM_LOSS = 0.01
        const val CALM_CONCEALED = 0.003
        /** A change takes a moment to arrive (one renegotiation) and show in the stats. */
        const val SETTLE_MS = 10_000L
        /** Calm for this long, then one step shorter. */
        const val CALM_MS = 60_000L
        const val MAX_HOLD_MS = 240_000L
        /** Rough again this soon after a step down: that step failed. */
        const val FAILED_MS = 30_000L
        /** A step down that lasted this long has held. */
        const val HELD_MS = 60_000L
    }
}
