package io.github.nomskis.earshot.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerHealthTest {
    @Test
    fun readsTheHealthAnswer() {
        assertEquals(ServerHealth.Answer(ok = true, relay = true), ServerHealth.parse("""{"status":"ok","relay":true}"""))
        assertEquals(ServerHealth.Answer(ok = true, relay = false), ServerHealth.parse("""{"status": "ok", "relay": false}"""))
        // A server from before relays were reported.
        assertEquals(ServerHealth.Answer(ok = true, relay = null), ServerHealth.parse("""{"status":"ok"}"""))
        assertFalse(ServerHealth.parse("<html>Not found</html>").ok)
    }

    @Test
    fun saysWhenTheServerIsFarAndWhenThereIsNoRelay() {
        val far = ServerHealth.notes(roundTripMs = 340, relay = false)
        assertTrue(far[0].contains("nearer"))
        assertTrue(far[1].startsWith("No relay"))
        val near = ServerHealth.notes(roundTripMs = 42, relay = true)
        assertEquals("42 ms away.", near[0])
        assertTrue(near[1].startsWith("Relay ready"))
        assertTrue(ServerHealth.notes(null, null).isEmpty())
    }

    @Test
    fun theThresholdIsWhereSetupStartsToDrag() {
        assertNull(ServerHealth.notes(ServerHealth.FAR_MS - 1, null).firstOrNull { it.contains("nearer") })
        assertTrue(ServerHealth.notes(ServerHealth.FAR_MS, null).first().contains("nearer"))
    }
}
