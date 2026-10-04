package io.github.nomskis.earshot.messages

import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.signaling.Caller
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessengerTest {
    private val sam = "c2FtLWFkZHJlc3MtMDAwMD"
    private val stranger = "c3RyYW5nZXItYWRkcmVzc0"

    private val sent = mutableListOf<ClientMessage>()
    private val saved = mutableMapOf<String, Conversation>()
    private val notified = mutableListOf<Pair<String, String>>()
    private val newContacts = mutableListOf<Contact>()
    private var clock = 1_000L

    private val store = object : Messenger.Store {
        override fun loadAll() = saved.toMap()
        override fun save(conversation: Conversation) {
            saved[conversation.address] = conversation
        }
    }

    private fun messenger(): Messenger {
        val dispatcher = UnconfinedTestDispatcher()
        return Messenger(
            scope = CoroutineScope(dispatcher),
            store = store,
            send = { sent += it; true },
            myName = { "Salma" },
            contact = { address -> if (address == sam) Contact("Sam", sam) else null },
            saveContact = { newContacts += it },
            notify = { name, address, _ -> notified += name to address },
            now = { clock },
            io = dispatcher,
        )
    }

    private fun incoming(id: String, from: String = sam, text: String = "Landed!") =
        ServerMessage.Message(id = id, from = Caller("Sam", from), text = text, sentAt = 1)

    @Test
    fun ourMessageGoesOutWithOurNameAndIsKept() {
        val m = messenger()
        m.send(sam, "  On my way  ")
        val out = sent.single() as ClientMessage.Message
        assertEquals(sam, out.to)
        assertEquals("On my way", out.text)
        assertEquals("Salma", out.name)
        assertEquals(TextMessage.Status.SENDING, saved.getValue(sam).messages.single().status)

        m.onServerMessage(ServerMessage.MessageStatus(out.id, sam, "queued"))
        assertEquals(TextMessage.Status.WAITING, m.conversations.value.getValue(sam).messages.single().status)
        m.onServerMessage(ServerMessage.MessageStatus(out.id, sam, "delivered"))
        assertEquals(TextMessage.Status.DELIVERED, m.conversations.value.getValue(sam).messages.single().status)
    }

    @Test
    fun whatTheyHaventConfirmedGoesAgainAfterAReconnect() {
        val m = messenger()
        m.send(sam, "One")
        m.send(sam, "Two")
        val first = (sent[0] as ClientMessage.Message).id
        m.onServerMessage(ServerMessage.MessageStatus(first, sam, "delivered"))
        sent.clear()
        m.onServerMessage(ServerMessage.Listening("our-address"))
        assertEquals(listOf("Two"), sent.map { (it as ClientMessage.Message).text })
    }

    @Test
    fun theirMessageIsConfirmedKeptAndNotified() {
        val m = messenger()
        m.onServerMessage(incoming("m-00000001"))
        assertEquals(ClientMessage.MessageAck(to = sam, id = "m-00000001"), sent.single())
        assertEquals(listOf("Sam" to sam), notified)
        assertEquals(1, m.conversations.value.getValue(sam).unread)

        // The same one again (their phone sent it again): confirmed again, kept and notified once.
        m.onServerMessage(incoming("m-00000001"))
        assertEquals(2, sent.size)
        assertEquals(1, notified.size)
        assertEquals(1, m.conversations.value.getValue(sam).messages.size)
    }

    @Test
    fun anOpenConversationReadsAsItArrives() {
        val m = messenger()
        m.open = sam
        clock = 2_000
        m.onServerMessage(incoming("m-00000002"))
        assertTrue(notified.isEmpty())
        assertEquals(0, m.conversations.value.getValue(sam).unread)
    }

    @Test
    fun someoneNewIsKeptAsAContact() {
        val m = messenger()
        m.onServerMessage(incoming("m-00000003", from = stranger))
        assertEquals(stranger, newContacts.single().address)
        assertEquals("Sam", newContacts.single().name)
    }

    @Test
    fun aCallsChatJoinsTheConversation() {
        val m = messenger()
        m.recordCallChat(sam, "call-1", "Can you hear me?", mine = true, atMillis = 5)
        m.recordCallChat(sam, "call-2", "Yes!", mine = false, atMillis = 6)
        val messages = m.conversations.value.getValue(sam).messages
        assertEquals(listOf("Can you hear me?", "Yes!"), messages.map { it.text })
        // Delivered in the call, so never sent again through the server.
        assertTrue(m.conversations.value.getValue(sam).outbox.isEmpty())
    }
}
