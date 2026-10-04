package io.github.nomskis.earshot.calls

import io.github.nomskis.earshot.call.Ids
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

/**
 * Who this install is, for ringing. The secret inbox key lets the app listen
 * for calls; its public address, a hash of the key, is what others ring.
 * Same derivation as the server (server/src/inbox.js).
 */
object InboxKeys {
    private val ADDRESS = Regex("^[A-Za-z0-9_-]{22}$")

    /** 144 random bits, URL-safe. */
    fun newKey(): String = Ids.random(18)

    fun address(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("earshot-inbox:$key".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(22)
    }

    fun isAddress(value: String): Boolean = ADDRESS.matches(value)
}

/** Someone you can call directly: saved when you've had a call with them. */
@Serializable
data class Contact(
    val name: String,
    val address: String,
    val lastCallAtMillis: Long = 0,
    /** You named them yourself; the name their phone sends no longer replaces it. */
    val renamed: Boolean = false,
)

object Contacts {
    /** Enough for the people you actually call; the oldest go first. */
    const val MAX = 20
    private const val MAX_NAME = 64

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Contact.serializer())

    fun decode(text: String?): List<Contact> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun encode(contacts: List<Contact>): String = json.encodeToString(serializer, contacts)

    /** Adds or refreshes [contact] (same address = same person), most recent first. */
    fun upsert(contacts: List<Contact>, contact: Contact): List<Contact> {
        val existing = contacts.firstOrNull { it.address == contact.address }
        var clean = contact.copy(name = cleanName(contact.name))
        if (existing != null) {
            // A name you gave them stays; the latest call time wins.
            if (existing.renamed && !contact.renamed) clean = clean.copy(name = existing.name, renamed = true)
            clean = clean.copy(lastCallAtMillis = maxOf(existing.lastCallAtMillis, clean.lastCallAtMillis))
        }
        return (listOf(clean) + contacts.filter { it.address != clean.address }).take(MAX)
    }

    /** Your own name for them; their position in the list doesn't change. */
    fun rename(contacts: List<Contact>, address: String, name: String): List<Contact> =
        contacts.map { if (it.address == address) it.copy(name = cleanName(name), renamed = true) else it }

    private fun cleanName(name: String) = name.trim().take(MAX_NAME).ifEmpty { "Contact" }

    fun remove(contacts: List<Contact>, address: String): List<Contact> = contacts.filter { it.address != address }
}
