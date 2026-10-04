package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.signaling.Capabilities
import io.github.nomskis.earshot.signaling.IceServerConfig
import io.github.nomskis.earshot.signaling.PeerInfo

/**
 * The call through the server's TURN relay instead of a direct path, both
 * ends, when either side asks for it (Settings, "Calls abroad").
 *
 * A direct path between two countries takes whatever route the two internet
 * providers' transit gives it, which on a busy evening can lose or delay
 * packets in the middle. Cloudflare's relay is anycast: each phone reaches
 * the Cloudflare city nearest to it, and when both ends relay through it,
 * the stretch between those two cities can go over Cloudflare's own backbone
 * (developers.cloudflare.com/realtime/turn). Whether that beats the direct
 * route depends on the day and the providers, so it's a switch to try, with
 * the delay readout to compare.
 *
 * Each side turns it on for itself when it asked or the other side's join
 * lists [Capabilities.RELAY_ROUTE], so both ends relay. A connection that
 * doesn't come up through the relay within [FALLBACK_MS] goes direct for the
 * rest of the call: a relay that's down must never stop a call.
 */
object RelayRoute {
    const val FALLBACK_MS = 12_000L

    fun use(asked: Boolean, remote: PeerInfo?, iceServers: List<IceServerConfig>, failed: Boolean): Boolean =
        !failed && ConnectHint.hasRelay(iceServers) &&
            (asked || remote?.client?.capabilities?.contains(Capabilities.RELAY_ROUTE) == true)
}
