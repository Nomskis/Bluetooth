package io.github.nomskis.earshot.call

import kotlin.math.max
import kotlin.math.min

/**
 * Spends video bits by picture quality instead of by habit. WebRTC's encoders
 * use every bit they're allowed, so a mostly still picture (a phone propped
 * up, someone talking) is coded far finer than anyone can see. The
 * quantizer each frame was coded with (qpSum / framesEncoded) says how fine:
 * when it stays below "already excellent", the allowance comes down a step;
 * when motion pushes it above "still very good", the allowance goes back up
 * straight away, to the [ceilingKbps]. The picture stays in the same quality
 * band; only the data it costs changes.
 *
 * WebRTC's own bandwidth estimate still has the last word: the allowance
 * only ever lowers what it would send.
 */
class VideoGovernor(ceilingKbps: Int, private val floorKbps: Int = FLOOR_KBPS) {
    /** WebRTC's own ceiling for the size being sent: the allowance replaces it, so never above it. */
    var ceilingKbps: Int = ceilingKbps
        private set

    /** The allowance in force, kbps. */
    var capKbps: Int = ceilingKbps
        private set

    /**
     * The picture is sent at another size, so WebRTC's ceiling for it is [kbps]. An allowance
     * at the old ceiling moves with it; a lowered one never ends up above the new ceiling.
     * Returns the new allowance when it changes.
     */
    fun setCeiling(kbps: Int): Int? {
        if (kbps == ceilingKbps) return null
        val atCeiling = capKbps >= ceilingKbps
        ceilingKbps = kbps
        return set(if (atCeiling) kbps else min(capKbps, kbps))
    }

    private var last: Sample? = null
    private var calmIntervals = 0

    private data class Sample(val qpSum: Double, val frames: Double, val bytes: Double, val atMs: Long)

    /**
     * Feeds one stats interval of our outgoing video: codec name, cumulative qpSum,
     * framesEncoded and bytesSent. Returns the new allowance when it changes.
     */
    fun update(codec: String?, qpSum: Double?, framesEncoded: Double?, bytesSent: Double?, nowMs: Long): Int? {
        val band = bandFor(codec) ?: return null
        if (qpSum == null || framesEncoded == null || bytesSent == null) return null
        val previous = last
        last = Sample(qpSum, framesEncoded, bytesSent, nowMs)
        if (previous == null || framesEncoded <= previous.frames || nowMs <= previous.atMs) return null
        val frames = framesEncoded - previous.frames
        if (frames < MIN_FRAMES) return null
        val qp = (qpSum - previous.qpSum) / frames
        // An encoder that reports no quantizer (0) would look like perfect quality: ignore it
        // rather than starve the picture.
        if (qp < MIN_PLAUSIBLE_QP) return null
        val sentKbps = (bytesSent - previous.bytes) * 8 / (nowMs - previous.atMs)

        if (qp > band.high) {
            calmIntervals = 0
            // Motion, a scene change: give the bits back at once.
            return set(min(ceilingKbps, max((capKbps * RAISE).toInt(), capKbps + RAISE_MIN_KBPS)))
        }
        // Lower only when the allowance is what the encoder is actually using; otherwise
        // something else (the bandwidth estimate) already holds it down.
        if (qp < band.low && sentKbps >= capKbps * USING) {
            calmIntervals++
            if (calmIntervals >= CALM_INTERVALS) {
                calmIntervals = 0
                return set(max(floorKbps, (capKbps * LOWER).toInt()))
            }
        } else {
            calmIntervals = 0
        }
        return null
    }

    private fun set(kbps: Int): Int? {
        if (kbps == capKbps) return null
        capKbps = kbps
        return kbps
    }

    /** A new connection: start from the ceiling again. */
    fun newConnection() {
        last = null
        calmIntervals = 0
        capKbps = ceilingKbps
    }

    /** Quantizer range per codec (their scales differ): below [low] is wasted finesse, above [high] needs bits. */
    data class Band(val low: Double, val high: Double)

    companion object {
        /**
         * VP9 reports 0..255, VP8 its 0..127 (WebRTC caps it at 56 for cameras), H.264 0..51.
         * All well inside where WebRTC itself is happy with the quality: its quality scaler
         * only starts lowering the resolution above 205 for VP9, 95 for VP8, 37 for H.264.
         */
        fun bandFor(codec: String?): Band? = when (codec?.uppercase()) {
            "VP9" -> Band(low = 60.0, high = 90.0)
            "VP8" -> Band(low = 10.0, high = 18.0)
            "H264" -> Band(low = 22.0, high = 28.0)
            else -> null
        }

        const val FLOOR_KBPS = 250
        const val CALM_INTERVALS = 2
        const val LOWER = 0.85
        const val RAISE = 1.3
        const val RAISE_MIN_KBPS = 100
        /** The encoder counts as using the allowance at this share of it. */
        const val USING = 0.8
        /** Fewer frames than this in an interval (camera paused) tell nothing. */
        const val MIN_FRAMES = 10.0
        /** No real encoder codes a camera picture this finely; below it the reporting is broken. */
        const val MIN_PLAUSIBLE_QP = 1.0
    }
}
