package io.github.nomskis.earshot.messages

import io.github.nomskis.earshot.call.Ids
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage
import io.github.nomskis.earshot.signaling.WireReply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) {
    /** Where conversations are kept. */
    interface Store {
        fun loadAll(): Map<String, Conversation>
        fun save(conversation: Conversation)
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

    /** The inbox is listening again: everything they haven't confirmed goes out. */
    fun flushOutbox() {
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
                when {
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
        // They deleted one of theirs for everyone.
        message.unsend?.let { id ->
            change(address) { it.withdrawn(id) }
            refreshNotification(address)
            return
        }
        val text = message.text.trim().take(Conversation.MAX_TEXT)
        if (text.isEmpty()) return
        val known = contact(address)
        if (known == null) saveContact(Contact(message.from.name.ifBlank { "Someone" }, address, 0))
        val at = now()
        val before = _conversations.value[address]
        // "you" in their reply is us.
        val reply = message.reply?.let { Quote(it.id, mine = it.sender == "you", text = it.text.take(Conversation.MAX_QUOTE)) }
        change(address) { it.received(message.id, text, at, reply) }
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

    private suspend fun transmit(address: String, message: TextMessage) {
        val reply = message.reply?.let { WireReply(it.id, sender = if (it.mine) "me" else "you", text = it.text) }
        send(ClientMessage.Message(to = address, id = message.id, text = message.text, name = myName(), reply = reply))
    }

    private suspend fun transmitUnsend(address: String, id: String) {
        send(ClientMessage.Message(to = address, id = Conversation.unsendId(id), name = myName(), unsend = id))
    }

    private fun change(address: String, transform: (Conversation) -> Conversation) {
        var saved: Conversation? = null
        _conversations.update { all ->
            val before = all[address] ?: Conversation(address)
            val after = transform(before)
            if (after == before) return@update all
            saved = after
            all + (address to after)
        }
        saved?.let { conversation -> scope.launch(io) { store.save(conversation) } }
    }
}
