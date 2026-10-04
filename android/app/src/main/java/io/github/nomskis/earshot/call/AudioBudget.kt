package io.github.nomskis.earshot.call

import kotlin.math.min

/**
 * Keeps the voice inside what the connection can carry. WebRTC sends audio at
 * a fixed bitrate whatever its bandwidth estimate says, and HD voice with
 * three redundant copies (see [WebRtcTuning]) is about 220 kbps on the wire.
 * Plenty of Wi-Fi carries that easily; a starved hotspot doesn't, and then
 * the voice itself would break up. So when the estimate says it doesn't fit
 * (with room for some video), the voice steps down to a leaner Opus bitrate,
 * which still sounds good, and steps back up once there's room again.
 *
 * Stepping up is a probe: it waits a while at the lower level, and if the
 * step up doesn't hold it waits longer before the next try. That also works
 * for voice-only calls, where WebRTC's estimate barely grows beyond what's
 * being sent.
 */
class AudioBudget(private val redundancy: Int = WebRtcTuning.RED_REDUNDANCY) {
    /** Opus bitrates. FULL is whatever the other side asked for, normally HD voice. */
    enum class Level(val opusBps: Int) { FULL(SdpTuning.HD_VOICE_BITRATE), REDUCED(32_000), LOW(20_000) }

    var level = Level.FULL
        private set

    /** The Opus cap to apply to the audio sender; null leaves it as negotiated. */
    val capBps: Int? get() = if (level == Level.FULL) null else level.opusBps

    private var levelSince: Long? = null
    private var overSamples = 0
    private var holdMs = MIN_HOLD_MS
    /** When the last step up happened, until it has held or failed. */
    private var probeAt: Long? = null

    /**
     * Feeds one stats interval: the connection's send estimate (availableOutgoingBitrate)
     * and what the audio really sent, in bits per second. True when [level] changed.
     */
    fun update(availableBps: Double?, audioBps: Double?, sendingVideo: Boolean, nowMs: Long): Boolean {
        if (levelSince == null) levelSince = nowMs
        if (availableBps == null || audioBps == null || audioBps <= 0) return false
        val videoRoom = if (sendingVideo) VIDEO_ROOM_BPS else 0.0

        if (availableBps < (audioBps + videoRoom) * HEADROOM) {
            overSamples++
            if (overSamples < OVER_SAMPLES || level == Level.LOW) return false
            // A step up that didn't hold: wait longer before trying again.
            probeAt?.let { if (nowMs - it < FAILED_PROBE_MS) holdMs = min(holdMs * 2, MAX_HOLD_MS) }
            probeAt = null
            setLevel(Level.entries[level.ordinal + 1], nowMs)
            return true
        }
        overSamples = 0
        probeAt?.let { if (nowMs - it >= HELD_PROBE_MS) {
            holdMs = MIN_HOLD_MS
            probeAt = null
        } }

        if (level == Level.FULL) return false
        val up = Level.entries[level.ordinal - 1]
        if (nowMs - (levelSince ?: nowMs) < holdMs) return false
        // With video the estimate is a fair measure of the room there is, so wait for it.
        if (sendingVideo && availableBps < (wireBps(up) + videoRoom) * HEADROOM) return false
        probeAt = nowMs
        setLevel(up, nowMs)
        return true
    }

    /** Roughly what [level] costs on the wire: the voice and its copies, plus each packet's headers. */
    fun wireBps(level: Level): Double =
        (1 + redundancy) * level.opusBps.toDouble() + PACKETS_PER_SECOND * (HEADER_BYTES + 4 * redundancy + 1) * 8

    private fun setLevel(next: Level, nowMs: Long) {
        level = next
        levelSince = nowMs
        overSamples = 0
    }

    companion object {
        /** What we send should fit the estimate with this much to spare. */
        const val HEADROOM = 1.1
        /** Kept free for video when it's on, so it isn't squeezed to nothing. */
        const val VIDEO_ROOM_BPS = 100_000.0
        /** Consecutive over-budget intervals (2 s each) before stepping down. */
        const val OVER_SAMPLES = 2
        const val MIN_HOLD_MS = 20_000L
        const val MAX_HOLD_MS = 160_000L
        /** A step up undone this soon counts as failed. */
        const val FAILED_PROBE_MS = 30_000L
        /** A step up that lasted this long has held. */
        const val HELD_PROBE_MS = 60_000L
        /** See [WebRtcTuning.AUDIO_PACKET_MS]. */
        const val PACKETS_PER_SECOND = 1000 / WebRtcTuning.AUDIO_PACKET_MS
        /** IPv4, UDP, RTP with its usual extensions, and the SRTP tag. */
        const val HEADER_BYTES = 58
    }
}
