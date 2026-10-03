package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Same cases as the web client's chat tests (server/test/web-client.test.js), so both sides agree. */
class ChatLogTest {
    private var n = 0
    private val log = ChatLog(newId = { "m-${++n}" }, now = { 1000L })

    @Test
    fun queuedUntilTheChannelOpensThenDeliveredOnAcknowledgement() {
        assertNull(log.send("   "))
        log.send("  One sec ")
        val wire = mutableListOf<String>()
        log.attach { wire += it; true }
        assertEquals(listOf("""{"kind":"chat","id":"m-1","text":"One sec","sentAt":1000}"""), wire)
        assertEquals(ChatMessage.Status.SENDING, log.messages.single().status)
        log.receive("""{"kind":"chat-ack","id":"m-1"}""")
        assertEquals(ChatMessage.Status.DELIVERED, log.messages.single().status)
    }

    @Test
    fun unacknowledgedMessagesGoAgainAndRepeatsAreDropped() {
        log.attach { true }
        log.send("Can you hear me?")
        log.detach()
        val wire = mutableListOf<Chat.Frame>()
        log.attach { wire += Chat.decode(it)!!; true }
        assertEquals(listOf<Chat.Frame>(Chat.Frame.Message("m-1", "Can you hear me?", 1000)), wire)

        val first = log.receive("""{"kind":"chat","id":"x-1","text":"Yes!","sentAt":5}""")
        val repeat = log.receive("""{"kind":"chat","id":"x-1","text":"Yes!","sentAt":5}""")
        assertEquals("Yes!", first?.text)
        assertNull(repeat)
        // Both copies are acknowledged: the first acknowledgement may have been lost.
        assertEquals(2, wire.count { it == Chat.Frame.Ack("x-1") })
        assertEquals(1, log.messages.count { !it.mine })
    }

    @Test
    fun aDifferentPersonMeansUndeliveredMessagesFailed() {
        log.send("hello?")
        log.peerChanged()
        assertEquals(ChatMessage.Status.FAILED, log.messages.single().status)
    }

    @Test
    fun decoderAcceptsTheSharedFixturesAndRejectsJunk() {
        val dir = File(checkNotNull(System.getProperty("earshot.fixtures")), "peer")
        val files = dir.listFiles { f -> f.extension == "json" }!!
        assertTrue(files.size >= 2)
        for (file in files) {
            val frame = Chat.decode(file.readText())
            val kind = when (frame) {
                is Chat.Frame.Message -> "chat"
                is Chat.Frame.Ack -> "chat-ack"
                null -> null
            }
            assertTrue(file.name, file.readText().contains("\"kind\": \"$kind\""))
        }
        assertNull(Chat.decode("nope"))
        assertNull(Chat.decode("""{"kind":"chat","id":"a","text":"  "}"""))
        assertNull(Chat.decode("""{"kind":"chat","text":"no id"}"""))
        assertNull(Chat.decode("""{"kind":"typing","id":"a"}"""))
        // What the web client sends: JSON.stringify keeps every field.
        assertEquals(Chat.Frame.Message("a", "hi", null), Chat.decode("""{"kind":"chat","id":"a","text":"hi","sentAt":null}"""))
        val long = Chat.decode("""{"kind":"chat","id":"a","text":"${"x".repeat(5000)}"}""") as Chat.Frame.Message
        assertEquals(Chat.MAX_LENGTH, long.text.length)
    }
}
