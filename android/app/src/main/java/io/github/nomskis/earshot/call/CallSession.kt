package io.github.nomskis.earshot.call

import android.content.Context
import android.util.Log
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.audio.AudioProfile
import io.github.nomskis.earshot.audio.CallAudioController
import io.github.nomskis.earshot.audio.RemoteVoiceTap
import io.github.nomskis.earshot.audio.ReplayPlayer
import io.github.nomskis.earshot.audio.SmartDuck
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.AppSettings
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
            hasCamera = withVideo,
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

    /** One RTCPeerConnection and everything tied to it. */
    private class Link(val pc: PeerConnection, val session: String, val tracks: SendTracks) {
        val pendingCandidates = mutableListOf<IceCandidate>()
        var tracksAdded = false
        var remoteAudio: AudioTrack? = null
        var remoteVideoTrack: VideoTrack? = null

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
        data object SwitchCamera : Event
        data class CameraSwitched(val front: Boolean) : Event
        data class SetVoiceVolume(val volume: Float) : Event
        data object NetworkChanged : Event
        data class RemoteSpeaking(val speaking: Boolean) : Event
        data object ToggleReplay : Event
        data object ReplayFinished : Event
        data object SmartDuckUnsupported : Event
        data class UpdateRadio(val plan: RadioPlan) : Event
        data class SetLipSync(val plan: LipSync.Plan?, val playoutMs: Double?, val measured: Boolean) : Event
        class Stats(val link: Link, val report: RTCStatsReport) : Event
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
    fun switchCamera() = post(Event.SwitchCamera)
    fun setVoiceVolume(volume: Float) = post(Event.SetVoiceVolume(volume))
    fun onNetworkChanged() = post(Event.NetworkChanged)
    fun toggleReplay() = post(Event.ToggleReplay)
    fun updateRadioPlan(plan: RadioPlan) = post(Event.UpdateRadio(plan))
    /**
     * [playoutMs] is the app-to-ear delay in use (from the delay tuner when [measured]),
     * [plan] how far to hold video back for it.
     */
    fun setPlayout(plan: LipSync.Plan?, playoutMs: Double?, measured: Boolean) = post(Event.SetLipSync(plan, playoutMs, measured))
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
            withVideo = withVideo,
            localPreview = localPreview,
        )
        _state.update {
            it.copy(hasCamera = engine.hasVideo, frontCamera = engine.isFrontCamera, radioNote = RadioPlan.describe(radioPlan, null))
        }
        audioController.begin(profile)
        // Ducking only makes sense when the call plays next to music, i.e. in Hi-Fi mode.
        if (settings.smartDuck && profile.mode == AudioMode.HIFI) {
            smartDuck = SmartDuck(appContext) { post(Event.SmartDuckUnsupported) }
        }
        engine.startCamera()

        val join = ClientMessage.Join(
            room = room,
            peerId = peerId,
            name = settings.displayName,
            client = ClientInfo(
                platform = "android",
                version = BuildConfig.VERSION_NAME,
                capabilities = listOf("hifi-audio"),
            ),
        )
        signaling = SignalingClient(http, wsUrl, join, scope)
        scope.launch { for (message in signaling.incoming) post(Event.Server(message)) }
        scope.launch { signaling.state.collect { post(Event.SignalingStateChanged(it)) } }
        signaling.connect()
    }

    private fun finish(phase: CallPhase, error: String? = null) {
        if (finished) return
        finished = true
        replayPlayer.stop()
        smartDuck?.release()
        closeLink()
        lipSyncSink.release()
        if (::signaling.isInitialized) signaling.close()
        audioController.end()
        if (::engine.isInitialized) engine.release()
        _state.update { it.copy(phase = phase, error = error, signalingOnline = false, hasRemoteVideo = false) }
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
                    Log.w(TAG, "No answer to our offer, starting over")
                    startSession()
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
                link?.tracks?.video?.setEnabled(!event.off)
                if (event.off) engine.stopCamera() else engine.startCamera()
                _state.update { it.copy(cameraOff = event.off) }
                sendMediaState()
            }
            Event.SwitchCamera -> engine.switchCamera { front -> post(Event.CameraSwitched(front)) }
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
            is Event.SetLipSync -> {
                lipSyncSink.delayMs = event.plan?.videoDelayMs ?: 0
                playoutMs = event.playoutMs
                playoutMeasured = event.measured
                _state.update { it.copy(lipSync = event.plan) }
            }
            is Event.Stats -> if (event.link === link) onStats(event.link, event.report)
            Event.HangUp -> finish(CallPhase.ENDED)
        }
    }

    // --- sharing the radio with Bluetooth ----------------------------------------------

    private suspend fun onRadioPlan(plan: RadioPlan) {
        if (plan == radioPlan) return
        val old = radioPlan
        radioPlan = plan
        val l = link
        if (l != null) {
            if (old.preferCellular != plan.preferCellular) engine.setPreferCellular(l.pc, iceServers, plan.preferCellular)
            applyVideoCap(l)
            // The cap on what they send us travels in the SDP; renegotiate if we're the one who offers.
            if (old.remoteVideoCap() != plan.remoteVideoCap() && isOfferer() && l.isHealthy &&
                l.pc.signalingState() == SignalingState.STABLE
            ) {
                sendOffer(l, iceRestart = false)
            }
        }
        _state.update { it.copy(radioNote = RadioPlan.describe(plan, it.callPath)) }
    }

    /** What we ask them to cap their video at. When mobile data is preferred we don't, so it isn't held back there. */
    private fun RadioPlan.remoteVideoCap(): Int? = if (preferCellular) null else wifiVideoCapKbps

    private fun applyVideoCap(l: Link) {
        engine.capVideoSend(l.pc, radioPlan.videoCapFor(_state.value.callPath))
    }

    private fun onStats(l: Link, report: RTCStatsReport) {
        val entries = report.statsMap.mapValues { (_, s) -> CallStats.Entry(s.type, s.members) }
        val delay = delayTracker.update(entries, playoutMs, playoutMeasured)
        _state.update { it.copy(delay = delay) }
        val path = RadioPlan.pathFor(CallStats.selectedNetworkType(entries)) ?: return
        if (path == _state.value.callPath) return
        Log.i(TAG, "Media now flows over $path")
        _state.update { it.copy(callPath = path, radioNote = RadioPlan.describe(radioPlan, path)) }
        applyVideoCap(l)
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
                ErrorCodes.ROOM_FULL -> finish(CallPhase.FAILED, "This room already has two people in it.")
                ErrorCodes.BAD_ROOM -> finish(CallPhase.FAILED, "That room code is not valid.")
                else -> Log.w(TAG, "Server error ${message.code}: ${message.message}")
            }
            ServerMessage.Pong -> Unit
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
        iceServers = message.iceServers
        mySeq = message.seq
        val peer = message.peers.firstOrNull()
        if (peer == null) {
            closeLink()
            setRemote(null)
            setPhase(CallPhase.WAITING)
            return
        }
        if (remote != null && remote?.peerId != peer.peerId) closeLink()
        setRemote(peer)
        ensureNegotiated()
    }

    private suspend fun onPeerJoined(peer: PeerInfo) {
        if (remote?.peerId == peer.peerId && remote?.seq == peer.seq) return
        // A fresh join always means a fresh connection, even from a known peerId.
        closeLink()
        setRemote(peer)
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
            is SignalData.MediaState -> _state.update {
                it.copy(remoteMedia = RemoteMedia(data.micMuted, data.cameraOff, data.audioMode))
            }
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
        // Always offer to receive both kinds, even when we send no video ourselves.
        if (l.tracks.video == null) {
            l.pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
        }
        engine.preferRedundantAudio(l.pc)
        setPhase(CallPhase.NEGOTIATING)
        sendOffer(l, iceRestart = false)
    }

    private suspend fun sendOffer(l: Link, iceRestart: Boolean) {
        val offer = l.pc.awaitCreateOffer(iceRestart)
        if (link !== l) return
        l.pc.awaitSetLocal(offer)
        if (link !== l) return
        sendSignal(SignalData.Offer(l.session, tune(offer.description)))
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
        if (fresh) addLocalTracks(l)
        engine.preferRedundantAudio(l.pc)
        val answer = l.pc.awaitCreateAnswer()
        if (link !== l) return
        l.pc.awaitSetLocal(answer)
        if (link !== l) return
        sendSignal(SignalData.Answer(l.session, tune(answer.description)))
        flushCandidates(l)
    }

    /** Our tweaks to every description we send. */
    private fun tune(sdp: String): String =
        SdpTuning.capVideoBandwidth(SdpTuning.preferHdVoice(SdpTuning.preferLowLatencyAudio(sdp)), radioPlan.remoteVideoCap())

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
        if (l != null && data.session == l.session && l.pc.signalingState() == SignalingState.STABLE &&
            l.pc.remoteDescription != null
        ) {
            sendOffer(l, iceRestart = true)
        } else {
            startSession()
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
                recoveryJob?.cancel()
                setPhase(CallPhase.CONNECTED)
                sendMediaState()
                applyVideoCap(l)
                if (statsJob?.isActive != true) watchStats(l)
            }
            IceConnectionState.DISCONNECTED -> {
                setPhase(CallPhase.RECONNECTING)
                recoveryJob?.cancel()
                recoveryJob = scope.launch {
                    delay(ICE_RECOVERY_DELAY_MS)
                    post(Event.Recover(l))
                }
            }
            IceConnectionState.FAILED -> {
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
                _state.update { it.copy(hasRemoteVideo = true) }
            }
            is AudioTrack -> {
                l.remoteAudio = track
                track.setVolume(_state.value.voiceVolume.toDouble())
                track.addSink(voiceTap)
            }
        }
    }

    // --- peer connection lifecycle ------------------------------------------------------

    private fun createLink(session: String): Link {
        val observer = LinkObserver()
        val pc = engine.createPeerConnection(iceServers, observer, radioPlan.preferCellular)
            ?: error("WebRTC could not create a peer connection")
        val tracks = engine.createSendTracks()
        tracks.audio.setEnabled(!_state.value.micMuted)
        tracks.video?.setEnabled(!_state.value.cameraOff)
        return Link(pc, session, tracks).also { observer.link = it }
    }

    private fun addLocalTracks(l: Link) {
        l.pc.addTrack(l.tracks.audio, listOf(STREAM_ID))
        l.tracks.video?.let { l.pc.addTrack(it, listOf(STREAM_ID)) }
        l.tracksAdded = true
    }

    private fun closeLink() {
        statsJob?.cancel()
        recoveryJob?.cancel()
        offerTimeoutJob?.cancel()
        requestOfferJob?.cancel()
        val l = link ?: return
        link = null
        l.remoteVideoTrack?.removeSink(lipSyncSink)
        l.remoteAudio?.removeSink(voiceTap)
        // dispose() also disposes the tracks its senders own (ours, once added).
        l.pc.dispose()
        if (!l.tracksAdded) {
            l.tracks.audio.dispose()
            l.tracks.video?.dispose()
        }
        _state.update { it.copy(hasRemoteVideo = false, remoteSpeaking = false, callPath = null) }
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
        sendSignal(SignalData.MediaState(micMuted = s.micMuted, cameraOff = s.cameraOff, audioMode = profile.wireName))
    }

    private fun setRemote(peer: PeerInfo?) {
        remote = peer
        _state.update {
            it.copy(remotePeer = peer, remoteMedia = if (peer == null) RemoteMedia() else it.remoteMedia)
        }
    }

    private fun setPhase(phase: CallPhase) {
        _state.update { if (it.isActive) it.copy(phase = phase) else it }
    }

    private companion object {
        const val TAG = "EarshotCall"
        const val STREAM_ID = "earshot"
        const val ICE_RECOVERY_DELAY_MS = 4_000L
        const val OFFER_TIMEOUT_MS = 10_000L
        const val REQUEST_OFFER_DELAY_MS = 1_500L
        const val REPLAY_SECONDS = 8.0
        const val STATS_INTERVAL_MS = 2_000L
    }
}
