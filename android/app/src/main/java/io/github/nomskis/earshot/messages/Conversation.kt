package io.github.nomskis.earshot.messages

import kotlinx.serialization.Serializable

/** One message in a conversation with a contact, kept on the phone. */
@Serializable
data class TextMessage(
    /** Made by the sender; unique within what they send. */
    val id: String,
    val text: String,
    val mine: Boolean,
    val atMillis: Long,
    val status: Status,
    /** The message this one answers, quoted. */
    val reply: Quote? = null,
    /** Deleted for everyone by its sender: only "deleted" shows where it was. */
    val deleted: Boolean = false,
    /** A picture, kept on this phone; [text] is then its words, which may be empty. */
    val photo: Photo? = null,
) {
    /** In the order a message we send moves through; theirs are [RECEIVED]. */
    enum class Status { SENDING, WAITING, SENT, DELIVERED, READ, RECEIVED }

    /** As a quote in an answer to it. */
    fun quoted(): Quote = Quote(id, mine, text.take(Conversation.MAX_QUOTE), photo = photo != null)

    /** In a line: the chat list, a notification, a quote. */
    val summary: String get() = when {
        photo != null && text.isNotBlank() -> "Photo: $text"
        photo != null -> "Photo"
        else -> text
    }
}

/** A picture in a message: its file in this phone's photo folder ([Photos]), and its size in pixels. */
@Serializable
data class Photo(val file: String, val width: Int, val height: Int)

/**
 * The message an answer quotes: which one ([id], and whether it's [mine], from this phone's
 * side) and a little of what it said, so the quote shows even after the message is gone.
 */
@Serializable
data class Quote(val id: String, val mine: Boolean, val text: String = "", val photo: Boolean = false)

/** Everything said with one contact (by inbox address), oldest first. */
@Serializable
data class Conversation(
    val address: String,
    val messages: List<TextMessage> = emptyList(),
    /** Their messages up to this time have been seen. */
    val readUpTo: Long = 0,
    /** Ours deleted for everyone whose withdrawal their phone hasn't confirmed: sent again on every reconnect. */
    val unsending: List<String> = emptyList(),
) {
    val unread: Int get() = messages.count { !it.mine && !it.deleted && it.atMillis > readUpTo }
    val last: TextMessage? get() = messages.lastOrNull()

    /** Ours that they haven't confirmed yet: sent again whenever the phone reconnects. */
    val outbox: List<TextMessage> get() = messages.filter { it.mine && !it.deleted && it.status < TextMessage.Status.DELIVERED }

    /** Their latest, which a read receipt names. */
    val lastReceived: TextMessage? get() = messages.lastOrNull { !it.mine }

    fun sending(id: String, text: String, nowMs: Long, reply: Quote? = null, photo: Photo? = null): Conversation =
        if (messages.any { it.mine && it.id == id }) {
            this
        } else {
            copy(messages = trim(messages + TextMessage(id, text, mine = true, nowMs, TextMessage.Status.SENDING, reply = reply, photo = photo)))
        }

    /** Theirs; the same message again (sent again after a reconnect) is kept once. */
    fun received(id: String, text: String, atMillis: Long, reply: Quote? = null, photo: Photo? = null): Conversation =
        if (messages.any { !it.mine && it.id == id }) {
            this
        } else {
            copy(messages = trim(messages + TextMessage(id, text, mine = false, atMillis, TextMessage.Status.RECEIVED, reply = reply, photo = photo)))
        }

    /** Whether one with this id came from them already (a picture arriving again isn't saved again). */
    fun hasTheirs(id: String): Boolean = messages.any { !it.mine && it.id == id }

    /** Every picture file the messages still use. */
    val photoFiles: Set<String> get() = messages.mapNotNullTo(HashSet()) { it.photo?.file }

    /** The message [quote] points at, if it's still here. */
    fun find(quote: Quote): TextMessage? = messages.firstOrNull { it.id == quote.id && it.mine == quote.mine }

    /**
     * Whether one of ours can still be deleted for everyone: not long after it was sent, like
     * WhatsApp, so an old conversation can't be rewritten.
     */
    fun canUnsend(message: TextMessage, nowMs: Long): Boolean =
        message.mine && !message.deleted && nowMs - message.atMillis < UNSEND_WINDOW_MS

    /**
     * Ours [id], deleted for everyone: what it said is gone from here, "deleted" stays in its
     * place, and their phone is told until it confirms. One not sent yet simply isn't.
     */
    fun unsent(id: String): Conversation {
        val target = messages.firstOrNull { it.mine && it.id == id && !it.deleted } ?: return this
        return copy(
            messages = messages.map { if (it === target) it.withdrawn() else it },
            unsending = if (id in unsending) unsending else unsending + id,
        )
    }

    /** Their phone has the withdrawal of [id]. */
    fun unsendDelivered(id: String): Conversation = if (id in unsending) copy(unsending = unsending - id) else this

    /** Theirs [id], deleted for everyone by them. */
    fun withdrawn(id: String): Conversation {
        if (messages.none { !it.mine && it.id == id && !it.deleted }) return this
        return copy(messages = messages.map { if (!it.mine && it.id == id) it.withdrawn() else it })
    }

    private fun TextMessage.withdrawn() = copy(text = "", reply = null, photo = null, deleted = true)

    /** How one of ours is doing; it only moves forward (a late "sent" doesn't undo "delivered"). */
    fun status(id: String, status: TextMessage.Status): Conversation = copy(
        messages = messages.map { m ->
            if (m.mine && m.id == id && status.ordinal > m.status.ordinal) m.copy(status = status) else m
        },
    )

    /**
     * They've seen ours up to [id]: that one, and the ones before it their phone confirmed.
     * One still on its way stays as it is, and keeps being sent until it's confirmed.
     */
    fun seen(id: String): Conversation {
        val upTo = messages.indexOfLast { it.mine && it.id == id }
        if (upTo < 0) return this
        return copy(
            messages = messages.mapIndexed { i, m ->
                val seen = m.mine && (i == upTo || (i < upTo && m.status == TextMessage.Status.DELIVERED))
                if (seen && m.status < TextMessage.Status.READ) m.copy(status = TextMessage.Status.READ) else m
            },
        )
    }

    /** Gone from this phone only. One of ours still on its way isn't sent any more. */
    fun delete(id: String, mine: Boolean): Conversation = copy(messages = messages.filterNot { it.id == id && it.mine == mine })

    /** Every message gone from this phone; the conversation itself stays. */
    fun cleared(): Conversation = copy(messages = emptyList())

    fun read(nowMs: Long): Conversation = if (unread == 0) this else copy(readUpTo = maxOf(readUpTo, nowMs, messages.maxOfOrNull { it.atMillis } ?: 0))

    private fun trim(list: List<TextMessage>): List<TextMessage> = list.takeLast(MAX_MESSAGES)

    companion object {
        /** Per contact; the oldest go first. */
        const val MAX_MESSAGES = 2_000

        /** Same as the server's limit. */
        const val MAX_TEXT = 4_000

        /** How much of a message an answer quotes; the server's limit too. */
        const val MAX_QUOTE = 300

        /** Ours can be deleted for everyone this long after they're sent. */
        const val UNSEND_WINDOW_MS = 48 * 60 * 60 * 1000L

        /** An unsend's own message id: one per message it withdraws, so sending it again is the same one. */
        fun unsendId(id: String): String = "x-$id"

        /** The message an unsend's status is about, or null for an ordinary message. */
        fun unsendTarget(id: String): String? = id.removePrefix("x-").takeIf { id.startsWith("x-") }

        fun statusOf(server: String): TextMessage.Status? = when (server) {
            "queued" -> TextMessage.Status.WAITING
            "sent" -> TextMessage.Status.SENT
            "delivered" -> TextMessage.Status.DELIVERED
            "read" -> TextMessage.Status.READ
            else -> null
        }
    }
}
