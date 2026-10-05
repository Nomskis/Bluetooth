package io.github.nomskis.earshot.messages

import io.github.nomskis.earshot.call.Ids
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage
import io.github.nomskis.earshot.signaling.WirePhoto
import io.github.nomskis.earshot.signaling.WireProfile
import io.github.nomskis.earshot.signaling.WireReply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Chat with contacts, in a call or not. Messages travel through the server on the same
 * inbox connection that rings the phone; a phone that's offline gets them when it's back.
 * Ours stay in the outbox until their phone confirms them, and go again after every
 * reconnect, so neither a dropped connection nor a server restart loses one.
 */
class Messenger(
    private val scope: CoroutineScope,
    private val store: Store,
    /** Hands a message to the inbox connection; false when it isn't up. */
    private val send: (ClientMessage) -> Boolean,
    /** Our name, as the other person's phone shows it for a stranger. */
    private val myName: suspend () -> String,
    /** Who they are, if they're a contact. */
    private val contact: suspend (address: String) -> Contact?,
    /** Someone new wrote to us: keep them, like after a first call. */
    private val saveContact: suspend (Contact) -> Unit,
    /** Someone we blocked: what they write is dropped. */
    private val isBlocked: suspend (address: String) -> Boolean,
    /** Their message arrived while their conversation isn't on screen. */
    private val notify: (name: String, address: String, conversation: Conversation) -> Unit,
    /** Nothing of theirs is left to show in a notification (they deleted it for everyone). */
    private val cancelNotification: (address: String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the files are read and written. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where pictures are kept ([Photos]). */
    private val photos: PhotoFiles = NoPhotos,
    /** Profile pictures, ours and theirs ([Profiles]). */
    private val profiles: ProfileFiles = NoProfiles,
    /** Everyone ours goes to: our contacts' addresses. */
    private val contacts: suspend () -> List<String> = { emptyList() },
) {
    /** Where conversations are kept. */
    interface Store {
        fun loadAll(): Map<String, Conversation>
        fun save(conversation: Conversation)
    }

    /** Where pictures are kept, by file name. */
    interface PhotoFiles {
        fun read(name: String): ByteArray?

        /** Keeps [bytes] (a JPEG); its new file's name, or null if it couldn't. */
        fun save(bytes: ByteArray): String?
        fun delete(names: Collection<String>)
    }

    /** Profile pictures: ours, which version of it each contact has, and theirs. */
    interface ProfileFiles {
        /** When ours was last set or taken away; 0 if it never was. */
        val myVersion: Long
        val hasMyPhoto: Boolean
        fun myPhoto(): ByteArray?
        fun sentVersion(address: String): Long
        fun markSent(address: String, version: Long)

        /** Theirs; null: they took it away. */
        fun saveTheirs(address: String, jpeg: ByteArray?)
    }

    private object NoProfiles : ProfileFiles {
        override val myVersion = 0L
        override val hasMyPhoto = false
        override fun myPhoto(): ByteArray? = null
        override fun sentVersion(address: String) = 0L
        override fun markSent(address: String, version: Long) = Unit
        override fun saveTheirs(address: String, jpeg: ByteArray?) = Unit
    }

    private object NoPhotos : PhotoFiles {
        override fun read(name: String): ByteArray? = null
        override fun save(bytes: ByteArray): String? = null
        override fun delete(names: Collection<String>) = Unit
    }

    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    /** The conversation on screen right now, whose new messages are read as they come. */
    @Volatile
    var open: String? = null
        set(value) {
            field = value
            value?.let(::markRead)
        }

    fun load() {
        scope.launch(io) { _conversations.value = store.loadAll() }
    }

    /** [reply]: the message this one answers. */
    fun send(address: String, text: String, reply: Quote? = null) {
        val body = text.trim().take(Conversation.MAX_TEXT)
        if (body.isEmpty()) return
        val id = Ids.random(12, "m")
        change(address) { it.sending(id, body, now(), reply) }
        val message = _conversations.value[address]?.messages?.lastOrNull { it.mine && it.id == id } ?: return
        scope.launch { transmit(address, message) }
    }

    /** A picture ([Photos.prepare] made it ready), with [caption] as its words. */
    fun sendPhoto(address: String, photo: Photo, caption: String = "", reply: Quote? = null) {
        val id = Ids.random(12, "m")
        change(address) { it.sending(id, caption.trim().take(Conversation.MAX_TEXT), now(), reply, photo) }
        val message = _conversations.value[address]?.messages?.lastOrNull { it.mine && it.id == id } ?: return
        scope.launch { transmit(address, message) }
    }

    /**
     * Our profile picture (or that we took it away) to each contact whose phone doesn't have
     * this version yet: after it changes, for a new contact, and after every reconnect.
     */
    fun shareProfile() {
        scope.launch {
            val version = profiles.myVersion
            if (version == 0L) return@launch
            val profile = if (profiles.hasMyPhoto) {
                val data = withContext(io) { profiles.myPhoto()?.let { Base64.getEncoder().encodeToString(it) } } ?: return@launch
                WireProfile(photo = data)
            } else {
                WireProfile(removed = true)
            }
            for (address in contacts()) {
                if (profiles.sentVersion(address) == version || isBlocked(address)) continue
                send(ClientMessage.Message(to = address, id = profileId(version), name = myName(), profile = profile))
            }
        }
    }

    /** The inbox is listening again: everything they haven't confirmed goes out. */
    fun flushOutbox() {
        shareProfile()
        scope.launch {
            for (conversation in _conversations.value.values) {
                for (message in conversation.outbox) transmit(conversation.address, message)
                for (id in conversation.unsending) transmitUnsend(conversation.address, id)
            }
        }
    }

    fun onServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Listening -> flushOutbox()
            is ServerMessage.Message -> scope.launch { receive(message) }
            is ServerMessage.MessageStatus -> {
                val status = Conversation.statusOf(message.status) ?: return
                val withdrawn = Conversation.unsendTarget(message.id)
                val profileVersion = profileVersion(message.id)
                when {
                    profileVersion != null -> if (status >= TextMessage.Status.DELIVERED) profiles.markSent(message.to, profileVersion)
                    withdrawn != null -> if (status >= TextMessage.Status.DELIVERED) change(message.to) { it.unsendDelivered(withdrawn) }
                    status == TextMessage.Status.READ -> change(message.to) { it.seen(message.id) }
                    else -> change(message.to) { it.status(message.id, status) }
                }
            }
            else -> Unit
        }
    }

    /** Their messages so far are seen (on screen, or answered from the notification); they're told, so theirs show "Seen". */
    fun markRead(address: String) {
        change(address) { it.read(now()) }
        val latest = _conversations.value[address]?.lastReceived ?: return
        send(ClientMessage.MessageRead(to = address, id = latest.id))
    }

    /** Off this phone only. */
    fun deleteMessage(address: String, id: String, mine: Boolean) = change(address) { it.delete(id, mine) }

    /** One of ours, off their phone too ([Conversation.canUnsend]); "deleted" shows on both. */
    fun deleteForEveryone(address: String, id: String) {
        val conversation = _conversations.value[address] ?: return
        val message = conversation.messages.firstOrNull { it.mine && it.id == id } ?: return
        if (!conversation.canUnsend(message, now())) return
        change(address) { it.unsent(id) }
        scope.launch { transmitUnsend(address, id) }
    }

    fun clear(address: String) = change(address) { it.cleared() }

    /** A message from a call's own chat, kept in the conversation with them too. */
    fun recordCallChat(address: String, id: String, text: String, mine: Boolean, atMillis: Long) = change(address) {
        if (mine) it.sending(id, text, atMillis).status(id, TextMessage.Status.DELIVERED) else it.received(id, text, atMillis)
    }

    private suspend fun receive(message: ServerMessage.Message) {
        val address = message.from.address ?: return
        // Confirm every copy, the same one again included: that's how their phone learns it got here.
        // A blocked sender's too, or the server would keep handing it over.
        send(ClientMessage.MessageAck(to = address, id = message.id))
        if (isBlocked(address)) return
        // Their profile picture, or that they took it away.
        message.profile?.let { profile ->
            val jpeg = profile.photo?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: return }
            withContext(io) { profiles.saveTheirs(address, jpeg) }
            return
        }
        // They deleted one of theirs for everyone.
        message.unsend?.let { id ->
            change(address) { it.withdrawn(id) }
            refreshNotification(address)
            return
        }
        val text = message.text.trim().take(Conversation.MAX_TEXT)
        if (text.isEmpty() && message.photo == null) return
        // A picture sent again after a reconnect isn't saved twice.
        if (_conversations.value[address]?.hasTheirs(message.id) == true) return
        val photo = message.photo?.let { keep(it) }
        if (text.isEmpty() && photo == null) return
        val known = contact(address)
        if (known == null) saveContact(Contact(message.from.name.ifBlank { "Someone" }, address, 0))
        val at = now()
        val before = _conversations.value[address]
        // "you" in their reply is us.
        val reply = message.reply?.let { Quote(it.id, mine = it.sender == "you", text = it.text.take(Conversation.MAX_QUOTE), photo = it.photo == true) }
        change(address) { it.received(message.id, text, at, reply, photo) }
        val after = _conversations.value[address] ?: return
        val isNew = (before?.messages?.size ?: 0) != after.messages.size
        if (!isNew) return
        if (open == address) {
            markRead(address)
        } else {
            notify(known?.name ?: message.from.name.ifBlank { "Someone" }, address, after)
        }
    }

    /** A withdrawn message mustn't linger in their notification. */
    private suspend fun refreshNotification(address: String) {
        if (open == address) return
        val conversation = _conversations.value[address] ?: return
        if (conversation.unread == 0) {
            cancelNotification(address)
        } else {
            notify(contact(address)?.name ?: "Someone", address, conversation)
        }
    }

    private fun profileId(version: Long) = "p-$version"

    /** The profile version a status is about, or null for a chat message. */
    private fun profileVersion(id: String): Long? = if (id.startsWith("p-")) id.removePrefix("p-").toLongOrNull() else null

    /** Their picture, kept here; null if it isn't one. */
    private suspend fun keep(wire: WirePhoto): Photo? = withContext(io) {
        val bytes = runCatching { Base64.getDecoder().decode(wire.data) }.getOrNull() ?: return@withContext null
        val name = photos.save(bytes) ?: return@withContext null
        Photo(name, wire.width, wire.height)
    }

    private suspend fun transmit(address: String, message: TextMessage) {
        val reply = message.reply?.let { WireReply(it.id, sender = if (it.mine) "me" else "you", text = it.text, photo = it.photo.takeIf { p -> p }) }
        val photo = message.photo?.let { photo ->
            val data = withContext(io) { photos.read(photo.file)?.let { Base64.getEncoder().encodeToString(it) } } ?: return
            WirePhoto(data, type = "image/jpeg", width = photo.width, height = photo.height)
        }
        send(ClientMessage.Message(to = address, id = message.id, text = message.text, name = myName(), reply = reply, photo = photo))
    }

    private suspend fun transmitUnsend(address: String, id: String) {
        send(ClientMessage.Message(to = address, id = Conversation.unsendId(id), name = myName(), unsend = id))
    }

    private fun change(address: String, transform: (Conversation) -> Conversation) {
        var saved: Conversation? = null
        var dropped: Set<String> = emptySet()
        _conversations.update { all ->
            val before = all[address] ?: Conversation(address)
            val after = transform(before)
            if (after == before) return@update all
            saved = after
            // Pictures no message uses any more (deleted, withdrawn, cleared, the oldest let go).
            dropped = before.photoFiles - after.photoFiles
            all + (address to after)
        }
        saved?.let { conversation -> scope.launch(io) { store.save(conversation) } }
        if (dropped.isNotEmpty()) {
            val gone = dropped
            scope.launch(io) { photos.delete(gone) }
        }
    }
}
