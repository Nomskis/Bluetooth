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
) {
    /** In the order a message we send moves through; theirs are [RECEIVED]. */
    enum class Status { SENDING, WAITING, SENT, DELIVERED, READ, RECEIVED }
}

/** Everything said with one contact (by inbox address), oldest first. */
@Serializable
data class Conversation(
    val address: String,
    val messages: List<TextMessage> = emptyList(),
    /** Their messages up to this time have been seen. */
    val readUpTo: Long = 0,
) {
    val unread: Int get() = messages.count { !it.mine && it.atMillis > readUpTo }
    val last: TextMessage? get() = messages.lastOrNull()

    /** Ours that they haven't confirmed yet: sent again whenever the phone reconnects. */
    val outbox: List<TextMessage> get() = messages.filter { it.mine && it.status < TextMessage.Status.DELIVERED }

    /** Their latest, which a read receipt names. */
    val lastReceived: TextMessage? get() = messages.lastOrNull { !it.mine }

    fun sending(id: String, text: String, nowMs: Long): Conversation =
        if (messages.any { it.mine && it.id == id }) this else copy(messages = trim(messages + TextMessage(id, text, mine = true, nowMs, TextMessage.Status.SENDING)))

    /** Theirs; the same message again (sent again after a reconnect) is kept once. */
    fun received(id: String, text: String, atMillis: Long): Conversation =
        if (messages.any { !it.mine && it.id == id }) this else copy(messages = trim(messages + TextMessage(id, text, mine = false, atMillis, TextMessage.Status.RECEIVED)))

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

    fun read(nowMs: Long): Conversation = if (unread == 0) this else copy(readUpTo = maxOf(readUpTo, nowMs, messages.maxOfOrNull { it.atMillis } ?: 0))

    private fun trim(list: List<TextMessage>): List<TextMessage> = list.takeLast(MAX_MESSAGES)

    companion object {
        /** Per contact; the oldest go first. */
        const val MAX_MESSAGES = 2_000

        /** Same as the server's limit. */
        const val MAX_TEXT = 4_000

        fun statusOf(server: String): TextMessage.Status? = when (server) {
            "queued" -> TextMessage.Status.WAITING
            "sent" -> TextMessage.Status.SENT
            "delivered" -> TextMessage.Status.DELIVERED
            "read" -> TextMessage.Status.READ
            else -> null
        }
    }
}
