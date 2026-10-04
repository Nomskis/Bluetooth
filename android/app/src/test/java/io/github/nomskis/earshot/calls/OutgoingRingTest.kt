package io.github.nomskis.earshot.calls

import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingRingTest {
    private val salma = Contact("Salma", "1D8ANuTJStR4AyHh0kwUw6")
    private var next = 0
    private fun newRing(video: Boolean = true) = OutgoingRing(salma, "Sam", "sam-secret-inbox-key-0001", video) { "r-${++next}" }

    @Test
    fun ringsWithWhoWeAreAndTheRoom() {
        val ring = newRing()
        assertEquals(
            ClientMessage.Ring(to = salma.address, ringId = "r-1", room = "call-abc", name = "Sam", video = true, inbox = "sam-secret-inbox-key-0001"),
            ring.ring("call-abc"),
        )
        assertEquals(OutgoingRing.Status.CALLING, ring.status)
    }

    @Test
    fun ringingThenAnswered() {
        val ring = newRing()
        ring.ring("call-abc")
        assertTrue(ring.onMessage(ServerMessage.RingStatus("r-1", "ringing", devices = 1)))
        assertEquals(OutgoingRing.Status.RINGING, ring.status)
        assertTrue(ring.onMessage(ServerMessage.RingAnswered("r-1", accepted = true)))
        assertEquals(OutgoingRing.Status.ANSWERED, ring.status)
        // Once answered there's nothing left to ring or cancel.
        assertNull(ring.ring("call-abc"))
        assertNull(ring.hangUp())
        assertNull(ring.timeOut())
        assertFalse(ring.gaveUp)
    }

    @Test
    fun declinedBusyAndNoAnswerEndTheCall() {
        for ((reason, status) in listOf("declined" to OutgoingRing.Status.DECLINED, "busy" to OutgoingRing.Status.BUSY, "no-answer" to OutgoingRing.Status.NO_ANSWER)) {
            val ring = newRing()
            val id = ring.ring("call-abc")!!.ringId
            ring.onMessage(ServerMessage.RingStatus(id, "ringing", devices = 1))
            ring.onMessage(ServerMessage.RingAnswered(id, accepted = false, reason = reason))
            assertEquals(status, ring.status)
            assertTrue(ring.gaveUp)
            // A late "ringing" doesn't bring it back.
            assertFalse(ring.onMessage(ServerMessage.RingStatus(id, "ringing", devices = 1)))
        }
    }

    @Test
    fun messagesForAnOlderRingAreIgnored() {
        val ring = newRing()
        ring.ring("call-abc") // r-1, lost with a reconnect
        ring.ring("call-abc") // r-2
        assertFalse(ring.onMessage(ServerMessage.RingAnswered("r-1", accepted = false, reason = "no-answer")))
        assertEquals(OutgoingRing.Status.CALLING, ring.status)
        assertTrue(ring.onMessage(ServerMessage.RingStatus("r-2", "ringing", devices = 2)))
    }

    @Test
    fun anUnreachablePhoneIsRetriedUntilTheMinuteIsUp() {
        val ring = newRing()
        ring.ring("call-abc")
        ring.onMessage(ServerMessage.RingStatus("r-1", "unreachable"))
        assertEquals(OutgoingRing.Status.UNREACHABLE, ring.status)
        assertTrue(ring.keepsTrying)
        assertFalse(ring.gaveUp) // the room stays open for the invite link

        // Their phone comes back online and the retry rings it.
        assertEquals("r-2", ring.ring("call-abc")?.ringId)
        ring.onMessage(ServerMessage.RingStatus("r-2", "ringing", devices = 1))
        assertEquals(OutgoingRing.Status.RINGING, ring.status)
        assertFalse(ring.keepsTrying)
    }

    @Test
    fun afterTheMinuteAnUnreachablePhoneIsLeftAlone() {
        val ring = newRing()
        ring.ring("call-abc")
        ring.onMessage(ServerMessage.RingStatus("r-1", "unreachable"))
        assertNull(ring.timeOut()) // nothing ringing to cancel
        assertEquals(OutgoingRing.Status.UNREACHABLE, ring.status)
        assertFalse(ring.keepsTrying)
        assertNull(ring.ring("call-abc"))
    }

    @Test
    fun timingOutWhileRingingCancelsTheRing() {
        val ring = newRing()
        ring.ring("call-abc")
        ring.onMessage(ServerMessage.RingStatus("r-1", "ringing", devices = 1))
        assertEquals(ClientMessage.RingCancel("r-1"), ring.timeOut())
        assertEquals(OutgoingRing.Status.NO_ANSWER, ring.status)
        assertTrue(ring.gaveUp)
    }

    @Test
    fun hangingUpWhileRingingStopsTheirPhone() {
        val ring = newRing()
        assertNull(ring.hangUp()) // nothing rung yet
        ring.ring("call-abc")
        ring.onMessage(ServerMessage.RingStatus("r-1", "ringing", devices = 1))
        assertEquals(ClientMessage.RingCancel("r-1"), ring.hangUp())
    }

    @Test
    fun aServerThatCantRingLeavesTheInviteLink() {
        val ring = newRing()
        ring.ring("call-abc")
        assertTrue(ring.refused())
        assertEquals(OutgoingRing.Status.UNREACHABLE, ring.status)
        assertFalse(ring.keepsTrying)
        // Only straight after ringing; a stray error later changes nothing.
        assertFalse(ring.refused())
    }

    @Test
    fun theyJoiningMeansAnswered() {
        val ring = newRing()
        ring.ring("call-abc")
        assertFalse(ring.joined)
        ring.onJoined()
        assertEquals(OutgoingRing.Status.ANSWERED, ring.status)
        assertTrue(ring.joined)
        assertNull(ring.outcome)
    }

    @Test
    fun theHomeScreenIsToldWhyTheCallEnded() {
        val ring = newRing()
        ring.ring("call-abc")
        ring.onMessage(ServerMessage.RingAnswered("r-1", accepted = false, reason = "declined"))
        assertEquals("Salma declined the call.", ring.outcome)
    }

    @Test
    fun aliveWhileStillTryingToReachThem() {
        val ring = newRing()
        assertTrue(ring.alive) // before the first ring even goes out
        ring.ring("call-abc")
        ring.onMessage(ServerMessage.RingStatus("r-1", "unreachable"))
        assertTrue(ring.alive)
        ring.timeOut()
        assertFalse(ring.alive)

        val answered = newRing()
        answered.ring("call-abc")
        answered.onJoined()
        assertFalse(answered.alive)

        val declined = newRing()
        declined.ring("call-abc")
        declined.onMessage(ServerMessage.RingAnswered("r-${next}", accepted = false, reason = "declined"))
        assertFalse(declined.alive)
    }

    @Test
    fun aCallBackIsOnlyActedOnStraightAway() {
        val request = CallBackRequest(salma, video = false, atMillis = 1_000_000)
        assertTrue(request.isFresh(1_000_000 + 5_000))
        assertFalse(request.isFresh(1_000_000 + CallBackRequest.FRESH_MS + 1))
        assertFalse(request.isFresh(999_000))
        assertNotNull(request.contact)
    }
}
