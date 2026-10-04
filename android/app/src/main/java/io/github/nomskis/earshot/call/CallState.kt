package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.calls.OutgoingRing
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
    /** Their camera is paused because their phone is in a pocket. */
    val inPocket: Boolean = false,
    /** Their video is paused because the connection can't carry it next to the voice. */
    val weakConnection: Boolean = false,
    /** The network their media goes over ("wifi", "cellular"), when they say. */
    val network: String? = null,
    /** How their uplink is doing ("tight", "starved"); null when fine or not said. */
    val uplink: String? = null,
    /** Their Wi-Fi shares its radio with Bluetooth earbuds. */
    val radioShared: Boolean = false,
)

/** A contact being rung from this call. [keepsTrying]: unreachable for now, and still ringing again. */
data class OutgoingCall(val name: String, val status: OutgoingRing.Status, val keepsTrying: Boolean = false)

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
    /** The camera is paused while the phone is in a pocket (the proximity sensor is covered). */
    val cameraPaused: Boolean = false,
    val hasCamera: Boolean = true,
    val frontCamera: Boolean = true,
    /** Flip is on: your video is mirrored, for you and the other person alike. */
    val flipped: Boolean = false,
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
    /** How the call is routed when that's been chosen for it: through the relay, or direct after the relay failed. */
    val routeNote: String? = null,
    /** How far her video is held back to match the Bluetooth audio delay; null when not. */
    val lipSync: LipSync.Plan? = null,
    /** Where her voice spends its time on the way to your ear, once stats arrive. */
    val delay: DelayBreakdown? = null,
    /** A Hi-Fi call temporarily using the earbuds' mic (and the call link). */
    val earbudMic: Boolean = false,
    /** The earbuds can carry a call, so the earbud mic can be switched on. */
    val earbudMicAvailable: Boolean = false,
    /** Our video is paused because the connection can't carry it next to the voice ([MediaBudget]). */
    val videoPausedForVoice: Boolean = false,
    /** Outgoing video lightened because the phone is warm; null when it isn't. */
    val thermal: ThermalPlan? = null,
    /** Shown when connecting takes long: what's probably wrong. */
    val connectHint: String? = null,
    /** Their voice is paused because the earbuds went away mid-call; see [OutputHold]. */
    val outputHeld: Boolean = false,
    /** Echo cancellation switched on mid-call because the call now plays out loud. */
    val echoGuard: Boolean = false,
    /**
     * On the phone itself: true on the loudspeaker, false on the earpiece. Null on earbuds,
     * a headset, or in Hi-Fi (where the call plays like music, wherever that goes).
     */
    val speakerOn: Boolean? = null,
    /** Calling a contact: their name and how it's going, until they've joined. */
    val outgoing: OutgoingCall? = null,
    /** Who a direct call is with, for the title while they're not in the room. */
    val contactName: String? = null,
    /** When the call first connected (SystemClock.elapsedRealtime), for the call timer; null until then. */
    val connectedAt: Long? = null,
    /** How the call went, filled in when it ends. */
    val quality: CallQuality? = null,
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
