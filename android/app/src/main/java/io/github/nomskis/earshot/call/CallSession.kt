package io.github.nomskis.earshot.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.audio.AudioProfile
import io.github.nomskis.earshot.audio.CallAudioController
import io.github.nomskis.earshot.audio.LinkConditions
import io.github.nomskis.earshot.audio.RemoteVoiceTap
import io.github.nomskis.earshot.audio.ReplayPlayer
import io.github.nomskis.earshot.audio.SmartDuck
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.calls.OutgoingRing
import io.github.nomskis.earshot.calls.Ringback
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.signaling.Capabilities
import io.github.nomskis.earshot.signaling.CandidatePayload
import io.github.nomskis.earshot.signaling.ClientInfo
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ErrorCodes
import io.github.nomskis.earshot.signaling.IceServerConfig
import io.github.nomskis.earshot.signaling.PeerInfo
import io.github.nomskis.earshot.signaling.ServerMessage
import io.github.nomskis.earshot.signaling.ServerUrls
import io.github.nomskis.earshot.signaling.SignalData
import io.github.nomskis.earshot.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * One call in one room. Implements the same negotiation algorithm as the web
 * client (web/js/call.js); docs/protocol.md describes it.
 *
 * Everything runs on a single "call thread" as an actor: server messages,
 * WebRTC callbacks, timers and UI actions all become [Event]s processed one at
 * a time, so there are no races between them.
 *
 * Roles: the peer with the higher `seq` (who joined later) makes offers. Each
 * peer connection has a random session id so late messages from an old
 * connection are ignored.
 */
class CallSession(
    context: Context,
    val room: String,
    serverBase: String,
    private val peerId: String,
    private val settings: AppSettings,
    val profile: AudioProfile,
    private val eglBase: EglBase,
    private val http: OkHttpClient,
    private val withVideo: Boolean,
    /** How to treat the radio Wi-Fi shares with Bluetooth; updated as the network changes. */
    radioPlan: RadioPlan = RadioPlan.NONE,
    /** Our contact card (name and inbox address), sent to the other side so they can call us directly. */
    private val me: Chat.Frame.Contact? = null,
    /** A chat message in this call, sent or received, with the contact at this address. */
    private val onChat: (address: String, message: ChatMessage) -> Unit = { _, _ -> },
    /** Their contact card arrived; save it. Called on the call thread. */
    private val onContact: (Contact) -> Unit = {},
    /** Set when this call rings a contact (instead of waiting for someone with the link). */
    private val outgoing: OutgoingRing? = null,
    /** Who a direct call is with (the contact rung, or who rang us), for the title. */
    contactName: String? = outgoing?.contact?.name,
    /** Their inbox address when known from the start (a direct call); the contact card fills it in otherwise. */
    remoteAddress: String? = outgoing?.contact?.address,
    /** What the last call with them, from this kind of network, learned about the route. */
    private val startFrom: LinkMemory? = null,
    /** Our network when the call starts ("wifi", "cellular"), the half of the route that's ours. */
    private val network: String? = null,
    /** At the end: what this call learned, for the next one ([LinkMemory]). Called on the call thread. */
    private val onLearned: (LinkMemory) -> Unit = {},
    /**
     * This phone is still ringing: join and connect now, but send and play nothing and leave the
     * audio mode, the music and the camera alone until [answer] ([Capabilities.RINGING]). The call
     * is then live the moment it's answered, instead of only starting to connect.
     */
    ringing: Boolean = false,
    /** Joins the room a ring invited us to, where another device of ours may still be leaving. */
    private val answersRing: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "EarshotCall") }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val wsUrl = ServerUrls.webSocketUrl(serverBase)

    private val _state = MutableStateFlow(
        CallState(
            phase = CallPhase.CONNECTING,
            room = room,
            inviteLink = ServerUrls.inviteLink(serverBase, room),
            audioMode = profile.mode,
            voiceVolume = settings.voiceVolume,
            frontCamera = !settings.startWithBackCamera,
            hasCamera = true,
            // A voice call is a call with the camera off: one tap turns it into a video call.
            cameraOff = !withVideo,
            contactName = contactName,
        ),
    )
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** Renderers attach here. These outlive individual peer connections. */
    val remoteVideo = ProxyVideoSink()
    /** Sits between her video track and [remoteVideo], holding frames back for lip sync. */
    private val lipSyncSink = DelayedVideoSink(remoteVideo)
    val localPreview = ProxyVideoSink()
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    private lateinit var engine: RtcEngine
    private lateinit var signaling: SignalingClient
    private val audioController = CallAudioController(appContext)

    /** Hears her audio as it enters Android, before the Bluetooth delay. Lives across reconnects. */
    private val voiceTap = RemoteVoiceTap { speaking -> post(Event.RemoteSpeaking(speaking)) }
    private var smartDuck: SmartDuck? = null
    private val replayPlayer = ReplayPlayer(profile.playbackAttributes)

    // Negotiation state. Only touched on the call thread.
    private var iceServers: List<IceServerConfig> = emptyList()
    private var mySeq = 0
    private var remote: PeerInfo? = null
    private var link: Link? = null
    private var lastOfferReceivedAt = 0L
    private var recoveryJob: Job? = null
    private var offerTimeoutJob: Job? = null
    private var requestOfferJob: Job? = null
    private var finished = false
    private var radioPlan = radioPlan
    private var statsJob: Job? = null
    private val delayTracker = DelayTracker()
    private var playoutMs: Double? = null
    private var playoutMeasured = false
    /** Lives across reconnects, so what's unacknowledged goes again on the next connection. */
    private val chat = ChatLog()
    private var lastPeerId: String? = null
    /** The network seemed to choke on priority-marked packets; leave them unmarked for this call. */
    private var markingBroken = false
    private var stuckJob: Job? = null
    /** Leaves Wi-Fi that's up but losing packets, for mobile data on standby. */
    private val steering = PathSteering()
    /** Media has gone over Wi-Fi on this call, so mobile data later means Wi-Fi gave out. */
    private var wasOnWifi = false
    /** The audio packet length we ask the other side for; lives across reconnects, like the network it reflects. */
    private val packetTime = PacketTime(startFrom?.packetStep?.takeIf { CallTuning.ADAPTIVE_PACKET_TIME } ?: PacketTime.START)
    /** What the route carries, learned as the call goes, for reconnects within it and for the next call. */
    private val learner = LinkLearner()
    @Volatile
    private var remoteAddress = remoteAddress

    /** Their inbox address: known from the start for a direct call, else from their contact card. */
    val theirAddress: String? get() = remoteAddress
    /** Where a new connection's voice and send estimate start: the last connection's, else the last call's. */
    private var startLevel = startFrom?.level ?: MediaBudget.Level.FULL
    private var startBitrateBps = startFrom?.startBitrateBps
    /** The relay route was tried on this call and didn't connect ([RelayRoute]). */
    private var relayFailed = false
    /** Lighter video to them while their Wi-Fi uplink starves and it helps. */
    private val airtime = AirtimeShare()

    /** What we tell them about our side of the route; see [AirtimeShare] and [PacketTime.floor]. */
    private data class LinkReport(val network: String?, val uplink: String?, val radioShared: Boolean)
    private var linkReport = LinkReport(null, null, false)
    private var pendingReport: LinkReport? = null
    private var pendingReportCount = 0
    /** How the call is going, for the history and its report. */
    private val qualityTracker = CallQualityTracker()
    private val ringback = Ringback(
        if (profile.mode == AudioMode.HIFI) android.media.AudioManager.STREAM_MUSIC else android.media.AudioManager.STREAM_VOICE_CALL,
    )
    private var ringTimeoutJob: Job? = null
    private var ringRetryJob: Job? = null
    private var gaveUpJob: Job? = null

    /** Still ringing here: connected ahead of the answer, sending and playing nothing. */
    private var ringing = ringing
    /** They joined while their phone still rings ([Capabilities.RINGING]): we keep ringing them and send nothing. */
    private var remoteRinging = false
    /** Nothing goes either way until the ringing phone is answered. */
    private val holding: Boolean get() = ringing || remoteRinging
    /** The connection's own phase; until their phone is answered the call shows WAITING. */
    private var linkPhase = CallPhase.CONNECTING
    private lateinit var join: ClientMessage.Join
    private var roomFullRetries = 0
    /** Their contact card, kept until the call that's ringing here is answered: only then is it a call with them. */
    private var pendingCard: Chat.Frame.Contact? = null
    /** Answered here; the hold lifts once the sound has somewhere to go ([answerNow]). */
    private var answered = false

    /** One RTCPeerConnection and everything tied to it. */
    private class Link(
        val pc: PeerConnection,
        val session: String,
        var tracks: SendTracks,
        val relayOnly: Boolean,
        /** Shares what this connection can carry between our voice and our video, voice first. */
        val budget: MediaBudget,
    ) {
        val pendingCandidates = mutableListOf<IceCandidate>()
        /** ICE has been connected at least once on this connection. */
        var everConnected = false
        var tracksAdded = false
        var remoteAudio: AudioTrack? = null
        var remoteVideoTrack: VideoTrack? = null
        var chatChannel: DataChannel? = null
        /** When priority marks were switched on for this connection; 0 = not marked. */
        var markedAt = 0L
        /** Last stats sample's cumulative audio bytes and packets sent, for rates. */
        var audioBytes: Double? = null
        var audioPackets: Double? = null
        var bytesAt = 0L
        /** The audio packet length the last description we sent asked for. */
        var askedPacketMs: Int? = null
        /** When we last renegotiated for a new packet length, to retry a lost one now and then. */
        var renegotiatedAt = 0L

        val isHealthy: Boolean
            get() = pc.iceConnectionState().let {
                it == IceConnectionState.CONNECTED || it == IceConnectionState.COMPLETED
            }
    }

    private sealed interface Event {
        data class Server(val message: ServerMessage) : Event
        data class SignalingStateChanged(val state: SignalingClient.State) : Event
        class IceState(val link: Link, val state: IceConnectionState) : Event
        class LocalCandidate(val link: Link, val candidate: IceCandidate) : Event
        class Track(val link: Link, val transceiver: RtpTransceiver) : Event
        class Recover(val link: Link) : Event
        class OfferTimeout(val link: Link) : Event
        data class RequestOfferDue(val askedAt: Long) : Event
        data class SetMicMuted(val muted: Boolean) : Event
        data class SetCameraOff(val off: Boolean) : Event
        data class SetSpeaker(val on: Boolean) : Event
        data class SetCameraPaused(val paused: Boolean) : Event
        data object SwitchCamera : Event
        data class SetFlipped(val on: Boolean) : Event
        data class CameraSwitched(val front: Boolean) : Event
        data class SetVoiceVolume(val volume: Float) : Event
        data object NetworkChanged : Event
        data class RemoteSpeaking(val speaking: Boolean) : Event
        data object ToggleReplay : Event
        data object ReplayFinished : Event
        data object SmartDuckUnsupported : Event
        data class UpdateRadio(val plan: RadioPlan) : Event
        data class SetEarbudMic(val on: Boolean) : Event
        data class SetLipSync(val plan: LipSync.Plan?, val playoutMs: Double?, val measured: Boolean) : Event
        class Stats(val link: Link, val report: RTCStatsReport) : Event
        class Renegotiate(val link: Link) : Event
        class RelayCheck(val link: Link) : Event
        class ChatChannelState(val link: Link, val open: Boolean) : Event
        class ChatIncoming(val link: Link, val text: String) : Event
        data class SendChat(val text: String) : Event
        data class SetEchoCancellation(val on: Boolean) : Event
        data class SetOutputHeld(val held: Boolean) : Event
        data object StuckCheck : Event
        data class SetThermal(val plan: ThermalPlan?) : Event
        data object RingTimeout : Event
        data object RingRetry : Event
        data object GiveUp : Event
        data object Answer : Event
        data object Unhold : Event
        data object Rejoin : Event
        data object HangUp : Event
    }

    // --- public API (any thread) ---------------------------------------------------

    fun start() {
        scope.launch {
            try {
                setUp()
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the call", e)
                finish(CallPhase.FAILED, "Could not start the call: ${e.message}")
                return@launch
            }
            for (event in events) {
                try {
                    handle(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling $event", e)
                }
                if (finished) break
            }
        }
    }

    fun setMicMuted(muted: Boolean) = post(Event.SetMicMuted(muted))
    fun setCameraOff(off: Boolean) = post(Event.SetCameraOff(off))

    /** Loudspeaker or earpiece, for a call on the phone itself. */
    fun setSpeaker(on: Boolean) = post(Event.SetSpeaker(on))
    /** Pause the camera while the phone is in a pocket; separate from the user's own camera switch. */
    fun setCameraPaused(paused: Boolean) = post(Event.SetCameraPaused(paused))
    fun switchCamera() = post(Event.SwitchCamera)
    fun setFlipped(on: Boolean) = post(Event.SetFlipped(on))
    fun setVoiceVolume(volume: Float) = post(Event.SetVoiceVolume(volume))
    fun onNetworkChanged() = post(Event.NetworkChanged)
    fun toggleReplay() = post(Event.ToggleReplay)
    fun updateRadioPlan(plan: RadioPlan) = post(Event.UpdateRadio(plan))
    fun setEarbudMic(on: Boolean) = post(Event.SetEarbudMic(on))
    /**
     * [playoutMs] is the app-to-ear delay in use (from the delay tuner when [measured]),
     * [plan] how far to hold video back for it.
     */
    fun setPlayout(plan: LipSync.Plan?, playoutMs: Double?, measured: Boolean) = post(Event.SetLipSync(plan, playoutMs, measured))
    fun sendChat(text: String) = post(Event.SendChat(text))
    /** For when the call moves between earbuds and a loudspeaker; no-op if unchanged. */
    fun setEchoCancellation(on: Boolean) = post(Event.SetEchoCancellation(on))
    /** Their voice paused because the earbuds went away ([OutputHold]); false plays it again. */
    fun setOutputHeld(held: Boolean) = post(Event.SetOutputHeld(held))
    /** How far to lighten outgoing video for the phone's temperature; null = not at all. */
    fun setThermal(plan: ThermalPlan?) = post(Event.SetThermal(plan))
    /** A call that was ringing here ([ringing]) was answered: start the sound and camera over the connection made meanwhile. */
    fun answer() = post(Event.Answer)
    fun hangUp() = post(Event.HangUp)

    private fun post(event: Event) {
        events.trySend(event)
    }

    // --- setup / teardown ------------------------------------------------------------

    private fun setUp() {
        engine = RtcEngine(
            context = appContext,
            eglBase = eglBase,
            profile = profile,
            videoQuality = settings.videoQuality,
            startWithBackCamera = settings.startWithBackCamera,
            camera = true,
            localPreview = localPreview,
        )
        engine.flipped = settings.flip
        _state.update {
            it.copy(
                hasCamera = engine.hasVideo,
                frontCamera = engine.isFrontCamera,
                flipped = settings.flip,
                radioNote = RadioPlan.describe(radioPlan, null),
            )
        }
        if (!ringing) beginLocalMedia()

        join = ClientMessage.Join(
            room = room,
            peerId = peerId,
            name = settings.displayName,
            client = ClientInfo(
                platform = "android",
                version = BuildConfig.VERSION_NAME,
                capabilities = listOfNotNull(
                    "hifi-audio",
                    Chat.CAPABILITY,
                    Capabilities.RENEGOTIATE,
                    Capabilities.RELAY_ROUTE.takeIf { settings.relayRoute },
                    Capabilities.RINGING.takeIf { ringing },
                ),
            ),
        )
        signaling = SignalingClient(http, wsUrl, join, scope)
        scope.launch { for (message in signaling.incoming) post(Event.Server(message)) }
        scope.launch { signaling.state.collect { post(Event.SignalingStateChanged(it)) } }
        signaling.connect()
    }

    /** The audio mode, ducking and the camera: from the start, or once a call that rang here is answered. */
    private fun beginLocalMedia() {
        // Like the phone app: a video call on the loudspeaker, a voice call at your ear.
        audioController.begin(profile, speaker = !_state.value.cameraOff)
        _state.update { it.copy(speakerOn = audioController.speaker()) }
        _state.update { it.copy(earbudMicAvailable = profile.mode == AudioMode.HIFI && audioController.earbudMicAvailable()) }
        // Ducking only makes sense when the call plays next to music, i.e. in Hi-Fi mode.
        if (settings.smartDuck && profile.mode == AudioMode.HIFI) {
            smartDuck = SmartDuck(appContext) { post(Event.SmartDuckUnsupported) }
        }
        applyCamera()
    }

    private fun finish(phase: CallPhase, error: String? = null) {
        if (finished) return
        finished = true
        replayPlayer.stop()
        ringback.stop()
        smartDuck?.release()
        learned()?.let(onLearned)
        // Never answered here: WebRTC prepared the playback while it rang but only frees playback it
        // started. Start it silent, so closing stops it and frees Android's AudioTrack now, not at GC.
        if (ringing) link?.let {
            engine.setPlaybackMuted(true)
            it.pc.setAudioPlayout(true)
        }
        closeLink()
        lipSyncSink.release()
        if (::signaling.isInitialized) signaling.close()
        audioController.end()
        if (::engine.isInitialized) engine.release()
        // A report is a nice-to-have; nothing about it may stop a call from ending.
        val quality = if (_state.value.connectedAt != null) runCatching { qualityTracker.summary() }.getOrNull() else null
        _state.update { it.copy(phase = phase, error = error, signalingOnline = false, hasRemoteVideo = false, quality = quality) }
        events.close()
        scope.cancel()
        executor.shutdown()
    }

    // --- event handling (call thread) ------------------------------------------------

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Server -> onServerMessage(event.message)
            is Event.SignalingStateChanged -> onSignalingState(event.state)
            is Event.IceState -> onIceState(event.link, event.state)
            is Event.LocalCandidate -> if (event.link === link) {
                val c = event.candidate
                sendSignal(SignalData.Candidate(event.link.session, CandidatePayload(c.sdp, c.sdpMid, c.sdpMLineIndex)))
            }
            is Event.Track -> onTrack(event.link, event.transceiver)
            is Event.Recover -> recover(event.link)
            is Event.OfferTimeout -> {
                val l = event.link
                if (l === link && l.pc.signalingState() == SignalingState.HAVE_LOCAL_OFFER) {
                    val offer = l.pc.localDescription
                    if (l.isHealthy && offer != null) {
                        // A renegotiation whose answer got lost; the call itself is fine, so don't tear it down.
                        Log.w(TAG, "No answer to our offer; sending it again")
                        sendSignal(SignalData.Offer(l.session, tune(offer.description)))
                        armOfferTimeout(l)
                    } else {
                        Log.w(TAG, "No answer to our offer, starting over")
                        startSession()
                    }
                }
            }
            is Event.Renegotiate -> renegotiate(event.link)
            is Event.RelayCheck -> {
                val l = event.link
                if (l === link && !l.everConnected) {
                    // Never came up through the relay: go direct for the rest of this call.
                    Log.w(TAG, "No connection through the relay; going direct")
                    relayFailed = true
                    _state.update { it.copy(routeNote = "The relay didn't connect, so this call goes direct") }
                    if (isOfferer()) startSession() else sendSignal(SignalData.RequestOffer(null))
                }
            }
            is Event.RequestOfferDue -> {
                if (lastOfferReceivedAt > event.askedAt) return
                if (link?.isHealthy == true) return
                sendSignal(SignalData.RequestOffer(link?.session))
            }
            is Event.SetMicMuted -> {
                link?.tracks?.audio?.setEnabled(!event.muted)
                _state.update { it.copy(micMuted = event.muted) }
                sendMediaState()
            }
            is Event.SetCameraOff -> {
                _state.update { it.copy(cameraOff = event.off) }
                applyCamera()
                // Turning a call at your ear into a video call: you're looking at the screen now.
                if (!event.off && audioController.speaker() == false) setSpeakerNow(true)
                sendMediaState()
            }
            is Event.SetSpeaker -> setSpeakerNow(event.on)
            is Event.SetCameraPaused -> if (event.paused != _state.value.cameraPaused) {
                _state.update { it.copy(cameraPaused = event.paused) }
                applyCamera()
                sendMediaState()
            }
            Event.SwitchCamera -> engine.switchCamera { front -> post(Event.CameraSwitched(front)) }
            is Event.SetFlipped -> {
                engine.flipped = event.on
                _state.update { it.copy(flipped = event.on) }
            }
            is Event.CameraSwitched -> _state.update { it.copy(frontCamera = event.front) }
            is Event.SetVoiceVolume -> {
                link?.remoteAudio?.setVolume(event.volume.toDouble())
                _state.update { it.copy(voiceVolume = event.volume) }
            }
            Event.NetworkChanged -> if (!_state.value.signalingOnline) signaling.reconnectNow()
            is Event.RemoteSpeaking -> {
                smartDuck?.onRemoteSpeaking(event.speaking)
                if (settings.headStartCue) _state.update { it.copy(remoteSpeaking = event.speaking, canReplay = true) }
                else _state.update { it.copy(canReplay = true) }
            }
            Event.ToggleReplay -> replayOrStop()
            Event.ReplayFinished -> {
                link?.remoteAudio?.setVolume(_state.value.voiceVolume.toDouble())
                _state.update { it.copy(replaying = false) }
            }
            Event.SmartDuckUnsupported -> {
                smartDuck = null
                _state.update { it.copy(smartDuckUnsupported = true) }
            }
            is Event.UpdateRadio -> onRadioPlan(event.plan)
            is Event.SetEarbudMic -> setEarbudMicNow(event.on)
            is Event.SetLipSync -> {
                lipSyncSink.delayMs = event.plan?.videoDelayMs ?: 0
                playoutMs = event.playoutMs
                playoutMeasured = event.measured
                _state.update { it.copy(lipSync = event.plan) }
            }
            is Event.Stats -> if (event.link === link) onStats(event.link, event.report)
            is Event.ChatChannelState -> if (event.link === link) {
                val channel = event.link.chatChannel
                if (event.open && channel != null) {
                    chat.attach { text -> channel.sendText(text) }
                    me?.let { channel.sendText(Chat.encode(it)) }
                } else {
                    chat.detach()
                }
                publishChat()
            }
            is Event.ChatIncoming -> if (event.link === link) {
                val card = Chat.decode(event.text) as? Chat.Frame.Contact
                if (card != null) {
                    if (ringing) pendingCard = card else takeCard(card)
                    return
                }
                val incoming = chat.receive(event.text)
                // Kept in the conversation with them too, when they're a contact.
                incoming?.let { message -> remoteAddress?.let { onChat(it, message) } }
                publishChat()
                if (incoming != null) _state.update { it.copy(lastIncomingChat = incoming) }
            }
            is Event.SendChat -> {
                chat.send(event.text)?.let { message -> remoteAddress?.let { onChat(it, message) } }
                publishChat()
            }
            is Event.SetEchoCancellation -> setEchoCancellationNow(event.on)
            is Event.SetThermal -> if (event.plan != _state.value.thermal) {
                if (event.plan != null) Log.i(TAG, "Phone is warm; lighter video: $event")
                _state.update { it.copy(thermal = event.plan) }
                link?.let(::applyVideoCap)
            }
            Event.StuckCheck -> if (_state.value.phase == CallPhase.NEGOTIATING || _state.value.phase == CallPhase.RECONNECTING) {
                _state.update { it.copy(connectHint = ConnectHint.forStuck(iceServers)) }
            }
            is Event.SetOutputHeld -> if (event.held != _state.value.outputHeld) {
                engine.setPlaybackMuted(event.held)
                _state.update { it.copy(outputHeld = event.held) }
            }
            Event.RingTimeout -> outgoing?.let { ring ->
                ring.timeOut()?.let(signaling::send)
                onOutgoingChanged()
            }
            Event.RingRetry -> if (remote == null) ringContact()
            // The reason goes to the home screen, which is where you land.
            Event.GiveUp -> finish(CallPhase.ENDED, outgoing?.outcome)
            Event.Answer -> answerNow()
            Event.Unhold -> unholdHere()
            // Only while connected; a reconnect joins by itself.
            Event.Rejoin -> if (_state.value.signalingOnline) signaling.send(join)
            Event.HangUp -> {
                outgoing?.hangUp()?.let(signaling::send)
                finish(CallPhase.ENDED)
            }
        }
    }

    private fun takeCard(card: Chat.Frame.Contact) {
        val name = card.name.ifBlank { remote?.name.orEmpty() }
        remoteAddress = card.address
        onContact(Contact(name, card.address, System.currentTimeMillis()))
    }

    private fun setSpeakerNow(on: Boolean) {
        audioController.setSpeaker(on)
        _state.update { it.copy(speakerOn = audioController.speaker()) }
    }

    /** Hi-Fi only: the earbuds' mic for a while (call quality), then back to the music link. */
    private fun setEarbudMicNow(on: Boolean) {
        if (profile.mode != AudioMode.HIFI || on == _state.value.earbudMic) return
        if (on) {
            if (!audioController.beginEarbudMic()) {
                _state.update { it.copy(earbudMicAvailable = false) }
                return
            }
            if (!engine.useEarbudMic(true)) {
                audioController.endEarbudMic()
                return
            }
        } else {
            engine.useEarbudMic(false)
            audioController.endEarbudMic()
        }
        _state.update { it.copy(earbudMic = on) }
        sendMediaState()
    }

    /**
     * Earbuds out mid-call (battery, back in the case) put the call on the
     * loudspeaker, which the phone's mic hears: the other side would get an
     * echo of themselves. Moves the microphone to a source with the echo
     * canceller on (or back off once earbuds are in again).
     */
    private fun setEchoCancellationNow(on: Boolean) {
        val oldSource = engine.switchEchoCancellation(on) ?: return
        link?.let(::moveToNewAudioTrack)
        oldSource.dispose()
        Log.i(TAG, "Echo cancellation ${if (on) "on" else "off"} for the new output")
        _state.update { it.copy(echoGuard = on && !profile.softwareEchoCancellation) }
    }

    private fun moveToNewAudioTrack(l: Link) {
        val track = engine.createAudioTrack().also { it.setEnabled(!_state.value.micMuted) }
        val old = l.tracks.audio
        if (l.tracksAdded) {
            val sender = l.pc.senders.firstOrNull { runCatching { it.track()?.kind() }.getOrNull() == MediaStreamTrack.AUDIO_TRACK_KIND }
            // The sender takes ownership of the new track (disposed with the connection).
            if (sender == null || !sender.setTrack(track, true)) {
                Log.w(TAG, "Could not move the microphone to the new audio source")
                track.dispose()
                return
            }
        }
        l.tracks = SendTracks(track, l.tracks.video)
        old.dispose()
    }

    // --- connecting while it rings ------------------------------------------------------

    /** Answered here: what the call held back starts now, over the connection made while it rang. */
    private fun answerNow() {
        if (!ringing || answered) return
        answered = true
        Log.i(TAG, if (link?.isHealthy == true) "Answered over a connection that's already up" else "Answered; still connecting")
        beginLocalMedia()
        if (!audioController.awaitingBluetoothRoute()) {
            unholdHere()
            return
        }
        // Headset mode on Bluetooth earbuds: Android brings their call link up in the background and
        // plays the call on the earpiece meanwhile. With the connection already made, the first words
        // would land there; wait for the earbuds (a second or two at most).
        scope.launch {
            val until = SystemClock.elapsedRealtime() + BLUETOOTH_ROUTE_WAIT_MS
            while (audioController.awaitingBluetoothRoute() && SystemClock.elapsedRealtime() < until) delay(ROUTE_POLL_MS)
            post(Event.Unhold)
        }
    }

    /** The sound and camera are ready: let the call flow both ways, and tell them it's answered. */
    private fun unholdHere() {
        if (!ringing) return
        ringing = false
        if (_state.value.phase == CallPhase.CONNECTED) {
            _state.update { it.copy(connectedAt = it.connectedAt ?: SystemClock.elapsedRealtime()) }
        }
        link?.let(::applyHold)
        // Their phone keeps ringing until this arrives (or our voice does).
        sendMediaState()
        pendingCard?.let(::takeCard)
        pendingCard = null
    }

    /**
     * While a phone rings, the connection is made but carries nothing: our voice's stream isn't
     * started (so WebRTC doesn't even prepare the microphone, see RtcEngine.audioConstraints),
     * recording is off as well, nothing plays on the ringing phone, and video is inactive.
     * Applied to a new connection's senders before it's negotiated, so nothing slips out when
     * it first connects; WebRTC keeps recording and playout for all of this call's connections.
     */
    private fun applyHold(l: Link) {
        l.pc.setAudioRecording(!holding)
        engine.setAudioSending(l.pc, !holding)
        l.pc.setAudioPlayout(!ringing)
        applyVideoCap(l)
    }

    private fun stillRinging(peer: PeerInfo): Boolean = outgoing?.stillRinging(peer.client.capabilities) == true

    /** They're in the room: unless their phone is still ringing, the ring is over and it's a call. */
    private fun theyJoined() {
        if (outgoing == null || remoteRinging) return
        outgoing.onJoined()
        onOutgoingChanged()
    }

    /** Their phone was answered: the call starts over the connection made while it rang. */
    private fun theyAnswered() {
        if (!remoteRinging) return
        remoteRinging = false
        Log.i(TAG, if (link?.isHealthy == true) "They answered; the connection is already up" else "They answered; still connecting")
        theyJoined()
        setRemote(remote)
        setPhase(linkPhase)
        _state.update { it.copy(hasRemoteVideo = link?.remoteVideoTrack != null) }
        link?.let(::applyHold)
    }

    // --- sharing the radio with Bluetooth ----------------------------------------------

    private suspend fun onRadioPlan(plan: RadioPlan) {
        if (plan == radioPlan) return
        val old = radioPlan
        radioPlan = plan
        val l = link
        if (l != null) {
            if (old.preferCellular != plan.preferCellular) engine.setPreferCellular(l.pc, iceServers, plan.preferCellular || steering.prefersCellular)
            applyVideoCap(l)
            if (l.isHealthy) applyPacketPriority(l)
            // The cap on what they send us travels in the SDP; renegotiate if we're the one who offers.
            if (old.remoteVideoCap() != plan.remoteVideoCap() && isOfferer() && l.isHealthy &&
                l.pc.signalingState() == SignalingState.STABLE
            ) {
                sendOffer(l, iceRestart = false)
            }
        }
        _state.update { it.copy(radioNote = radioNote(plan, it.callPath)) }
    }

    private fun radioNote(plan: RadioPlan, path: CallPath?): String? = when {
        steering.prefersCellular && path == CallPath.CELLULAR -> "Wi-Fi here keeps dropping packets, so the call moved to mobile data"
        steering.prefersCellular -> "Wi-Fi here keeps dropping packets; moving the call to mobile data"
        // A call that's on mobile data from the start (the phone has no working Wi-Fi) has nothing to explain.
        else -> RadioPlan.describe(plan, path) ?: if (path == CallPath.CELLULAR && wasOnWifi) "Wi-Fi stalled, so the call moved to mobile data" else null
    }

    /** The radio plan's wish (2.4 GHz option) or the steering's (bad Wi-Fi). */
    private val preferCellular: Boolean get() = radioPlan.preferCellular || steering.prefersCellular

    private fun steer(l: Link, entries: Map<String, CallStats.Entry>) {
        if (!settings.mobileDataBackup) return
        val prefer = steering.update(CallStats.candidatePairs(entries), SystemClock.elapsedRealtime()) ?: return
        Log.i(TAG, if (prefer) "Wi-Fi is losing pings and mobile data isn't; preferring mobile data" else "Wi-Fi has recovered; letting ICE choose again")
        engine.setPreferCellular(l.pc, iceServers, preferCellular)
        _state.update { it.copy(radioNote = radioNote(radioPlan, it.callPath)) }
    }

    /** What we ask them to cap their video at. When mobile data is preferred we don't, so it isn't held back there. */
    private fun RadioPlan.remoteVideoCap(): Int? = if (preferCellular || !CallTuning.RADIO_VIDEO_CAP) null else wifiVideoCapKbps

    private fun applyVideoCap(l: Link) {
        val thermal = _state.value.thermal
        val kbps = listOfNotNull(
            radioPlan.videoCapFor(_state.value.callPath).takeIf { CallTuning.RADIO_VIDEO_CAP },
            thermal?.maxKbps,
            l.budget.videoCapBps?.let { it / 1000 },
            airtime.capKbps,
        ).minOrNull()
        engine.capVideoSend(l.pc, kbps, thermal?.scaleDownBy, thermal?.maxFps, active = !l.budget.videoPaused && !holding)
    }

    private fun applyPacketPriority(l: Link) {
        val mark = radioPlan.priorityMarking && !markingBroken
        if (mark && l.markedAt == 0L) l.markedAt = SystemClock.elapsedRealtime()
        if (!mark) l.markedAt = 0L
        engine.setPacketPriority(l.pc, mark)
    }

    /**
     * A few networks drop or delay marked packets. If the connection falters
     * right after the marks went on, take them off for the rest of the call.
     */
    private fun suspectMarking(l: Link) {
        if (markingBroken || l.markedAt == 0L) return
        if (SystemClock.elapsedRealtime() - l.markedAt > MARKING_TRIAL_MS) return
        Log.w(TAG, "Connection faltered right after marking packets for priority; sending them unmarked")
        markingBroken = true
        applyPacketPriority(l)
    }

    private fun onStats(l: Link, report: RTCStatsReport) {
        val entries = report.statsMap.mapValues { (_, s) -> CallStats.Entry(s.type, s.members) }
        // A ringing phone sends no voice, so their voice arriving means they answered, even if
        // both messages saying so were lost with a dead connection to the server.
        if (remoteRinging && (CallStats.inboundAudioCounters(entries)?.packetsReceived ?: 0.0) > 0.0) theyAnswered()
        // While it rings nothing flows yet: no rates, estimates or quality to go by.
        if (!holding) followMedia(l, entries)
        steer(l, entries)
        val path = RadioPlan.pathFor(CallStats.selectedNetworkType(entries)) ?: return
        if (path == _state.value.callPath) return
        if (path == CallPath.WIFI) wasOnWifi = true
        Log.i(TAG, "Media now flows over $path")
        _state.update { it.copy(callPath = path, radioNote = radioNote(radioPlan, path)) }
        applyVideoCap(l)
    }

    private fun followMedia(l: Link, entries: Map<String, CallStats.Entry>) {
        followMediaBudget(l, entries)
        qualityTracker.update(entries, voiceReduced = l.budget.voiceCapBps != null)
        if (l.isHealthy) CallStats.availableOutgoingBitrate(entries)?.let(learner::addEstimate)
        followTheirLink(l, SystemClock.elapsedRealtime())
        followPacketTime(l, entries)
        val squeeze = when {
            l.budget.videoPaused -> LinkQuality.POOR
            l.budget.level != MediaBudget.Level.FULL -> LinkQuality.FAIR
            else -> LinkQuality.GOOD
        }
        val delay = delayTracker.update(entries, playoutMs, playoutMeasured, packetTime.ms).copy(sendSqueeze = squeeze)
        val now = _state.value
        qualityTracker.context(
            theirNetwork = now.remoteMedia.network,
            video = !now.sendsNoVideo || (now.hasRemoteVideo && !now.remoteMedia.cameraOff),
            mouthToEarMs = delay.totalMs,
        )
        followLinkReport(delay)
        // Earbuds can connect mid-call; keep the earbud-mic button honest.
        val micAvailable = profile.mode == AudioMode.HIFI && (_state.value.earbudMic || audioController.earbudMicAvailable())
        _state.update { it.copy(delay = delay, earbudMicAvailable = micAvailable, speakerOn = audioController.speaker()) }
    }

    /**
     * Voice first: video gets what the voice really leaves (RED's copies included), the
     * voice gets leaner when even that's too little, and video pauses when the leanest voice
     * still doesn't leave room for a picture. Each comes back by itself.
     */
    private fun followMediaBudget(l: Link, entries: Map<String, CallStats.Entry>) {
        if (!CallTuning.VOICE_FIRST) return
        val now = SystemClock.elapsedRealtime()
        val bytes = CallStats.outboundBytes(entries, "audio")
        val packets = CallStats.outboundPackets(entries, "audio")
        val seconds = (now - l.bytesAt) / 1000.0
        val lastBytes = l.audioBytes
        val lastPackets = l.audioPackets
        // On the wire: what WebRTC counts, plus IP, UDP and the SRTP tag on each packet.
        val audioBps = if (bytes != null && packets != null && lastBytes != null && lastPackets != null && l.bytesAt > 0 && seconds > 0) {
            // Fewer when they've asked for longer packets; resends add a few.
            l.budget.packetsPerSecond = ((packets - lastPackets) / seconds).coerceIn(MIN_PACKET_RATE, MAX_PACKET_RATE)
            ((bytes - lastBytes) + (packets - lastPackets) * MediaBudget.TRANSPORT_OVERHEAD_BYTES) * 8 / seconds
        } else {
            null
        }
        l.audioBytes = bytes
        l.audioPackets = packets
        l.bytesAt = now
        val wantsVideo = l.tracks.video != null && !_state.value.sendsNoVideo
        val changes = l.budget.update(CallStats.availableOutgoingBitrate(entries), audioBps, wantsVideo, now)
        if (changes.voice) {
            Log.i(TAG, "Voice now ${l.budget.voiceCapBps?.let { "capped at ${it / 1000} kbps" } ?: "at full quality"} for this connection")
            engine.capAudioSend(l.pc, l.budget.voiceCapBps)
        }
        if (changes.video) applyVideoCap(l)
        if (l.budget.videoPaused != _state.value.videoPausedForVoice) {
            Log.i(TAG, if (l.budget.videoPaused) "Connection too weak for video next to the voice; pausing our video" else "Trying our video again")
            _state.update { it.copy(videoPausedForVoice = l.budget.videoPaused) }
            sendMediaState()
        }
    }

    /**
     * Tells them about our side of the route when it changes and has held for two
     * intervals (one lossy report isn't news): our network, how our uplink is doing
     * (from [MediaBudget] and what they report losing), and whether our Wi-Fi shares
     * its radio with the earbuds.
     */
    private fun followLinkReport(delay: DelayBreakdown) {
        val path = _state.value.callPath
        val next = LinkReport(
            network = when (path) {
                CallPath.WIFI -> AirtimeShare.WIFI
                CallPath.CELLULAR -> "cellular"
                else -> null
            },
            uplink = AirtimeShare.uplinkOf(delay.sendSqueeze, delay.sendLossPercent),
            radioShared = radioPlan.sharedRadio && path != CallPath.CELLULAR,
        )
        if (next == linkReport) {
            pendingReport = null
            return
        }
        if (next == pendingReport) pendingReportCount++ else {
            pendingReport = next
            pendingReportCount = 1
        }
        if (pendingReportCount < 2) return
        linkReport = next
        pendingReport = null
        sendMediaState()
    }

    /**
     * Their side of the route: lighter video from us while their Wi-Fi uplink starves
     * ([AirtimeShare]), and 20 ms packets at least while either phone's Wi-Fi shares its
     * radio with Bluetooth earbuds ([PacketTime.floor]).
     */
    private fun followTheirLink(l: Link, nowMs: Long) {
        val theirs = _state.value.remoteMedia
        if (CallTuning.AIRTIME_SHARE && airtime.update(theirs.network, theirs.uplink, nowMs)) {
            Log.i(TAG, airtime.capKbps?.let { "Their Wi-Fi uplink is struggling; our video to them capped at $it kbps" } ?: "Lifting the cap on our video to them")
            applyVideoCap(l)
        }
        val ourRadioShared = radioPlan.sharedRadio && _state.value.callPath != CallPath.CELLULAR
        packetTime.floor = if (ourRadioShared || theirs.radioShared) PacketTime.Step.MEDIUM else PacketTime.Step.SHORT
    }

    /**
     * Longer audio packets from them while their audio arrives with gaps RED can't
     * cover, shorter again once it's calm ([PacketTime]). What we ask for travels in
     * our description, so a change means renegotiating, without restarting ICE.
     */
    private fun followPacketTime(l: Link, entries: Map<String, CallStats.Entry>) {
        if (!CallTuning.ADAPTIVE_PACKET_TIME) return
        val now = SystemClock.elapsedRealtime()
        val counters = CallStats.inboundAudioCounters(entries)
        if (counters != null && packetTime.update(counters, now)) {
            Log.i(TAG, "Asking for ${packetTime.ms} ms audio packets")
        }
        if (l.askedPacketMs == null || l.askedPacketMs == packetTime.ms) return
        if (now - l.renegotiatedAt < RENEGOTIATE_RETRY_MS) return
        // As the answerer we can only ask for an offer, and only peers that renegotiate in place.
        if (!isOfferer() && remote?.client?.capabilities?.contains(Capabilities.RENEGOTIATE) != true) return
        l.renegotiatedAt = now
        post(Event.Renegotiate(l))
    }

    private suspend fun renegotiate(l: Link) {
        if (l !== link || !l.isHealthy || l.pc.signalingState() != SignalingState.STABLE || l.pc.remoteDescription == null) return
        if (isOfferer()) sendOffer(l, iceRestart = false) else sendSignal(SignalData.RequestOffer(l.session, iceRestart = false))
    }

    private fun watchStats(l: Link) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                l.pc.getStats { report -> post(Event.Stats(l, report)) }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    /** Plays the last few seconds of her voice again, with the live call turned down meanwhile. */
    private fun replayOrStop() {
        if (replayPlayer.isPlaying) {
            replayPlayer.stop()
            post(Event.ReplayFinished)
            return
        }
        val (pcm, rate) = voiceTap.lastSeconds(REPLAY_SECONDS)
        if (pcm.isEmpty()) return
        link?.remoteAudio?.setVolume(0.25 * _state.value.voiceVolume)
        _state.update { it.copy(replaying = true) }
        replayPlayer.play(pcm, rate) { post(Event.ReplayFinished) }
    }

    private suspend fun onServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Joined -> onJoined(message)
            is ServerMessage.PeerJoined -> onPeerJoined(message.peer)
            is ServerMessage.PeerLeft -> onPeerLeft(message.peerId)
            is ServerMessage.Signal -> onSignal(message.from, message.data)
            is ServerMessage.Error -> when (message.code) {
                ErrorCodes.ROOM_FULL -> if (answersRing && roomFullRetries < ROOM_FULL_RETRIES) {
                    // Another device of ours, connected while it rang, may still be on its way out.
                    roomFullRetries++
                    scope.launch {
                        delay(ROOM_FULL_RETRY_MS)
                        post(Event.Rejoin)
                    }
                } else {
                    finish(CallPhase.FAILED, "This room already has two people in it.")
                }
                ErrorCodes.BAD_ROOM -> finish(CallPhase.FAILED, "That room code is not valid.")
                else -> {
                    Log.w(TAG, "Server error ${message.code}: ${message.message}")
                    // Right after a ring, an error means this server can't ring phones.
                    if (message.code == ErrorCodes.BAD_REQUEST && outgoing?.refused() == true) onOutgoingChanged()
                }
            }
            ServerMessage.Pong -> Unit
            // Incoming rings and chat go to the inbox connection, not to calls.
            is ServerMessage.Listening, is ServerMessage.Incoming, is ServerMessage.RingCancelled,
            is ServerMessage.Message, is ServerMessage.MessageStatus,
            -> Unit
            // An answer while their early connection is here only says "answered, connecting": they may
            // be answering on a fresh connection (voice only, another device). That connection's own
            // media-state, or its voice arriving, lifts the hold (theyAnswered).
            is ServerMessage.RingStatus, is ServerMessage.RingAnswered -> if (outgoing?.onMessage(message) == true) onOutgoingChanged()
        }
    }

    private fun onSignalingState(state: SignalingClient.State) {
        when (state) {
            SignalingClient.State.Open -> _state.update { it.copy(signalingOnline = true, error = null) }
            is SignalingClient.State.Reconnecting -> _state.update {
                it.copy(
                    signalingOnline = false,
                    error = if (state.attempt >= 3 && it.phase == CallPhase.CONNECTING) {
                        "Can't reach the server. Still trying…"
                    } else {
                        it.error
                    },
                )
            }
            SignalingClient.State.Connecting -> _state.update { it.copy(signalingOnline = false) }
            is SignalingClient.State.Closed -> if (state.reason == "replaced") {
                finish(CallPhase.FAILED, "You joined this room from somewhere else.")
            }
        }
    }

    private suspend fun onJoined(message: ServerMessage.Joined) {
        // A rejoin brings fresh TURN credentials; a long call's next ICE restart should use them.
        if (message.iceServers != iceServers) link?.let { engine.updateIceServers(it.pc, message.iceServers, preferCellular) }
        iceServers = message.iceServers
        mySeq = message.seq
        val peer = message.peers.firstOrNull()
        if (peer == null) {
            closeLink()
            setRemote(null)
            setPhase(CallPhase.WAITING)
            ringContact()
            return
        }
        if (remote != null && remote?.peerId != peer.peerId) closeLink()
        remoteRinging = stillRinging(peer)
        setRemote(peer)
        // They arrived while we were reconnecting.
        theyJoined()
        // Our ring went with our old connection, so their phone would stop ringing; ring again
        // (their inbox takes it as the same call, keeping the connection it made).
        if (remoteRinging) ringContact()
        ensureNegotiated()
    }

    private suspend fun onPeerJoined(peer: PeerInfo) {
        if (remote?.peerId == peer.peerId && remote?.seq == peer.seq) return
        // A fresh join always means a fresh connection, even from a known peerId.
        closeLink()
        remoteRinging = stillRinging(peer)
        setRemote(peer)
        theyJoined()
        setPhase(CallPhase.NEGOTIATING)
        if (isOfferer()) startSession()
    }

    private fun onPeerLeft(peerId: String) {
        if (remote?.peerId != peerId) return
        closeLink()
        setRemote(null)
        setPhase(CallPhase.WAITING)
    }

    private suspend fun onSignal(from: String, data: SignalData) {
        if (remote?.peerId != from) return
        when (data) {
            is SignalData.Offer -> onOffer(data)
            is SignalData.Answer -> onAnswer(data)
            is SignalData.Candidate -> onRemoteCandidate(data)
            is SignalData.RequestOffer -> onRequestOffer(data)
            is SignalData.MediaState -> {
                if (data.ringing != true) theyAnswered()
                updateRemoteMedia(data)
            }
        }
    }

    private fun updateRemoteMedia(data: SignalData.MediaState) {
        _state.update {
            it.copy(
                remoteMedia = RemoteMedia(
                    data.micMuted,
                    data.cameraOff,
                    data.audioMode,
                    inPocket = data.inPocket == true,
                    weakConnection = data.weakConnection == true,
                    network = data.network,
                    uplink = data.uplink,
                    radioShared = data.radioShared == true,
                ),
            )
        }
    }

    // --- negotiation -----------------------------------------------------------------

    private fun isOfferer(): Boolean = remote?.let { mySeq > it.seq } ?: false

    private suspend fun ensureNegotiated() {
        val current = link
        if (current != null && current.isHealthy) return
        if (isOfferer()) {
            if (current != null && current.pc.signalingState() == SignalingState.STABLE && current.pc.remoteDescription != null) {
                sendOffer(current, iceRestart = true)
            } else {
                startSession()
            }
            return
        }
        // We answer. Offers queued on the server arrive right after `joined`,
        // so give them a moment before asking for a new one.
        if (current == null) setPhase(CallPhase.NEGOTIATING)
        val askedAt = System.currentTimeMillis()
        requestOfferJob?.cancel()
        requestOfferJob = scope.launch {
            delay(REQUEST_OFFER_DELAY_MS)
            post(Event.RequestOfferDue(askedAt))
        }
    }

    private suspend fun startSession() {
        closeLink()
        val l = createLink(Ids.random(9, "s"))
        link = l
        addLocalTracks(l)
        if (holding) applyHold(l)
        // Always offer to receive both kinds, even when we send no video ourselves.
        if (l.tracks.video == null) {
            l.pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
        }
        if (CallTuning.REDUNDANT_AUDIO) engine.preferRedundantAudio(l.pc)
        setPhase(CallPhase.NEGOTIATING)
        sendOffer(l, iceRestart = false)
    }

    private suspend fun sendOffer(l: Link, iceRestart: Boolean) {
        val offer = l.pc.awaitCreateOffer(iceRestart)
        if (link !== l) return
        l.pc.awaitSetLocal(offer)
        if (link !== l) return
        sendSignal(SignalData.Offer(l.session, tune(offer.description)))
        l.askedPacketMs = packetTime.ms
        armOfferTimeout(l)
    }

    private fun armOfferTimeout(l: Link) {
        offerTimeoutJob?.cancel()
        offerTimeoutJob = scope.launch {
            delay(OFFER_TIMEOUT_MS)
            post(Event.OfferTimeout(l))
        }
    }

    private suspend fun onOffer(data: SignalData.Offer) {
        if (isOfferer()) {
            Log.w(TAG, "Ignoring offer: this side is the offerer")
            return
        }
        lastOfferReceivedAt = System.currentTimeMillis()
        var l = link
        val fresh = l == null || l.session != data.session
        if (fresh) {
            closeLink()
            l = createLink(data.session)
            link = l
            setPhase(CallPhase.NEGOTIATING)
        }
        checkNotNull(l)
        l.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, data.sdp))
        if (link !== l) return
        if (fresh) {
            addLocalTracks(l)
            if (holding) applyHold(l)
        }
        if (CallTuning.REDUNDANT_AUDIO) engine.preferRedundantAudio(l.pc)
        val answer = l.pc.awaitCreateAnswer()
        if (link !== l) return
        l.pc.awaitSetLocal(answer)
        if (link !== l) return
        sendSignal(SignalData.Answer(l.session, tune(answer.description)))
        l.askedPacketMs = packetTime.ms
        flushCandidates(l)
    }

    /** Our tweaks to every description we send. */
    private fun tune(sdp: String): String {
        var tuned = sdp
        if (CallTuning.ADAPTIVE_PACKET_TIME) tuned = SdpTuning.askForPacketTime(tuned, packetTime.ms)
        if (CallTuning.HD_VOICE) tuned = SdpTuning.preferHdVoice(tuned)
        if (CallTuning.AUDIO_RESENDS) tuned = SdpTuning.requestAudioResends(tuned)
        return SdpTuning.capVideoBandwidth(tuned, radioPlan.remoteVideoCap())
    }

    private suspend fun onAnswer(data: SignalData.Answer) {
        val l = link ?: return
        if (l.session != data.session || l.pc.signalingState() != SignalingState.HAVE_LOCAL_OFFER) return
        offerTimeoutJob?.cancel()
        l.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, data.sdp))
        flushCandidates(l)
    }

    private fun onRemoteCandidate(data: SignalData.Candidate) {
        val l = link ?: return
        if (l.session != data.session) return
        val c = data.candidate
        val candidate = IceCandidate(c.sdpMid ?: "", c.sdpMLineIndex ?: 0, c.candidate)
        if (l.pc.remoteDescription != null) {
            if (!l.pc.addIceCandidate(candidate)) Log.w(TAG, "Rejected remote candidate")
        } else {
            l.pendingCandidates += candidate
        }
    }

    private suspend fun onRequestOffer(data: SignalData.RequestOffer) {
        if (!isOfferer()) return
        val l = link
        val stable = l != null && data.session == l.session && l.pc.signalingState() == SignalingState.STABLE &&
            l.pc.remoteDescription != null
        when {
            // They want to change what they ask for on a working connection: renegotiate in place.
            // If we're mid-negotiation or it's an old session, they ask again later.
            data.iceRestart == false -> if (stable && l!!.isHealthy) sendOffer(l, iceRestart = false)
            stable -> sendOffer(l!!, iceRestart = true)
            else -> startSession()
        }
    }

    private fun flushCandidates(l: Link) {
        val pending = l.pendingCandidates.toList()
        l.pendingCandidates.clear()
        pending.forEach { l.pc.addIceCandidate(it) }
    }

    private suspend fun recover(l: Link) {
        if (l !== link || l.isHealthy) return
        if (isOfferer()) {
            if (l.pc.signalingState() == SignalingState.STABLE) sendOffer(l, iceRestart = true) else startSession()
        } else {
            sendSignal(SignalData.RequestOffer(l.session))
        }
    }

    private fun onIceState(l: Link, ice: IceConnectionState) {
        if (l !== link) return
        when (ice) {
            IceConnectionState.CONNECTED, IceConnectionState.COMPLETED -> {
                l.everConnected = true
                recoveryJob?.cancel()
                setPhase(CallPhase.CONNECTED)
                sendMediaState()
                applyVideoCap(l)
                applyPacketPriority(l)
                if (statsJob?.isActive != true) watchStats(l)
            }
            IceConnectionState.DISCONNECTED -> {
                suspectMarking(l)
                setPhase(CallPhase.RECONNECTING)
                recoveryJob?.cancel()
                recoveryJob = scope.launch {
                    delay(ICE_RECOVERY_DELAY_MS)
                    post(Event.Recover(l))
                }
            }
            IceConnectionState.FAILED -> {
                suspectMarking(l)
                setPhase(CallPhase.RECONNECTING)
                post(Event.Recover(l))
            }
            else -> Unit
        }
    }

    private fun onTrack(l: Link, transceiver: RtpTransceiver) {
        if (l !== link) return
        when (val track = transceiver.receiver.track()) {
            is VideoTrack -> {
                l.remoteVideoTrack = track
                track.addSink(lipSyncSink)
                // Nothing to show before their phone is answered: the screen keeps saying it's ringing.
                _state.update { it.copy(hasRemoteVideo = !remoteRinging) }
            }
            is AudioTrack -> {
                l.remoteAudio = track
                track.setVolume(_state.value.voiceVolume.toDouble())
                track.addSink(voiceTap)
            }
        }
    }

    // --- ringing a contact ---------------------------------------------------------------

    /**
     * Alone in the room: ring them. Again after a reconnect (the server drops a ring along
     * with the caller's connection), and every few seconds while their phone can't be
     * reached, so it rings as soon as it's back online.
     */
    private fun ringContact() {
        val ring = outgoing?.ring(room) ?: return
        signaling.send(ring)
        if (ringTimeoutJob == null) {
            ringTimeoutJob = scope.launch {
                delay(OutgoingRing.TIMEOUT_MS)
                post(Event.RingTimeout)
            }
        }
        onOutgoingChanged()
    }

    private fun onOutgoingChanged() {
        val ring = outgoing ?: return
        ringRetryJob?.cancel()
        if (ring.joined) {
            // They've been here: an ordinary call from now on, even if they drop out and back.
            ringback.stop()
            ringTimeoutJob?.cancel()
            gaveUpJob?.cancel()
            _state.update { it.copy(outgoing = null) }
            return
        }
        val status = ring.status
        // Like a phone: the ringing tone once their phone is actually ringing.
        if (status == OutgoingRing.Status.RINGING) ringback.start() else ringback.stop()
        if (ring.keepsTrying) {
            ringRetryJob = scope.launch {
                delay(RING_RETRY_MS)
                post(Event.RingRetry)
            }
        }
        _state.update { it.copy(outgoing = OutgoingCall(ring.contact.name, status, keepsTrying = ring.keepsTrying)) }
        if (ring.gaveUp && gaveUpJob == null) {
            ringTimeoutJob?.cancel()
            // Long enough to read why, then the call ends by itself.
            gaveUpJob = scope.launch {
                delay(GAVE_UP_LINGER_MS)
                post(Event.GiveUp)
            }
        }
    }

    // --- chat -------------------------------------------------------------------------------

    private fun publishChat() {
        _state.update { it.copy(chat = chat.messages) }
    }

    /** Created on both sides before negotiating, so the offer carries it and nobody waits for the other. */
    private fun openChatChannel(l: Link) {
        val init = DataChannel.Init().apply {
            negotiated = true
            id = Chat.CHANNEL_ID
            ordered = true
        }
        val channel = l.pc.createDataChannel(Chat.CHANNEL_LABEL, init) ?: return
        l.chatChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val open = runCatching { channel.state() == DataChannel.State.OPEN }.getOrDefault(false)
                post(Event.ChatChannelState(l, open))
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                // The buffer is only valid during this call.
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                post(Event.ChatIncoming(l, String(bytes, Charsets.UTF_8)))
            }
        })
    }

    /**
     * Why a new connection may use mobile data, for the call report; null when it stays off it.
     * Only if you opted in, or if the phone has no working Wi-Fi (none, or one stuck at a gym's
     * login page): never quietly next to working Wi-Fi, even when the phone has made mobile data
     * its default network ([LinkConditions.hasWorkingWifi]).
     */
    private fun mobileDataUse(): String? = when {
        settings.mobileDataBackup -> "allowed, as set in Settings (Mobile data as a backup)"
        settings.mobileDataOn24GHz -> "allowed, as set in Settings (instead of 2.4 GHz Wi-Fi)"
        !LinkConditions.hasWorkingWifi(appContext) -> "used, no working Wi-Fi when the call connected"
        else -> null
    }

    private fun DataChannel.sendText(text: String): Boolean = runCatching {
        send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }.getOrDefault(false)

    // --- peer connection lifecycle ------------------------------------------------------

    private fun createLink(session: String): Link {
        val observer = LinkObserver()
        val mobileData = mobileDataUse()
        val relayOnly = RelayRoute.use(settings.relayRoute, remote, iceServers, relayFailed)
        val pc = engine.createPeerConnection(iceServers, observer, preferCellular, mobileDataNextToWifi = mobileData != null, relayOnly = relayOnly)
            ?: error("WebRTC could not create a peer connection")
        qualityTracker.mobileData(mobileData ?: "kept off, next to working Wi-Fi")
        // Start the bandwidth estimate where this route has been, not at WebRTC's blind 300 kbps.
        startBitrateBps?.takeIf { CallTuning.START_FROM_MEMORY }?.let { if (!pc.setBitrate(null, it, null)) Log.w(TAG, "Could not set the start bitrate") }
        // Before any audio stream exists, so a ringing call never starts the microphone ([applyHold]).
        pc.setAudioRecording(!holding)
        pc.setAudioPlayout(!ringing)
        qualityTracker.newConnection()
        val tracks = engine.createSendTracks()
        tracks.audio.setEnabled(!_state.value.micMuted)
        tracks.video?.setEnabled(!_state.value.sendsNoVideo)
        if (relayOnly) {
            val why = if (settings.relayRoute) "as set in Settings (Calls abroad)" else "as the other phone asked"
            _state.update { it.copy(routeNote = "Going through the relay, $why") }
        }
        return Link(pc, session, tracks, relayOnly, MediaBudget(startLevel = startLevel)).also {
            observer.link = it
            openChatChannel(it)
            if (relayOnly) {
                scope.launch {
                    delay(RelayRoute.FALLBACK_MS)
                    post(Event.RelayCheck(it))
                }
            }
        }
    }

    private fun addLocalTracks(l: Link) {
        l.pc.addTrack(l.tracks.audio, listOf(STREAM_ID))
        l.tracks.video?.let { l.pc.addTrack(it, listOf(STREAM_ID)) }
        l.tracksAdded = true
    }

    /** What this call has learned so far, for the next connection within it. */
    private fun rememberRoute(l: Link) {
        startLevel = l.budget.level
        learner.sendEstimateBps?.let { startBitrateBps = LinkMemory.startBitrateFor(it) }
    }

    /** What this call learned, for the next call with them; null when it's too short to tell or we don't know who they are. */
    private fun learned(): LinkMemory? {
        val address = remoteAddress ?: return null
        val estimate = learner.sendEstimateBps ?: return null
        val path = when (_state.value.callPath) {
            CallPath.WIFI -> "wifi"
            CallPath.CELLULAR -> "cellular"
            else -> network
        } ?: return null
        val level = link?.budget?.level ?: startLevel
        return LinkMemory(address, path, estimate, packetTime.ms, level.name, System.currentTimeMillis())
    }

    private fun closeLink() {
        statsJob?.cancel()
        recoveryJob?.cancel()
        offerTimeoutJob?.cancel()
        requestOfferJob?.cancel()
        val l = link ?: return
        rememberRoute(l)
        link = null
        l.remoteVideoTrack?.removeSink(lipSyncSink)
        l.remoteAudio?.removeSink(voiceTap)
        chat.detach()
        l.chatChannel?.let { channel ->
            channel.unregisterObserver()
            channel.dispose()
        }
        // dispose() also disposes the tracks its senders own (ours, once added).
        l.pc.dispose()
        if (!l.tracksAdded) {
            l.tracks.audio.dispose()
            l.tracks.video?.dispose()
        }
        _state.update { it.copy(hasRemoteVideo = false, remoteSpeaking = false, callPath = null, videoPausedForVoice = false) }
        smartDuck?.onRemoteSpeaking(false)
    }

    private inner class LinkObserver : PeerConnection.Observer {
        @Volatile
        var link: Link? = null

        override fun onIceConnectionChange(newState: IceConnectionState) {
            link?.let { post(Event.IceState(it, newState)) }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            link?.let { post(Event.LocalCandidate(it, candidate)) }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            link?.let { post(Event.Track(it, transceiver)) }
        }

        override fun onSignalingChange(newState: SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
    }

    // --- helpers --------------------------------------------------------------------------

    private fun sendSignal(data: SignalData) {
        val to = remote?.peerId ?: return
        signaling.send(ClientMessage.Signal(to, data))
    }

    private fun sendMediaState() {
        if (remote == null) return
        val s = _state.value
        val mode = if (s.earbudMic) "headset" else profile.wireName
        sendSignal(
            SignalData.MediaState(
                micMuted = s.micMuted,
                // Older apps show any of these as the camera being off, which beats a frozen picture.
                cameraOff = s.sendsNoVideo || s.videoPausedForVoice,
                audioMode = mode,
                inPocket = (s.cameraPaused && !s.cameraOff).takeIf { it },
                weakConnection = (s.videoPausedForVoice && !s.sendsNoVideo).takeIf { it },
                network = linkReport.network,
                uplink = linkReport.uplink,
                radioShared = linkReport.radioShared.takeIf { it },
                ringing = ringing.takeIf { it },
            ),
        )
    }

    /** The camera runs unless you switched it off or the phone is in a pocket. */
    private fun applyCamera() {
        val off = _state.value.sendsNoVideo
        link?.tracks?.video?.setEnabled(!off)
        if (off) engine.stopCamera() else engine.startCamera()
    }

    private val CallState.sendsNoVideo: Boolean get() = cameraOff || cameraPaused

    private fun setRemote(peer: PeerInfo?) {
        if (peer != null && lastPeerId != null && peer.peerId != lastPeerId) {
            chat.peerChanged()
            publishChat()
        }
        if (peer != null) lastPeerId = peer.peerId
        if (peer == null) remoteRinging = false
        remote = peer
        // Until their phone is answered they aren't in the call yet, as far as the screen goes.
        val shown = peer.takeUnless { remoteRinging }
        _state.update {
            it.copy(remotePeer = shown, remoteMedia = if (shown == null) RemoteMedia() else it.remoteMedia)
        }
    }

    private fun setPhase(phase: CallPhase) {
        linkPhase = phase
        // While their phone rings, the call is still waiting for them, whatever the connection is doing.
        val shown = if (remoteRinging) CallPhase.WAITING else phase
        val before = _state.value
        if (before.isActive && shown == CallPhase.RECONNECTING && before.phase == CallPhase.CONNECTED) qualityTracker.reconnected()
        _state.update {
            if (!it.isActive) return@update it
            // The timer counts from when the call connected, through any reconnects; a call
            // connected while it rang here starts counting when it's answered ([unholdHere]).
            val connectedAt = it.connectedAt ?: if (shown == CallPhase.CONNECTED && !ringing) SystemClock.elapsedRealtime() else null
            it.copy(phase = shown, connectedAt = connectedAt)
        }
        // Connecting for a long time usually means the networks block direct calls; say so.
        if (shown == CallPhase.NEGOTIATING || shown == CallPhase.RECONNECTING) {
            if (stuckJob?.isActive != true) {
                stuckJob = scope.launch {
                    delay(ConnectHint.AFTER_MS)
                    post(Event.StuckCheck)
                }
            }
        } else {
            stuckJob?.cancel()
            stuckJob = null
            if (_state.value.connectHint != null) _state.update { it.copy(connectHint = null) }
        }
    }

    private companion object {
        const val TAG = "EarshotCall"
        const val STREAM_ID = "earshot"
        const val ICE_RECOVERY_DELAY_MS = 4_000L
        const val OFFER_TIMEOUT_MS = 10_000L
        const val REQUEST_OFFER_DELAY_MS = 1_500L
        const val REPLAY_SECONDS = 8.0
        const val STATS_INTERVAL_MS = 2_000L
        /** A drop this soon after marking packets is blamed on the marks. */
        const val MARKING_TRIAL_MS = 15_000L
        const val GAVE_UP_LINGER_MS = 3_000L
        /** How often to try again while their phone can't be reached. */
        const val RING_RETRY_MS = 5_000L
        /** A renegotiation for a new packet length that didn't take is tried again after this. */
        const val RENEGOTIATE_RETRY_MS = 15_000L
        /** Voice packets per second, 120 ms to 10 ms packets. */
        const val MIN_PACKET_RATE = 8.0
        const val MAX_PACKET_RATE = 100.0
        /** Joining a room that's full while answering a ring: tries again this often, this many times. */
        const val ROOM_FULL_RETRY_MS = 700L
        const val ROOM_FULL_RETRIES = 4
        /** Longest wait, after answering, for Bluetooth earbuds' call link before the call flows anyway. */
        const val BLUETOOTH_ROUTE_WAIT_MS = 1_500L
        const val ROUTE_POLL_MS = 50L
    }
}
