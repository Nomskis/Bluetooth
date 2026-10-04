package io.github.nomskis.earshot.calls

import io.github.nomskis.earshot.calls.CallRecord.Direction
import io.github.nomskis.earshot.calls.CallRecord.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogTest {
    private val salma = Contact("Salma", "1D8ANuTJStR4AyHh0kwUw6")

    private fun ended(
        calling: Contact? = salma,
        answering: IncomingRing? = null,
        status: OutgoingRing.Status? = OutgoingRing.Status.ANSWERED,
        connectedForMs: Long? = 754_000,
        peerName: String? = "Salma",
    ) = CallRecords.ended(calling, answering, "call-x", peerName, null, status, connectedForMs, video = true, startedAtMillis = 1_000)

    @Test
    fun aCallYouTalkedOnRecordsHowLong() {
        val record = ended()!!
        assertEquals(Direction.OUTGOING, record.direction)
        assertEquals(Outcome.ANSWERED, record.outcome)
        assertEquals(754, record.durationSeconds)
        assertEquals("12 min", record.summary)
        assertEquals(salma.address, record.address)
    }

    @Test
    fun anOutgoingCallThatDidntHappenSaysWhy() {
        assertEquals(Outcome.DECLINED, ended(status = OutgoingRing.Status.DECLINED, connectedForMs = null)!!.outcome)
        assertEquals(Outcome.BUSY, ended(status = OutgoingRing.Status.BUSY, connectedForMs = null)!!.outcome)
        assertEquals(Outcome.NO_ANSWER, ended(status = OutgoingRing.Status.NO_ANSWER, connectedForMs = null)!!.outcome)
        assertEquals(Outcome.UNREACHABLE, ended(status = OutgoingRing.Status.UNREACHABLE, connectedForMs = null)!!.outcome)
        assertEquals(Outcome.CANCELLED, ended(status = OutgoingRing.Status.RINGING, connectedForMs = null)!!.outcome)
        assertEquals(Outcome.FAILED, ended(status = OutgoingRing.Status.ANSWERED, connectedForMs = null)!!.outcome)
    }

    @Test
    fun anAnsweredIncomingCallIsIncomingWithTheCallersAddress() {
        val ring = IncomingRing("r-1", "call-x", "Salma", salma.address, video = false)
        val record = ended(calling = null, answering = ring, status = null, connectedForMs = 59_000)!!
        assertEquals(Direction.INCOMING, record.direction)
        assertEquals("59 sec", record.summary)
        assertEquals(salma.address, record.address)
    }

    @Test
    fun aRoomNobodyJoinedIsntACall() {
        assertNull(ended(calling = null, status = null, connectedForMs = null, peerName = null))
        val joined = ended(calling = null, status = null, connectedForMs = 5_000, peerName = null)!!
        assertEquals("Room call-x", joined.name)
    }

    @Test
    fun missedCallsAreRed() {
        val ring = IncomingRing("r-1", "call-x", "Salma", salma.address, video = true)
        assertTrue(CallRecords.notTaken(ring, Outcome.MISSED, 5).missed)
        assertTrue(!CallRecords.notTaken(ring, Outcome.DECLINED, 5).missed)
    }

    @Test
    fun theLogIsNewestFirstAndBounded() {
        var log = emptyList<CallRecord>()
        repeat(CallLog.MAX + 5) { i ->
            log = CallLog.add(log, CallRecord("n$i", null, Direction.OUTGOING, Outcome.ANSWERED, false, atMillis = i.toLong()))
        }
        assertEquals(CallLog.MAX, log.size)
        assertEquals("n${CallLog.MAX + 4}", log.first().name)
        assertEquals(log, CallLog.decode(CallLog.encode(log)))
    }

    @Test
    fun durationsReadLikeAPhone() {
        assertEquals("0 sec", CallRecord.durationText(0))
        assertEquals("1 min", CallRecord.durationText(60))
        assertEquals("1 hr", CallRecord.durationText(3_600))
        assertEquals("1 hr 5 min", CallRecord.durationText(3_900))
    }

    @Test
    fun aNameYouGaveSomeoneSticks() {
        var contacts = Contacts.upsert(emptyList(), salma)
        contacts = Contacts.rename(contacts, salma.address, "Salma ❤️")
        // Their phone sends its own name on the next call; yours stays.
        contacts = Contacts.upsert(contacts, salma.copy(name = "salma.b", lastCallAtMillis = 99))
        assertEquals("Salma ❤️", contacts.single().name)
        assertEquals(99, contacts.single().lastCallAtMillis)
        assertTrue(contacts.single().renamed)
    }
}
