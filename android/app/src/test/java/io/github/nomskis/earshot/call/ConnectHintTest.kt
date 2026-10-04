package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.signaling.IceServerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectHintTest {
    private val stun = IceServerConfig(listOf("stun:stun.l.google.com:19302"))
    private val turn = IceServerConfig(listOf("turn:calls.example.com:3478?transport=udp"), "u", "p")

    @Test
    fun withoutARelayItPointsAtTurn() {
        assertFalse(ConnectHint.hasRelay(listOf(stun)))
        assertTrue(ConnectHint.forStuck(listOf(stun)).contains("no relay"))
        assertTrue(ConnectHint.hasRelay(listOf(stun, turn)))
        assertFalse(ConnectHint.forStuck(listOf(stun, turn)).contains("no relay"))
        assertTrue(ConnectHint.hasRelay(listOf(IceServerConfig(listOf("turns:relay.example.com:443?transport=tcp")))))
    }
}
