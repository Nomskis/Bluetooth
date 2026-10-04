package io.github.nomskis.earshot.call

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shares what the connection can carry between the voice and the video,
 * voice first.
 *
 * WebRTC splits its bandwidth estimate between the two itself, but it only
 * counts the voice's codec bitrate and headers, not the three copies RED adds
 * ([WebRtcTuning.RED_REDUNDANCY]; audio_send_stream.cc registers the codec
 * rate with the bitrate allocator). HD voice is about 250 kbps on the wire,
 * WebRTC reserves about 100, and video is handed the difference on top of
 * what the connection has. On a fast connection that's noise. On one under
 * about 1.2 Mbps (a weak uplink in another country, say) the call sends more
 * than the estimate all the time: a standing queue, so delay and then loss,
 * for the voice too. So, each stats interval:
 *
 * - **Video gets what the voice really leaves**: capped at the estimate minus
 *   what the voice really sends, while the estimate is low enough to matter.
 * - **The voice steps down** (48, 32, then 20 kbps Opus, still clear speech)
 *   when the estimate can't carry it with room for some video. WebRTC sends
 *   audio at a fixed bitrate whatever its estimate says, so this is the only
 *   thing that ever makes it leaner.
 * - **Video pauses** when even the leanest voice would leave it less than a
 *   picture is worth, so the voice gets through. The other side is told why.
 * - **Coming back is a probe**: after a while video resumes, and the voice
 *   steps up; a step that doesn't hold makes the next try wait twice as long.
 *   That also works for voice-only calls, where WebRTC's estimate barely
 *   grows beyond what's being sent.
 */
class MediaBudget(
    private val redundancy: Int = WebRtcTuning.RED_REDUNDANCY,
    /** Where the voice starts: where it settled last time on this route ([LinkMemory]); it probes up as usual. */
    startLevel: Level = Level.FULL,
) {
    /** Opus bitrates. FULL is whatever the other side asked for, normally HD voice. */
    enum class Level(val opusBps: Int) { FULL(SdpTuning.HD_VOICE_BITRATE), REDUCED(32_000), LOW(20_000) }

    /** What changed in one interval, so the caller re-applies only that. */
    data class Changes(val voice: Boolean = false, val video: Boolean = false)

    var level = startLevel
        private set

    /** The Opus cap to apply to the audio sender; null leaves it as negotiated. */
    val voiceCapBps: Int? get() = if (level == Level.FULL) null else level.opusBps

    /** Video paused so the voice gets through. */
    var videoPaused = false
        private set

    /** The most video may send, bits per second; null = no cap from here. */
    var videoCapBps: Int? = null
        private set

    private var levelSince: Long? = null
    private var overSamples = 0
    private var holdMs = MIN_HOLD_MS
    /** When the voice last stepped up, until that step has held or failed. */
    private var probeAt: Long? = null

    private var starvedSamples = 0
    private var videoPausedAt = 0L
    private var videoHoldMs = MIN_HOLD_MS
    /** When video last came back, until that has held or failed. */
    private var videoProbeAt: Long? = null

    /**
     * Feeds one stats interval: the connection's send estimate (availableOutgoingBitrate),
     * what the voice really sent on the wire, in bits per second, and whether video is
     * meant to be on (camera on and not in a pocket).
     */
    fun update(availableBps: Double?, audioBps: Double?, wantsVideo: Boolean, nowMs: Long): Changes {
        if (levelSince == null) levelSince = nowMs
        var video = false
        if (!wantsVideo && (videoPaused || videoCapBps != null || videoProbeAt != null)) {
            // Nothing to share: a camera switched back on starts afresh.
            video = videoPaused || videoCapBps != null
            videoPaused = false
            videoCapBps = null
            videoProbeAt = null
            videoHoldMs = MIN_HOLD_MS
        }
        if (availableBps == null || audioBps == null || audioBps <= 0) return Changes(video = video)

        // Video first in line to go: even the leanest voice would leave it too little.
        val probing = videoProbeAt?.let { nowMs - it < PROBE_GRACE_MS } == true
        if (wantsVideo && !videoPaused && !probing && availableBps < (wireBps(Level.LOW) + VIDEO_FLOOR_BPS) * HEADROOM) {
            starvedSamples++
            if (starvedSamples >= OVER_SAMPLES) {
                // Back this soon after coming back: wait longer before the next try.
                videoProbeAt?.let { if (nowMs - it < FAILED_PROBE_MS) videoHoldMs = min(videoHoldMs * 2, MAX_HOLD_MS) }
                videoProbeAt = null
                videoPaused = true
                videoPausedAt = nowMs
                starvedSamples = 0
                video = true
            }
        } else {
            starvedSamples = 0
        }
        val videoOn = wantsVideo && !videoPaused

        val voice = updateVoice(availableBps, audioBps, videoOn, nowMs)

        if (videoOn) {
            videoProbeAt?.let { if (nowMs - it >= HELD_PROBE_MS) {
                videoHoldMs = MIN_HOLD_MS
                videoProbeAt = null
            } }
        } else if (videoPaused && wantsVideo && nowMs - videoPausedAt >= videoHoldMs) {
            videoPaused = false
            videoProbeAt = nowMs
            video = true
        }

        // What the voice really leaves, while the estimate is low enough for RED's copies to matter.
        val cap = if (wantsVideo && !videoPaused && availableBps < CAP_BELOW_BPS) {
            max(VIDEO_FLOOR_BPS, availableBps / HEADROOM - audioBps).toInt() / CAP_STEP_BPS * CAP_STEP_BPS
        } else {
            null
        }
        if (capMoved(videoCapBps, cap)) {
            videoCapBps = cap
            video = true
        }
        return Changes(voice = voice, video = video)
    }

    /** The voice's level for this interval; true when it changed. */
    private fun updateVoice(availableBps: Double, audioBps: Double, videoOn: Boolean, nowMs: Long): Boolean {
        val videoRoom = if (videoOn) VIDEO_ROOM_BPS else 0.0
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
        if (videoOn && availableBps < (wireBps(up) + videoRoom) * HEADROOM) return false
        probeAt = nowMs
        setLevel(up, nowMs)
        return true
    }

    /**
     * Voice packets we send per second: 100 at the usual 10 ms, fewer when the other side
     * asks for longer packets on a rough link ([PacketTime]).
     */
    var packetsPerSecond: Double = PACKETS_PER_SECOND.toDouble()

    /** Roughly what [level] costs on the wire: the voice and its copies, plus each packet's headers. */
    fun wireBps(level: Level): Double =
        (1 + redundancy) * level.opusBps.toDouble() + packetsPerSecond * (HEADER_BYTES + 4 * redundancy + 1) * 8

    private fun setLevel(next: Level, nowMs: Long) {
        level = next
        levelSince = nowMs
        overSamples = 0
    }

    /** Small moves aren't worth a new encoder setting every two seconds. */
    private fun capMoved(old: Int?, new: Int?): Boolean = when {
        old == null || new == null -> old != new
        else -> abs(new - old) >= max(old / 10, CAP_STEP_BPS)
    }

    companion object {
        /** What we send should fit the estimate with this much to spare. */
        const val HEADROOM = 1.1
        /** Kept free for video when it's on, so it isn't squeezed to nothing. */
        const val VIDEO_ROOM_BPS = 100_000.0
        /** Less than this for video next to the leanest voice, and video pauses. */
        const val VIDEO_FLOOR_BPS = 60_000.0
        /** Above this the copies WebRTC doesn't count are a small share, and video isn't capped. */
        const val CAP_BELOW_BPS = 1_500_000.0
        const val CAP_STEP_BPS = 10_000
        /** Consecutive over-budget intervals (2 s each) before stepping down or pausing. */
        const val OVER_SAMPLES = 2
        const val MIN_HOLD_MS = 20_000L
        const val MAX_HOLD_MS = 160_000L
        /** A step up undone this soon counts as failed. */
        const val FAILED_PROBE_MS = 30_000L
        /** A step up that lasted this long has held. */
        const val HELD_PROBE_MS = 60_000L
        /** Video that just came back isn't judged until the estimate has had time to grow with it. */
        const val PROBE_GRACE_MS = 6_000L
        /** 10 ms packets. */
        const val PACKETS_PER_SECOND = 100
        /** IPv4, UDP, RTP with its usual extensions, and the SRTP tag. */
        const val HEADER_BYTES = 58
        /** What each packet costs below RTP, which WebRTC's byte counters leave out: IPv4, UDP, SRTP tag. */
        const val TRANSPORT_OVERHEAD_BYTES = 38
    }
}
