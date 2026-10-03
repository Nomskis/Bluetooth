package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.signaling.PeerInfo

enum class CallPhase {
    /** Reaching the signaling server. */
    CONNECTING,

    /** In the room, alone. */
    WAITING,

    /** The other person is here; setting up the media connection. */
    NEGOTIATING,

    CONNECTED,

    /** Media connection dropped; trying to recover. */
    RECONNECTING,

    ENDED,
    FAILED,
}

data class RemoteMedia(
    val micMuted: Boolean = false,
    val cameraOff: Boolean = false,
    val audioMode: String? = null,
)

data class CallState(
    val phase: CallPhase,
    val room: String,
    val inviteLink: String,
    val audioMode: AudioMode,
    val remotePeer: PeerInfo? = null,
    val remoteMedia: RemoteMedia = RemoteMedia(),
    val hasRemoteVideo: Boolean = false,
    val micMuted: Boolean = false,
    val cameraOff: Boolean = false,
    val hasCamera: Boolean = true,
    val frontCamera: Boolean = true,
    val signalingOnline: Boolean = false,
    val voiceVolume: Float = 1f,
    /** The other person is talking (detected before their voice reaches your ears). */
    val remoteSpeaking: Boolean = false,
    val replaying: Boolean = false,
    val canReplay: Boolean = false,
    /** Smart dip turned itself off because the music app pauses instead of ducking. */
    val smartDuckUnsupported: Boolean = false,
    /** Which network the media is flowing over, once known. */
    val callPath: CallPath? = null,
    /** What Earshot is doing to keep the shared radio free for the earbuds, if anything. */
    val radioNote: String? = null,
    /** How far her video is held back to match the Bluetooth audio delay; null when not. */
    val lipSync: LipSync.Plan? = null,
    /** Where her voice spends its time on the way to your ear, once stats arrive. */
    val delay: DelayBreakdown? = null,
    /** A Hi-Fi call temporarily using the earbuds' mic (and the call link). */
    val earbudMic: Boolean = false,
    /** The earbuds can carry a call, so the earbud mic can be switched on. */
    val earbudMicAvailable: Boolean = false,
    /** Shown when connecting takes long: what's probably wrong. */
    val connectHint: String? = null,
    /** Their voice is paused because the earbuds went away mid-call; see [OutputHold]. */
    val outputHeld: Boolean = false,
    /** Echo cancellation switched on mid-call because the call now plays out loud. */
    val echoGuard: Boolean = false,
    /** Text chat with the other person, oldest first. */
    val chat: List<ChatMessage> = emptyList(),
    /** Their latest message, for the bubble and the notification. */
    val lastIncomingChat: ChatMessage? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = phase != CallPhase.ENDED && phase != CallPhase.FAILED

    /** Older apps and browsers don't have chat; their join message doesn't list it. */
    val chatAvailable: Boolean
        get() = remotePeer?.client?.capabilities?.contains(Chat.CAPABILITY) == true
}
