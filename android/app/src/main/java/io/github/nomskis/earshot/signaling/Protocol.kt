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
}

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

    /** Sent by the answering side when it needs a (new) offer. */
    @Serializable
    @SerialName("request-offer")
    data class RequestOffer(val session: String? = null) : SignalData

    @Serializable
    @SerialName("media-state")
    data class MediaState(
        val micMuted: Boolean = false,
        val cameraOff: Boolean = false,
        /** "hifi" when this side plays the call as media, "headset" or "standard" otherwise. */
        val audioMode: String? = null,
    ) : SignalData
}

object ErrorCodes {
    const val ROOM_FULL = "room-full"
    const val BAD_ROOM = "bad-room"
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
