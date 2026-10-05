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
    private val blocked = mutableSetOf<String>()
    private var clock = 1_000L
    private val files = mutableMapOf<String, ByteArray>()
    private var myVersion = 0L
    private var myPhoto: ByteArray? = null
    private val sentTo = mutableMapOf<String, Long>()
    private val theirs = mutableMapOf<String, ByteArray?>()
    private val profileFiles = object : Messenger.ProfileFiles {
        override val myVersion get() = this@MessengerTest.myVersion
        override val hasMyPhoto get() = myPhoto != null
        override fun myPhoto() = this@MessengerTest.myPhoto
        override fun sentVersion(address: String) = sentTo[address] ?: 0L
        override fun markSent(address: String, version: Long) {
            sentTo[address] = version
        }
        override fun saveTheirs(address: String, jpeg: ByteArray?) {
            theirs[address] = jpeg
        }
    }
    private val photoFiles = object : Messenger.PhotoFiles {
        override fun read(name: String) = files[name]
        override fun save(bytes: ByteArray): String = "p${files.size + 1}.jpg".also { files[it] = bytes }
        override fun delete(names: Collection<String>) {
            names.forEach(files::remove)
        }
    }

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
            isBlocked = { it in blocked },
            notify = { name, address, _ -> notified += name to address },
            now = { clock },
            io = dispatcher,
            photos = photoFiles,
            profiles = profileFiles,
            contacts = { listOf(sam, stranger) },
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
    fun anOpenConversationReadsAsItArrivesAndTellsThem() {
        val m = messenger()
        m.open = sam
        // Nothing of theirs yet: nothing to say.
        assertTrue(sent.isEmpty())
        clock = 2_000
        m.onServerMessage(incoming("m-00000002"))
        assertTrue(notified.isEmpty())
        assertEquals(0, m.conversations.value.getValue(sam).unread)
        assertEquals(ClientMessage.MessageRead(to = sam, id = "m-00000002"), sent.last())
    }

    @Test
    fun openingAConversationTellsThemTheirsWereSeen() {
        val m = messenger()
        m.onServerMessage(incoming("m-00000004"))
        m.onServerMessage(incoming("m-00000005", text = "Call me"))
        sent.clear()
        m.open = sam
        assertEquals(listOf(ClientMessage.MessageRead(to = sam, id = "m-00000005")), sent)
    }

    @Test
    fun oursShowSeenWhenTheyveReadThem() {
        val m = messenger()
        m.send(sam, "One")
        m.send(sam, "Two")
        val (one, two) = sent.map { (it as ClientMessage.Message).id }
        m.onServerMessage(ServerMessage.MessageStatus(one, sam, "delivered"))
        m.onServerMessage(ServerMessage.MessageStatus(two, sam, "delivered"))
        m.onServerMessage(ServerMessage.MessageStatus(two, sam, "read"))
        assertEquals(listOf(TextMessage.Status.READ, TextMessage.Status.READ), m.conversations.value.getValue(sam).messages.map { it.status })
    }

    @Test
    fun someoneBlockedIsConfirmedButNotKeptOrNotified() {
        blocked += stranger
        val m = messenger()
        m.onServerMessage(incoming("m-00000006", from = stranger))
        // Confirmed, so the server stops handing it over.
        assertEquals(ClientMessage.MessageAck(to = stranger, id = "m-00000006"), sent.single())
        assertTrue(notified.isEmpty())
        assertTrue(newContacts.isEmpty())
        assertEquals(null, m.conversations.value[stranger])
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

    @Test
    fun anAnswerCarriesWhatItAnswersAndTheirsAreReadFromOurSide() {
        val m = messenger()
        m.onServerMessage(incoming("m-them-0001"))
        sent.clear()
        m.send(sam, "Welcome home", reply = Quote("m-them-0001", mine = false, text = "Landed!"))
        val out = sent.single() as ClientMessage.Message
        // From our side, the quoted one is "you": theirs.
        assertEquals(io.github.nomskis.earshot.signaling.WireReply("m-them-0001", sender = "you", text = "Landed!"), out.reply)

        // Their answer to ours: "you" is us.
        val mine = out.id
        m.onServerMessage(
            ServerMessage.Message(
                id = "m-them-0002",
                from = Caller("Sam", sam),
                text = "Thanks!",
                reply = io.github.nomskis.earshot.signaling.WireReply(mine, sender = "you", text = "Welcome home"),
            ),
        )
        val theirs = m.conversations.value.getValue(sam).messages.last()
        assertEquals(Quote(mine, mine = true, text = "Welcome home"), theirs.reply)
    }

    @Test
    fun deletedForEveryoneUntilTheirPhoneConfirmsIt() {
        val m = messenger()
        m.send(sam, "Wrong chat")
        val id = (sent.single() as ClientMessage.Message).id
        sent.clear()
        m.deleteForEveryone(sam, id)
        val unsend = sent.single() as ClientMessage.Message
        assertEquals(id, unsend.unsend)
        assertEquals(null, unsend.text)
        val kept = m.conversations.value.getValue(sam)
        assertTrue(kept.messages.single().deleted)
        // Neither the message nor its text goes out again; the withdrawal does, until confirmed.
        sent.clear()
        m.onServerMessage(ServerMessage.Listening("our-address"))
        assertEquals(listOf(id), sent.map { (it as ClientMessage.Message).unsend })
        m.onServerMessage(ServerMessage.MessageStatus(unsend.id, sam, "delivered"))
        sent.clear()
        m.onServerMessage(ServerMessage.Listening("our-address"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anOldMessageCantBeDeletedForEveryone() {
        val m = messenger()
        m.send(sam, "Last week")
        val id = (sent.single() as ClientMessage.Message).id
        sent.clear()
        clock += Conversation.UNSEND_WINDOW_MS
        m.deleteForEveryone(sam, id)
        assertTrue(sent.isEmpty())
        assertEquals("Last week", m.conversations.value.getValue(sam).messages.single().text)
    }

    @Test
    fun theirsDeletedForEveryoneLeavesOnlyThatItWas() {
        val m = messenger()
        m.onServerMessage(incoming("m-them-0001", text = "Oops, not for you"))
        sent.clear()
        m.onServerMessage(ServerMessage.Message(id = "x-m-them-0001", from = Caller("Sam", sam), unsend = "m-them-0001"))
        // Confirmed, so their phone stops sending it.
        assertEquals(ClientMessage.MessageAck(to = sam, id = "x-m-them-0001"), sent.single())
        val message = m.conversations.value.getValue(sam).messages.single()
        assertTrue(message.deleted)
        assertEquals("", message.text)
        assertEquals(0, m.conversations.value.getValue(sam).unread)
    }

    @Test
    fun aPictureGoesAsItsFileWithItsWordsAndIsKept() {
        val m = messenger()
        files["mine.jpg"] = byteArrayOf(1, 2, 3)
        m.sendPhoto(sam, Photo("mine.jpg", 1600, 1200), caption = " The view ")
        val out = sent.single() as ClientMessage.Message
        assertEquals("The view", out.text)
        assertEquals(io.github.nomskis.earshot.signaling.WirePhoto("AQID", "image/jpeg", 1600, 1200), out.photo)
        assertEquals("Photo: The view", m.conversations.value.getValue(sam).messages.single().summary)
        // It goes again after a reconnect, file and all.
        sent.clear()
        m.onServerMessage(ServerMessage.Listening("our-address"))
        assertEquals("AQID", (sent.single() as ClientMessage.Message).photo?.data)
    }

    @Test
    fun theirPictureIsKeptOnceAndLetGoWithItsMessage() {
        val m = messenger()
        val photo = io.github.nomskis.earshot.signaling.WirePhoto("AQID", "image/jpeg", 1200, 1600)
        val message = ServerMessage.Message(id = "m-them-0001", from = Caller("Sam", sam), photo = photo)
        m.onServerMessage(message)
        m.onServerMessage(message)
        assertEquals(1, files.size)
        val kept = m.conversations.value.getValue(sam).messages.single()
        assertEquals(Photo("p1.jpg", 1200, 1600), kept.photo)
        assertEquals(listOf<Byte>(1, 2, 3), files.getValue("p1.jpg").toList())
        // Deleted for everyone by them: the picture goes too.
        m.onServerMessage(ServerMessage.Message(id = "x-m-them-0001", from = Caller("Sam", sam), unsend = "m-them-0001"))
        assertTrue(files.isEmpty())
    }

    @Test
    fun clearingAChatLetsItsPicturesGo() {
        val m = messenger()
        files["mine.jpg"] = byteArrayOf(1)
        m.sendPhoto(sam, Photo("mine.jpg", 10, 10))
        m.clear(sam)
        assertTrue(files.isEmpty())
    }

    @Test
    fun ourPictureGoesToEachContactUntilTheirPhoneHasIt() {
        val m = messenger()
        m.shareProfile()
        // Never set: nothing to send.
        assertTrue(sent.isEmpty())
        myVersion = 1_791_200_000_000
        myPhoto = byteArrayOf(1, 2, 3)
        m.shareProfile()
        val out = sent.map { it as ClientMessage.Message }
        assertEquals(listOf(sam, stranger), out.map { it.to })
        assertEquals("p-1791200000000", out.first().id)
        assertEquals(io.github.nomskis.earshot.signaling.WireProfile(photo = "AQID"), out.first().profile)
        // Sam's phone has it; the other one is still owed it after a reconnect.
        m.onServerMessage(ServerMessage.MessageStatus("p-1791200000000", sam, "delivered"))
        sent.clear()
        m.onServerMessage(ServerMessage.Listening("our-address"))
        assertEquals(listOf(stranger), sent.mapNotNull { (it as ClientMessage.Message).takeIf { m -> m.profile != null }?.to })
        // Taken away: everyone's told.
        myVersion += 1
        myPhoto = null
        sent.clear()
        m.shareProfile()
        assertEquals(listOf(true, true), sent.map { (it as ClientMessage.Message).profile?.removed })
    }

    @Test
    fun theirPictureIsKeptAndNotAChatMessage() {
        val m = messenger()
        m.onServerMessage(
            ServerMessage.Message(id = "p-1", from = Caller("Sam", sam), profile = io.github.nomskis.earshot.signaling.WireProfile(photo = "AQID")),
        )
        assertEquals(listOf<Byte>(1, 2, 3), theirs.getValue(sam)?.toList())
        assertEquals(ClientMessage.MessageAck(to = sam, id = "p-1"), sent.single())
        assertEquals(null, m.conversations.value[sam]?.messages?.firstOrNull())
        m.onServerMessage(
            ServerMessage.Message(id = "p-2", from = Caller("Sam", sam), profile = io.github.nomskis.earshot.signaling.WireProfile(removed = true)),
        )
        assertEquals(null, theirs.getValue(sam))
    }
}
