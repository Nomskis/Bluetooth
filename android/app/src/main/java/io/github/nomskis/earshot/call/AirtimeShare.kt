package io.github.nomskis.earshot.call

/**
 * Gives the other side's Wi-Fi back the airtime their voice needs.
 *
 * Wi-Fi is half-duplex: one shared channel, taking turns. The video we send
 * them comes down through their access point on the same airtime their
 * phone needs to get its own voice and video up. On a weak or busy Wi-Fi
 * (far from the router, the whole family online) our downstream can be what
 * starves their upstream, and neither side's congestion control can see it:
 * each only watches its own direction. Each side tells the other what its
 * uplink is going through (`uplink` and `network` in media-state, from its
 * [MediaBudget] and the reports on what it sends). When theirs is Wi-Fi and
 * struggling, we send them lighter video, and keep it lighter only while it
 * helps:
 *
 * - their uplink tight for a few seconds: our video to them capped at
 *   [TIGHT_KBPS], starved: [STARVED_KBPS];
 * - if their uplink recovers, the cap stays until it has been fine for
 *   [RELEASE_AFTER_MS], then lifts;
 * - if it hasn't got any better within [TRIAL_MS], our downstream isn't their
 *   problem: the cap lifts and isn't tried again for [BACKOFF_MS].
 *
 * Mobile data has separate up and down channels, so it never applies there.
 */
class AirtimeShare {
    /** Cap for our video to them, kbps; null = none from here. */
    var capKbps: Int? = null
        private set

    private var troubleSince: Long? = null
    private var helpingSince: Long? = null
    /** The worst their uplink has been since we stepped in. */
    private var worst: String? = null
    private var helped = false
    private var clearSince: Long? = null
    private var backoffUntil = 0L

    /** Feeds what they last said about their network and uplink; true when [capKbps] changed. */
    fun update(theirNetwork: String?, theirUplink: String?, nowMs: Long): Boolean {
        val before = capKbps
        val struggling = theirUplink == TIGHT || theirUplink == STARVED
        if (theirNetwork != WIFI) {
            reset()
        } else if (helpingSince == null) {
            troubleSince = if (struggling) troubleSince ?: nowMs else null
            if (struggling && nowMs >= backoffUntil && nowMs - troubleSince!! >= ENGAGE_MS) {
                helpingSince = nowMs
                worst = theirUplink
                helped = false
                clearSince = null
                capKbps = capFor(theirUplink)
            }
        } else if (struggling) {
            clearSince = null
            if (theirUplink == STARVED) worst = STARVED
            // Starved to tight is a step back up: it's working.
            if (worst == STARVED && theirUplink == TIGHT) helped = true
            capKbps = minOf(capKbps ?: Int.MAX_VALUE, capFor(theirUplink))
            if (!helped && nowMs - helpingSince!! >= TRIAL_MS) {
                // Lighter video from us didn't free them: it isn't our downstream.
                reset()
                backoffUntil = nowMs + BACKOFF_MS
            }
        } else {
            helped = true
            clearSince = clearSince ?: nowMs
            if (nowMs - clearSince!! >= RELEASE_AFTER_MS) reset()
        }
        return capKbps != before
    }

    private fun reset() {
        capKbps = null
        troubleSince = null
        helpingSince = null
        worst = null
        helped = false
        clearSince = null
    }

    private fun capFor(uplink: String?): Int = if (uplink == STARVED) STARVED_KBPS else TIGHT_KBPS

    companion object {
        const val WIFI = "wifi"
        const val TIGHT = "tight"
        const val STARVED = "starved"
        const val TIGHT_KBPS = 800
        const val STARVED_KBPS = 400
        /** Their uplink struggling this long before we step in: not for a blip. */
        const val ENGAGE_MS = 4_000L
        /** Their side takes 20 s or so to probe its voice and video back up. */
        const val TRIAL_MS = 45_000L
        const val RELEASE_AFTER_MS = 60_000L
        const val BACKOFF_MS = 5 * 60_000L

        /** What we tell them about our own uplink: how hard [MediaBudget] squeezed, or what they report losing. */
        fun uplinkOf(squeeze: LinkQuality?, sendLossPercent: Double?): String? {
            val loss = sendLossPercent ?: 0.0
            return when {
                squeeze == LinkQuality.POOR || loss >= DelayBreakdown.WEAK_LOSS_PERCENT -> STARVED
                squeeze == LinkQuality.FAIR || loss >= DelayBreakdown.FAIR_LOSS_PERCENT -> TIGHT
                else -> null
            }
        }
    }
}
