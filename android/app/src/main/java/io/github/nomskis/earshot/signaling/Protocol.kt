@file:OptIn(ExperimentalSerializationApi::class)

package io.github.nomskis.earshot.signaling

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Signaling protocol v1. See docs/protocol.md; example messages live in
 * protocol/fixtures and are checked by unit tests on both sides.
 */
const val PROTOCOL_VERSION = 1

val ProtocolJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ClientInfo(
    val platform: String = "unknown",
    val version: String = "",
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class PeerInfo(
    val peerId: String,
    val name: String = "",
    val client: ClientInfo = ClientInfo(),
    /** Join order in the room. The peer with the higher seq sends offers. */
    val seq: Int,
)

@Serializable
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class CandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val usernameFragment: String? = null,
)

/** Messages a client sends to the server. */
@Serializable
sealed interface ClientMessage {
    @Serializable
    @SerialName("join")
    data class Join(
        val room: String,
        val peerId: String,
        val name: String = "",
        val client: ClientInfo = ClientInfo(),
    ) : ClientMessage

    @Serializable
    @SerialName("signal")
    data class Signal(val to: String, val data: SignalData) : ClientMessage

    @Serializable
    @SerialName("leave")
    data object Leave : ClientMessage

    @Serializable
    @SerialName("ping")
    data object Ping : ClientMessage

    /** Wait for calls on the inbox this secret key belongs to. */
    @Serializable
    @SerialName("listen")
    data class Listen(val inbox: String) : ClientMessage

    /**
     * Ring someone's inbox address with the room to meet in. [inbox] (our own key) proves who's calling.
     * [preconnect]: we take someone who joins listing [Capabilities.RINGING] as still ringing, so their
     * phone may connect ahead of the answer.
     */
    @Serializable
    @SerialName("ring")
    data class Ring(
        val to: String,
        val ringId: String,
        val room: String,
        val name: String = "",
        val video: Boolean = false,
        val inbox: String? = null,
        val preconnect: Boolean? = null,
    ) : ClientMessage

    @Serializable
    @SerialName("ring-cancel")
    data class RingCancel(val ringId: String) : ClientMessage

    /** [reason] when not accepted: "declined" or "busy". */
    @Serializable
    @SerialName("ring-answer")
    data class RingAnswer(val ringId: String, val accepted: Boolean, val reason: String? = null) : ClientMessage

    /** A chat message to someone's inbox address, sent over our own listening inbox connection. */
    @Serializable
    @SerialName("message")
    data class Message(val to: String, val id: String, val text: String, val name: String = "") : ClientMessage

    /** We have [id] from [to]: the server stops holding it and tells them it was delivered. */
    @Serializable
    @SerialName("message-ack")
    data class MessageAck(val to: String, val id: String) : ClientMessage
}

/** Who's calling: the name they gave, and their inbox address when they proved it. */
@Serializable
data class Caller(val name: String = "", val address: String? = null)

/** Messages the server sends to a client. */
@Serializable
sealed interface ServerMessage {
    @Serializable
    @SerialName("joined")
    data class Joined(
        val protocol: Int = PROTOCOL_VERSION,
        val room: String,
        val peerId: String,
        val seq: Int,
        val resumed: Boolean = false,
        val peers: List<PeerInfo> = emptyList(),
        val iceServers: List<IceServerConfig> = emptyList(),
    ) : ServerMessage

    @Serializable
    @SerialName("peer-joined")
    data class PeerJoined(val peer: PeerInfo) : ServerMessage

    @Serializable
    @SerialName("peer-left")
    data class PeerLeft(val peerId: String, val reason: String = "") : ServerMessage

    @Serializable
    @SerialName("signal")
    data class Signal(val from: String, val data: SignalData) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String = "") : ServerMessage

    @Serializable
    @SerialName("pong")
    data object Pong : ServerMessage

    @Serializable
    @SerialName("listening")
    data class Listening(val address: String) : ServerMessage

    /** A chat message for us; [from] is proven by the server (the address the sender listens on). */
    @Serializable
    @SerialName("message")
    data class Message(val id: String, val from: Caller, val text: String, val sentAt: Long = 0) : ServerMessage

    /** How a message we sent to [to] is doing: "sent", "queued" (their phone is offline) or "delivered". */
    @Serializable
    @SerialName("message-status")
    data class MessageStatus(val id: String, val to: String, val status: String) : ServerMessage

    /** Someone is ringing us. [preconnect]: their app lets us connect while it rings (see [ClientMessage.Ring]). */
    @Serializable
    @SerialName("incoming")
    data class Incoming(
        val ringId: String,
        val room: String,
        val from: Caller = Caller(),
        val video: Boolean = false,
        val preconnect: Boolean = false,
    ) : ServerMessage

    /** Our ring reached [devices] devices ("ringing"), or nobody is listening ("unreachable"). */
    @Serializable
    @SerialName("ring-status")
    data class RingStatus(val ringId: String, val status: String, val devices: Int = 0) : ServerMessage

    /** Stop ringing: "cancelled" by the caller, "timeout", or "answered-elsewhere". */
    @Serializable
    @SerialName("ring-cancelled")
    data class RingCancelled(val ringId: String, val reason: String = "cancelled") : ServerMessage

    /** The answer to our ring; [reason] when not accepted: "declined", "busy" or "no-answer". */
    @Serializable
    @SerialName("ring-answered")
    data class RingAnswered(val ringId: String, val accepted: Boolean, val reason: String? = null) : ServerMessage
}

/** Peer-to-peer payloads. The server relays these untouched. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface SignalData {
    @Serializable
    @SerialName("offer")
    data class Offer(val session: String, val sdp: String) : SignalData

    @Serializable
    @SerialName("answer")
    data class Answer(val session: String, val sdp: String) : SignalData

    @Serializable
    @SerialName("candidate")
    data class Candidate(val session: String, val candidate: CandidatePayload) : SignalData

    /**
     * Sent by the answering side when it needs a (new) offer. [iceRestart] false: the
     * connection is fine, it just wants to change what it asks for (packet time), so
     * an offer on the same session without restarting ICE. Only sent to peers that
     * list [Capabilities.RENEGOTIATE]; older ones would restart ICE.
     */
    @Serializable
    @SerialName("request-offer")
    data class RequestOffer(val session: String? = null, val iceRestart: Boolean? = null) : SignalData

    @Serializable
    @SerialName("media-state")
    data class MediaState(
        val micMuted: Boolean = false,
        val cameraOff: Boolean = false,
        /** "hifi" when this side plays the call as media, "headset" or "standard" otherwise. */
        val audioMode: String? = null,
        /** The camera is paused because the phone is in a pocket (cameraOff is true too). */
        val inPocket: Boolean? = null,
        /** Video is paused because the connection can't carry it next to the voice (cameraOff is true too). */
        val weakConnection: Boolean? = null,
        /** The network our media goes over: "wifi" or "cellular". */
        val network: String? = null,
        /** How our uplink is doing: "tight" or "starved"; absent when fine. */
        val uplink: String? = null,
        /** Our Wi-Fi shares its radio with Bluetooth earbuds (2.4 GHz): fewer, longer packets please. */
        val radioShared: Boolean? = null,
        /** Our phone is still ringing ([Capabilities.RINGING]); absent once answered. */
        val ringing: Boolean? = null,
    ) : SignalData
}

/** What a client lists in its join message's capabilities. */
object Capabilities {
    /** Answers `request-offer` with `iceRestart: false` by renegotiating in place. */
    const val RENEGOTIATE = "renegotiate"

    /** Wants this call through the TURN relay at both ends (the other side follows, when it has a relay). */
    const val RELAY_ROUTE = "relay-route"

    /**
     * Joined while its phone still rings, to connect ahead of the answer: it sends and plays
     * nothing, and the caller keeps ringing and sends nothing either, until it's answered
     * (`ring-answered`, or a media-state without `ringing`). Only joins rooms whose ring said
     * `preconnect`, and once answered it's an ordinary call, whatever its join said.
     */
    const val RINGING = "ringing"
}

object ErrorCodes {
    const val ROOM_FULL = "room-full"
    const val BAD_ROOM = "bad-room"
    const val BAD_REQUEST = "bad-request"
}

fun encodeClientMessage(message: ClientMessage): String =
    ProtocolJson.encodeToString(ClientMessage.serializer(), message)

/**
 * Decodes a server message. Returns null for anything this version does not
 * understand (for example a signal kind added by a newer client), so old apps
 * keep working next to new ones.
 */
fun decodeServerMessage(text: String): ServerMessage? = try {
    ProtocolJson.decodeFromString(ServerMessage.serializer(), text)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
