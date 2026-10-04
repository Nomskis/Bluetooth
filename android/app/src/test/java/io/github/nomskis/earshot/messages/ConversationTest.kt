package io.github.nomskis.earshot.messages

import io.github.nomskis.earshot.messages.TextMessage.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTest {
    private val empty = Conversation("c2FtLWFkZHJlc3MtMDAwMD")

    @Test
    fun ourMessageMovesForwardUntilDelivered() {
        var c = empty.sending("m1", "Landed!", 1_000)
        assertEquals(listOf("m1"), c.outbox.map { it.id })
        c = c.status("m1", Status.WAITING).status("m1", Status.DELIVERED)
        // A late "sent" doesn't undo "delivered".
        c = c.status("m1", Status.SENT)
        assertEquals(Status.DELIVERED, c.messages.single().status)
        assertEquals(emptyList<TextMessage>(), c.outbox)
    }

    @Test
    fun theirMessageSentAgainIsKeptOnce() {
        val c = empty.received("m1", "Hi", 1_000).received("m1", "Hi", 1_000)
        assertEquals(1, c.messages.size)
        assertEquals(1, c.unread)
        assertEquals(0, c.read(2_000).unread)
    }

    @Test
    fun ourIdsAndTheirsDontClash() {
        // Each side makes its own ids, so the same id can be one of ours and one of theirs.
        val c = empty.sending("m1", "Mine", 1_000).received("m1", "Theirs", 1_001)
        assertEquals(2, c.messages.size)
    }

    @Test
    fun aLongConversationKeepsTheNewest() {
        var c = empty
        repeat(Conversation.MAX_MESSAGES + 5) { i -> c = c.received("m$i", "#$i", i.toLong()) }
        assertEquals(Conversation.MAX_MESSAGES, c.messages.size)
        assertEquals("#5", c.messages.first().text)
    }

    @Test
    fun serverWordsMapToStatuses() {
        assertEquals(Status.WAITING, Conversation.statusOf("queued"))
        assertEquals(Status.SENT, Conversation.statusOf("sent"))
        assertEquals(Status.DELIVERED, Conversation.statusOf("delivered"))
        assertEquals(null, Conversation.statusOf("weird"))
    }
}
