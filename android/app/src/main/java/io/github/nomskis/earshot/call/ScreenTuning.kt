package io.github.nomskis.earshot.call

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The choices behind a sharp, steady screen share (docs/research/screen-share.md), kept free
 * of Android and WebRTC types so they can be unit tested.
 */
object ScreenTuning {
    /** Capture no bigger than this on the short side: a phone's native 1080 on Wi-Fi, 720 on mobile data. */
    const val MAX_SHORT_SIDE_WIFI = 1080
    const val MAX_SHORT_SIDE_CELLULAR = 720

    /** The most a screen may use: plenty for sharp text, little enough to leave room for the voice. */
    const val MAX_KBPS_WIFI = 2_500
    const val MAX_KBPS_CELLULAR = 1_200

    /** Smooth scrolling; text needs resolution, not more frames than this. */
    const val MAX_FPS = 30

    /** Cloudflare's address for finding a way to its nearest site. */
    const val CLOUDFLARE_STUN = "stun:stun.cloudflare.com:3478"

    /**
     * Best for screens first: AV1 (palette mode and screen tuning in WebRTC's encoder), then
     * VP9 and VP8 (both with screen modes), then H.264, which blurs text at low rates. AV1 only
     * when the viewer can decode it and this phone can encode it fast enough in software.
     */
    fun codecOrder(av1: Boolean): List<String> = listOfNotNull("AV1".takeIf { av1 }, "VP9", "VP8", "H264")

    /** AV1 is encoded in software; a phone with eight cores and Android 12 or later keeps up with a screen. */
    fun encodesAv1(sdk: Int, cores: Int): Boolean = sdk >= 31 && cores >= 8

    /**
     * The size to capture a [width] by [height] screen at: scaled down so the short side is
     * at most [maxShortSide], and both sides even multiples of 8, which every encoder takes.
     */
    fun captureSize(width: Int, height: Int, maxShortSide: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val short = min(width, height)
        val scale = if (short > maxShortSide) maxShortSide.toDouble() / short else 1.0
        fun fit(side: Int) = max(16, ((side * scale) / 8).toInt() * 8)
        return fit(width) to fit(height)
    }

    /**
     * When to send the last frame again while the screen doesn't change ([idleMs] since a
     * new one). WebRTC's zero-hertz mode does this on desktop but never turns on on Android
     * (its video source sets no frame-rate constraints): a few repeats a second at first let
     * the encoder spend bits on sharpening what's there, then a slow one keeps the stream
     * alive, since Cloudflare drops a track that sends nothing for 30 seconds. Null: not yet.
     */
    fun repeatEveryMs(idleMs: Long): Long? = when {
        idleMs < SETTLE_MS -> null
        idleMs < SETTLE_MS + SHARPEN_MS -> SHARPEN_EVERY_MS
        else -> KEEPALIVE_EVERY_MS
    }

    /** A still screen for this long gets sharpened. */
    const val SETTLE_MS = 300L
    /** How long to sharpen for, and how often to send the frame while doing it. */
    const val SHARPEN_MS = 2_000L
    const val SHARPEN_EVERY_MS = 250L
    /** After that: a repeat this often, well inside Cloudflare's 30 seconds. */
    const val KEEPALIVE_EVERY_MS = 1_500L
}

/**
 * The viewer's side of pacing a screen: what arrives is measured every couple of seconds and
 * the sharer is asked for a lower or higher ceiling, so the weaker of the two links sets the
 * pace. Without this, a sharer on good Wi-Fi would send more than a viewer on mobile data can
 * take: Cloudflare forwards it all, and her link would lose packets until the picture froze.
 */
class ScreenPace(
    private val minKbps: Int = MIN_KBPS,
    private val maxKbps: Int = ScreenTuning.MAX_KBPS_WIFI,
) {
    /** Cumulative counters from the screen's inbound-rtp stats. */
    data class Counters(
        val bytesReceived: Double,
        val packetsReceived: Double,
        val packetsLost: Double,
        val freezeCount: Double = 0.0,
    )

    /** The ceiling to ask the sharer for; null = whatever they can send. */
    var capKbps: Int? = null
        private set

    private var last: Counters? = null
    private var lastAt = 0L
    private var calmSince = 0L
    private var heldUntil = 0L

    /** Feeds one sample; true when [capKbps] changed enough to tell the sharer. */
    fun update(counters: Counters, nowMs: Long): Boolean {
        val before = last
        val beforeAt = lastAt
        last = counters
        lastAt = nowMs
        if (before == null || nowMs <= beforeAt) {
            calmSince = nowMs
            return false
        }
        val seconds = (nowMs - beforeAt) / 1000.0
        val received = counters.packetsReceived - before.packetsReceived
        val lost = (counters.packetsLost - before.packetsLost).coerceAtLeast(0.0)
        // A reset (new connection) or a quiet moment says nothing.
        if (received < 0 || counters.bytesReceived < before.bytesReceived) {
            calmSince = nowMs
            return false
        }
        val kbps = (counters.bytesReceived - before.bytesReceived) * 8 / 1000 / seconds
        val lossFraction = if (received + lost > 0) lost / (received + lost) else 0.0
        val froze = counters.freezeCount > before.freezeCount
        val struggling = (lossFraction > LOSS_LIMIT && received + lost >= MIN_PACKETS) || froze
        val old = capKbps
        if (struggling) {
            calmSince = nowMs
            if (nowMs < heldUntil && old != null) return false
            // Just under what actually got through, so her link's queue can drain.
            val next = (kbps * BACK_OFF).roundToInt().coerceIn(minKbps, maxKbps)
            capKbps = if (old == null) next else min(old, next)
            heldUntil = nowMs + HOLD_MS
        } else if (old != null && nowMs - calmSince >= CALM_MS) {
            calmSince = nowMs
            // Only raise a ceiling that's being used; a still screen sends little whatever the cap.
            if (kbps >= old * BINDING) {
                val next = (old * RAISE).roundToInt()
                capKbps = if (next >= maxKbps) null else next
            }
        }
        return changedEnough(old, capKbps)
    }

    /** Back to "whatever they can send", for a new share. */
    fun reset() {
        capKbps = null
        last = null
        lastAt = 0L
        heldUntil = 0L
    }

    private fun changedEnough(old: Int?, new: Int?): Boolean = when {
        old == new -> false
        old == null || new == null -> true
        else -> kotlin.math.abs(new - old) >= old * 0.1
    }

    companion object {
        /** Below this a screen can't stay readable; it gets this at least. */
        const val MIN_KBPS = 150
        const val LOSS_LIMIT = 0.05
        const val MIN_PACKETS = 20.0
        const val BACK_OFF = 0.8
        /** After backing off, give the sharer time to slow down before judging again. */
        const val HOLD_MS = 6_000L
        /** Calm for this long before asking for more. */
        const val CALM_MS = 10_000L
        const val RAISE = 1.25
        /** A ceiling counts as in use when what arrives is at least this share of it. */
        const val BINDING = 0.7
    }
}
