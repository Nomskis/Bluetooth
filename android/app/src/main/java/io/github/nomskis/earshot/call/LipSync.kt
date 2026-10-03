package io.github.nomskis.earshot.call

/**
 * Keeps her lips in time with her voice when the voice goes through
 * Bluetooth earbuds.
 *
 * WebRTC lines video up with audio assuming audio takes a fixed time to play
 * once it leaves the jitter buffer. On Android that assumption is 75 ms (half
 * of the 150 ms "high latency" estimate the Java audio module is built with).
 * Over A2DP the real figure is typically 150-300 ms, so video runs ahead of
 * the voice by the difference, enough to notice. Earshot holds the video
 * back by that difference. That adds nothing to the conversation's delay:
 * the voice was already the slower of the two.
 */
object LipSync {
    /** What WebRTC's Android audio module reports as playout delay. */
    const val WEBRTC_ASSUMED_MS = 75.0

    /** Time from handing a frame to the renderer to it being on screen (a display frame or so). */
    const val DISPLAY_MS = 20.0

    /** Never hold video longer than this; past it something is mismeasured. */
    const val MAX_MS = 400

    /** Below this, people can't tell (ITU-R BT.1359 puts detectability of lagging audio near 125 ms). */
    const val NOT_WORTH_MS = 25

    enum class Source { MEASURED, ESTIMATED }

    data class Plan(val videoDelayMs: Int, val audioDelayMs: Double, val source: Source)

    /**
     * [measuredMs] is the sonar's app-to-ear result for these earbuds, the best
     * evidence there is. [estimatedMs] is what Android itself believes the
     * output latency is, from the playback timestamps. Null when neither is
     * known or audio isn't on Bluetooth.
     */
    fun plan(onBluetooth: Boolean, measuredMs: Double?, estimatedMs: Double?): Plan? {
        if (!onBluetooth) return null
        val (audio, source) = when {
            measuredMs != null -> measuredMs to Source.MEASURED
            estimatedMs != null -> estimatedMs to Source.ESTIMATED
            else -> return null
        }
        val extra = (audio - WEBRTC_ASSUMED_MS - DISPLAY_MS).toInt().coerceIn(0, MAX_MS)
        if (extra < NOT_WORTH_MS) return null
        return Plan(extra, audio, source)
    }
}
