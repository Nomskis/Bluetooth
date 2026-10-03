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
    val error: String? = null,
) {
    val isActive: Boolean
        get() = phase != CallPhase.ENDED && phase != CallPhase.FAILED
}
