@file:OptIn(ExperimentalSerializationApi::class)

package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.calls.InboxKeys
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Text chat between the two sides, for when one of you is muted or the gym is
 * too loud. It runs over a WebRTC data channel, so it's end-to-end encrypted
 * like the call and never passes through the server. The web client does the
 * same in web/js/chat.js; docs/protocol.md describes the messages.
 *
 * Every message is acknowledged. Anything not acknowledged yet is sent again
 * when the next connection's channel opens (a reconnect can mean a new peer
 * connection, and with it a new channel), and the receiver drops repeats by id.
 */
object Chat {
    /** Both sides create this channel themselves (negotiated), so neither waits for the other's. */
    const val CHANNEL_LABEL = "earshot-chat"
    const val CHANNEL_ID = 0
    const val MAX_LENGTH = 1000
    /** The other side shows the chat only when our join message lists this. */
    const val CAPABILITY = "chat"
    val QUICK_REPLIES = listOf("👍", "One sec", "Can't hear you", "Call you back", "❤️")

    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Frame {
        @Serializable
        @SerialName("chat")
        data class Message(val id: String, val text: String, val sentAt: Long? = null) : Frame

        @Serializable
        @SerialName("chat-ack")
        data class Ack(val id: String) : Frame

        /** Introduces our inbox address, so the other app can call us directly next time. */
        @Serializable
        @SerialName("contact")
        data class Contact(val name: String = "", val address: String) : Frame
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame)

    /** The frame, or null for anything else (newer kinds, garbage). */
    fun decode(text: String): Frame? {
        val frame = try {
            json.decodeFromString(Frame.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val id = when (frame) {
            is Frame.Message -> frame.id
            is Frame.Ack -> frame.id
            is Frame.Contact -> return frame.takeIf { InboxKeys.isAddress(it.address) }
        }
        if (id.isEmpty() || id.length > 64) return null
        if (frame !is Frame.Message) return frame
        val clean = frame.text.trim().take(MAX_LENGTH)
        return if (clean.isEmpty()) null else frame.copy(text = clean)
    }
}

data class ChatMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val atMillis: Long,
    val status: Status,
) {
    enum class Status { SENDING, DELIVERED, FAILED, RECEIVED }
}

/**
 * The conversation for one call. Not thread-safe: the call thread owns it.
 * [attach] plugs in the current channel's send function once it's open.
 */
class ChatLog(
    private val newId: () -> String = { Ids.random(9, "m") },
    private val now: () -> Long = System::currentTimeMillis,
) {
    var messages: List<ChatMessage> = emptyList()
        private set

    private val seen = HashSet<String>()
    private var transmit: ((String) -> Boolean)? = null

    /** The channel is open: send what's still waiting. */
    fun attach(send: (String) -> Boolean) {
        transmit = send
        messages.filter { it.mine && it.status == ChatMessage.Status.SENDING }.forEach(::sendMessage)
    }

    fun detach() {
        transmit = null
    }

    /** Queues and, if a channel is open, sends a message. Null when there's nothing to send. */
    fun send(text: String): ChatMessage? {
        val clean = text.trim().take(Chat.MAX_LENGTH)
        if (clean.isEmpty()) return null
        val message = ChatMessage(newId(), clean, mine = true, atMillis = now(), status = ChatMessage.Status.SENDING)
        messages = messages + message
        sendMessage(message)
        return message
    }

    /** Handles one frame from the channel; returns their new message, if it was one. */
    fun receive(text: String): ChatMessage? {
        when (val frame = Chat.decode(text) ?: return null) {
            is Chat.Frame.Ack -> {
                messages = messages.map {
                    if (it.mine && it.id == frame.id) it.copy(status = ChatMessage.Status.DELIVERED) else it
                }
                return null
            }
            // Contact cards are the session's business, not the conversation's.
            is Chat.Frame.Contact -> return null
            is Chat.Frame.Message -> {
                // Acknowledge repeats too: the first acknowledgement may be what got lost.
                transmit?.invoke(Chat.encode(Chat.Frame.Ack(frame.id)))
                if (!seen.add(frame.id)) return null
                val message = ChatMessage(frame.id, frame.text, mine = false, atMillis = now(), status = ChatMessage.Status.RECEIVED)
                messages = messages + message
                return message
            }
        }
    }

    /** Someone else took the other seat: what we couldn't deliver was meant for the person who left. */
    fun peerChanged() {
        messages = messages.map {
            if (it.mine && it.status == ChatMessage.Status.SENDING) it.copy(status = ChatMessage.Status.FAILED) else it
        }
    }

    private fun sendMessage(message: ChatMessage) {
        transmit?.invoke(Chat.encode(Chat.Frame.Message(message.id, message.text, message.atMillis)))
    }
}
