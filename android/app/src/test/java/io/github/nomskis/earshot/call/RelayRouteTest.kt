package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.signaling.Capabilities
import io.github.nomskis.earshot.signaling.ClientInfo
import io.github.nomskis.earshot.signaling.IceServerConfig
import io.github.nomskis.earshot.signaling.PeerInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRouteTest {
    private val stunOnly = listOf(IceServerConfig(listOf("stun:stun.l.google.com:19302")))
    private val withTurn = stunOnly + IceServerConfig(listOf("turn:turn.cloudflare.com:3478?transport=udp"), "u", "p")
    private val plain = PeerInfo(peerId = "p1", seq = 1, client = ClientInfo("android", "0.1.0", listOf("hifi-audio")))
    private val asking = plain.copy(client = plain.client.copy(capabilities = listOf("hifi-audio", Capabilities.RELAY_ROUTE)))

    @Test
    fun offUnlessOneSideAsks() {
        assertFalse(RelayRoute.use(asked = false, remote = plain, iceServers = withTurn, failed = false))
        assertFalse(RelayRoute.use(asked = false, remote = null, iceServers = withTurn, failed = false))
    }

    @Test
    fun eitherSideAskingPutsBothEndsThroughTheRelay() {
        assertTrue(RelayRoute.use(asked = true, remote = plain, iceServers = withTurn, failed = false))
        assertTrue(RelayRoute.use(asked = false, remote = asking, iceServers = withTurn, failed = false))
    }

    @Test
    fun neverWithoutARelayOrAfterItFailed() {
        // Relay-only with no relay would mean no way to connect at all.
        assertFalse(RelayRoute.use(asked = true, remote = asking, iceServers = stunOnly, failed = false))
        assertFalse(RelayRoute.use(asked = true, remote = asking, iceServers = withTurn, failed = true))
    }
}
