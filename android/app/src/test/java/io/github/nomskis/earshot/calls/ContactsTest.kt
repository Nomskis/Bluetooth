package io.github.nomskis.earshot.calls

import io.github.nomskis.earshot.call.Chat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {
    @Test
    fun addressMatchesTheServersDerivation() {
        // Same keys and addresses as protocol/fixtures (computed by server/src/inbox.js).
        assertEquals("FpXE_Hse1mKgCltfE83ULb", InboxKeys.address("sam-secret-inbox-key-0001"))
        assertEquals("1D8ANuTJStR4AyHh0kwUw6", InboxKeys.address("salma-secret-inbox-key-01"))
        assertTrue(InboxKeys.isAddress(InboxKeys.address(InboxKeys.newKey())))
        assertTrue(InboxKeys.newKey().length >= 22) // the server's minimum
        assertFalse(InboxKeys.isAddress("too-short"))
    }

    @Test
    fun savingKeepsOnePerPersonMostRecentFirst() {
        var list = Contacts.upsert(emptyList(), Contact("Salma", "1D8ANuTJStR4AyHh0kwUw6", 1))
        list = Contacts.upsert(list, Contact("Mum", "FpXE_Hse1mKgCltfE83ULb", 2))
        list = Contacts.upsert(list, Contact("Salma ❤️", "1D8ANuTJStR4AyHh0kwUw6", 3))
        assertEquals(listOf("Salma ❤️", "Mum"), list.map { it.name })
        assertEquals(list, Contacts.decode(Contacts.encode(list)))
        assertEquals(listOf("Mum"), Contacts.remove(list, "1D8ANuTJStR4AyHh0kwUw6").map { it.name })
        assertEquals("Contact", Contacts.upsert(emptyList(), Contact("  ", "1D8ANuTJStR4AyHh0kwUw6")).single().name)
        assertEquals(emptyList<Contact>(), Contacts.decode("not json"))
    }

    @Test
    fun blockingKeepsOnePerPersonNewestFirst() {
        var list = Contacts.block(emptyList(), Contact("Spam", "FpXE_Hse1mKgCltfE83ULb"))
        list = Contacts.block(list, Contact("Ex", "1D8ANuTJStR4AyHh0kwUw6"))
        list = Contacts.block(list, Contact("Spam again", "FpXE_Hse1mKgCltfE83ULb"))
        assertEquals(listOf("Spam again", "Ex"), list.map { it.name })
    }

    @Test
    fun theContactCardTravelsOverTheChatChannelAndIsCheckedOnArrival() {
        val card = Chat.Frame.Contact("Sam", "FpXE_Hse1mKgCltfE83ULb")
        assertEquals(card, Chat.decode(Chat.encode(card)))
        assertNull(Chat.decode("""{"kind":"contact","name":"x","address":"not-an-address"}"""))
        // The chat log leaves cards to the session.
        assertNull(io.github.nomskis.earshot.call.ChatLog().receive(Chat.encode(card)))
    }
}
