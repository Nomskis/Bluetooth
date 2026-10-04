package io.github.nomskis.earshot.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.audio.AudioProfile
import io.github.nomskis.earshot.settings.VideoQuality
import io.github.nomskis.earshot.signaling.IceServerConfig
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.Priority
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards frames to whatever renderer is currently attached. The UI swaps
 * renderers in and out; WebRTC only ever sees this stable sink.
 */
class ProxyVideoSink : VideoSink {
    private var target: VideoSink? = null

    @Synchronized
    fun setTarget(sink: VideoSink?) {
        target = sink
    }

    /** Unplugs [sink] if it's still the one attached; a renderer that replaced it stays. */
    @Synchronized
    fun clearTarget(sink: VideoSink) {
        if (target === sink) target = null
    }

    @Synchronized
    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}

/** Tracks sent on one peer connection. Each connection gets its own, because disposing a connection disposes its tracks. */
class SendTracks(val audio: AudioTrack, val video: VideoTrack?)

/**
 * Owns the WebRTC objects for one call: the factory, the audio device module
 * configured from [AudioProfile], the microphone and the camera.
 *
 * Not thread-safe; CallSession calls it from its single call thread.
 */
class RtcEngine(
    context: Context,
    private val eglBase: EglBase,
    val profile: AudioProfile,
    private val videoQuality: VideoQuality,
    startWithBackCamera: Boolean,
    withVideo: Boolean,
    /** Local camera preview; the UI attaches a renderer to it. */
    private val localPreview: ProxyVideoSink,
) {
    private val appContext = context.applicationContext
    private val audioDeviceModule: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private var audioSource: AudioSource
    private val videoSource: VideoSource?
    private val surfaceTextureHelper: SurfaceTextureHelper?
    private val capturer: CameraVideoCapturer?
    private val previewTrack: VideoTrack?

    val hasVideo: Boolean
    var isFrontCamera: Boolean
        private set
    private val flipProcessor = FlipProcessor()

    /** Flip: your video mirrored left to right, for the other person and your own preview alike. */
    var flipped: Boolean
        get() = flipProcessor.flip
        set(value) {
            flipProcessor.flip = value
        }

    /** WebRTC's software echo canceller for the microphone. */
    var echoCancellation: Boolean = profile.softwareEchoCancellation
        private set
    private var capturing = false

    init {
        initializeWebRtc(appContext)

        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            // The heart of Hi-Fi mode: playback is labelled as media, so Android keeps the
            // earbuds on A2DP. In headset mode this is USAGE_VOICE_COMMUNICATION instead.
            .setAudioAttributes(profile.playbackAttributes)
            .setAudioSource(profile.audioSource)
            .setUseHardwareAcousticEchoCanceler(
                profile.hardwareEchoCanceler && JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
            )
            .setUseHardwareNoiseSuppressor(
                profile.hardwareNoiseSuppressor && JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
            )
            // PERFORMANCE_MODE_LOW_LATENCY plus WebRTC's buffer manager, which starts small and
            // only grows the buffer when it sees underruns.
            .setUseLowLatency(profile.lowLatencyPlayback)
            .setAudioRecordErrorCallback(AudioErrorLogger)
            .setAudioTrackErrorCallback(AudioErrorLogger)
            .createAudioDeviceModule()

        if (profile.preferBuiltInMic) {
            builtInMic()?.let { audioDeviceModule.setPreferredInputDevice(it) }
        }

        val eglContext = eglBase.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        audioSource = factory.createAudioSource(audioConstraints(profile))

        val enumerator = cameraEnumerator()
        val names = if (withVideo) enumerator.deviceNames.toList() else emptyList()
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val back = names.firstOrNull { enumerator.isBackFacing(it) }
        val chosen = (if (startWithBackCamera) back ?: front else front ?: back) ?: names.firstOrNull()

        val cameraCapturer = chosen?.let { enumerator.createCapturer(it, CameraEventsLogger) }
        if (cameraCapturer != null) {
            capturer = cameraCapturer
            surfaceTextureHelper = SurfaceTextureHelper.create("EarshotCapture", eglContext)
            videoSource = factory.createVideoSource(false)
            // Every camera frame passes through Flip before it's encoded or previewed.
            videoSource.setVideoProcessor(flipProcessor)
            cameraCapturer.initialize(surfaceTextureHelper, appContext, videoSource.capturerObserver)
            previewTrack = factory.createVideoTrack("preview", videoSource).also { it.addSink(localPreview) }
            isFrontCamera = enumerator.isFrontFacing(chosen)
            hasVideo = true
        } else {
            capturer = null
            surfaceTextureHelper = null
            videoSource = null
            previewTrack = null
            isFrontCamera = true
            hasVideo = false
        }
    }

    fun startCamera() {
        if (capturing || capturer == null) return
        capturer.startCapture(videoQuality.width, videoQuality.height, videoQuality.fps)
        capturing = true
    }

    fun stopCamera() {
        if (!capturing || capturer == null) return
        try {
            capturer.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        capturing = false
    }

    fun switchCamera(onSwitched: (isFront: Boolean) -> Unit) {
        val cam = capturer ?: return
        cam.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                this@RtcEngine.isFrontCamera = isFrontCamera
                onSwitched(isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String) {
                Log.w(TAG, "Camera switch failed: $errorDescription")
            }
        })
    }

    /**
     * Puts RED (RFC 2198 redundant audio) first for every audio transceiver, so
     * each packet also carries the previous one. A single lost packet is then
     * repaired from the next, instead of the jitter buffer growing to hide it.
     */
    fun preferRedundantAudio(pc: PeerConnection) {
        val codecs = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs
        val (red, rest) = codecs.partition { it.name.equals("red", ignoreCase = true) }
        if (red.isEmpty()) return
        for (transceiver in pc.transceivers) {
            if (transceiver.mediaType != MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) continue
            runCatching { transceiver.setCodecPreferences(red + rest) }
                .onFailure { Log.w(TAG, "Could not prefer RED", it) }
        }
    }

    /**
     * Puts VP9 first for video (WebRtcTuning.PREFERRED_VIDEO_CODEC), keeping every other
     * codec, RTX and FEC included, as fallbacks in their usual order.
     */
    fun preferVp9(pc: PeerConnection) {
        val codecs = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        val (vp9, rest) = codecs.partition {
            it.name.equals(WebRtcTuning.PREFERRED_VIDEO_CODEC, ignoreCase = true) && (it.parameters["profile-id"] ?: "0") == "0"
        }
        if (vp9.isEmpty()) return
        for (transceiver in pc.transceivers) {
            if (transceiver.mediaType != MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) continue
            runCatching { transceiver.setCodecPreferences(vp9 + rest) }
                .onFailure { Log.w(TAG, "Could not prefer VP9", it) }
        }
    }

    fun createSendTracks(): SendTracks = SendTracks(
        audio = createAudioTrack(),
        video = videoSource?.let { factory.createVideoTrack(Ids.random(6, "v"), it) },
    )

    fun createAudioTrack(): AudioTrack = factory.createAudioTrack(Ids.random(6, "a"), audioSource)

    /** Silences their voice at the output; everything else (decoding, the talking cue) carries on. */
    fun setPlaybackMuted(muted: Boolean) = audioDeviceModule.setSpeakerMute(muted)

    /**
     * Turns the software echo canceller on or off. The setting travels with the
     * audio source (WebRTC applies a source's options when its track is set on
     * a sender), so this makes a new source; move the call's audio track onto
     * it with [createAudioTrack]. The recording itself keeps running. Returns
     * the replaced source, to dispose once its track is gone, or null when
     * nothing changed.
     */
    fun switchEchoCancellation(on: Boolean): AudioSource? {
        if (on == echoCancellation) return null
        echoCancellation = on
        val old = audioSource
        audioSource = factory.createAudioSource(audioConstraints(profile.copy(softwareEchoCancellation = on)))
        return old
    }

    /** What a connection was made with that every later configuration of it has to repeat. */
    private data class Fixed(val mobileDataNextToWifi: Boolean, val relayOnly: Boolean)

    /**
     * Per connection: whether it may gather on mobile data next to Wi-Fi (WebRTC fixes that
     * when the connection is made; setConfiguration refuses to change it), and whether it
     * only uses the relay.
     */
    private val fixed = WeakHashMap<PeerConnection, Fixed>()

    /**
     * [mobileDataNextToWifi] false keeps the call off mobile data while a cheaper network
     * (working Wi-Fi) is up; with mobile data as the only network it's used as usual.
     * [relayOnly] sends everything through the TURN relay ([RelayRoute]).
     */
    fun createPeerConnection(
        iceServers: List<IceServerConfig>,
        observer: PeerConnection.Observer,
        preferCellular: Boolean = false,
        mobileDataNextToWifi: Boolean = true,
        relayOnly: Boolean = false,
    ): PeerConnection? {
        val options = Fixed(mobileDataNextToWifi, relayOnly)
        return factory.createPeerConnection(rtcConfiguration(iceServers, preferCellular, options), observer)
            ?.also { fixed[it] = options }
    }

    /**
     * Tells ICE to prefer (or stop preferring) candidate pairs on mobile data.
     * A preference, not a rule: if mobile data fails, the call stays on Wi-Fi.
     */
    fun setPreferCellular(pc: PeerConnection, iceServers: List<IceServerConfig>, prefer: Boolean) {
        if (!pc.setConfiguration(rtcConfiguration(iceServers, prefer, fixed[pc] ?: Fixed(true, false)))) {
            Log.w(TAG, "Could not change the network preference")
        }
    }

    /** Fresh STUN/TURN servers (TURN credentials expire) for the connection's next ICE restart. */
    fun updateIceServers(pc: PeerConnection, iceServers: List<IceServerConfig>, preferCellular: Boolean) {
        if (!pc.setConfiguration(rtcConfiguration(iceServers, preferCellular, fixed[pc] ?: Fixed(true, false)))) {
            Log.w(TAG, "Could not update the ICE servers")
        }
    }

    /**
     * Caps the video we send ([kbps] null = no cap), and optionally sends fewer
     * pixels ([scaleDownBy]) and frames ([maxFps]); [active] false stops sending
     * it altogether (the camera keeps running, so it's back at once). Takes
     * effect without renegotiating.
     */
    fun capVideoSend(pc: PeerConnection, kbps: Int?, scaleDownBy: Double? = null, maxFps: Int? = null, active: Boolean = true) {
        for (sender in pc.senders) {
            val kind = runCatching { sender.track()?.kind() }.getOrNull()
            if (kind != MediaStreamTrack.VIDEO_TRACK_KIND) continue
            val parameters = sender.parameters
            parameters.encodings.forEach {
                it.maxBitrateBps = kbps?.let { k -> k * 1000 }
                it.scaleResolutionDownBy = scaleDownBy
                it.maxFramerate = maxFps
                it.active = active
            }
            if (!sender.setParameters(parameters)) Log.w(TAG, "Could not cap video at $kbps kbps (active: $active)")
        }
    }

    /** Caps the Opus bitrate of our voice ([bps] null = as negotiated). Takes effect without renegotiating. */
    fun capAudioSend(pc: PeerConnection, bps: Int?) {
        for (sender in pc.senders) {
            val kind = runCatching { sender.track()?.kind() }.getOrNull()
            if (kind != MediaStreamTrack.AUDIO_TRACK_KIND) continue
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty() || parameters.encodings.all { it.maxBitrateBps == bps }) continue
            parameters.encodings.forEach { it.maxBitrateBps = bps }
            if (!sender.setParameters(parameters)) Log.w(TAG, "Could not cap audio at $bps bps")
        }
    }

    /**
     * Sends our voice or not. Off, WebRTC doesn't start the audio stream at all, so it doesn't
     * even prepare the microphone (with [audioConstraints]' InitAudioRecordingOnSend off);
     * on, it starts the stream, the recorder with it. Kept across other parameter changes.
     */
    fun setAudioSending(pc: PeerConnection, active: Boolean) {
        for (sender in pc.senders) {
            val kind = runCatching { sender.track()?.kind() }.getOrNull()
            if (kind != MediaStreamTrack.AUDIO_TRACK_KIND) continue
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty() || parameters.encodings.all { it.active == active }) continue
            parameters.encodings.forEach { it.active = active }
            if (!sender.setParameters(parameters)) Log.w(TAG, "Could not ${if (active) "start" else "hold"} our voice")
        }
    }

    /**
     * Temporal layers for our video when it's VP8 or VP9 (see [WebRtcTuning.VIDEO_SCALABILITY_MODE]).
     * Called once the call is connected, when the codec is settled; its own setParameters
     * call, so a refusal doesn't take other settings with it.
     */
    fun useTemporalLayers(pc: PeerConnection) {
        for (sender in pc.senders) {
            val kind = runCatching { sender.track()?.kind() }.getOrNull()
            if (kind != MediaStreamTrack.VIDEO_TRACK_KIND) continue
            val parameters = sender.parameters
            val codec = parameters.codecs.firstOrNull()?.name ?: continue
            if (WebRtcTuning.TEMPORAL_LAYER_CODECS.none { it.equals(codec, ignoreCase = true) }) continue
            if (parameters.encodings.isEmpty() || parameters.encodings.all { it.scalabilityMode == WebRtcTuning.VIDEO_SCALABILITY_MODE }) continue
            parameters.encodings.forEach { it.scalabilityMode = WebRtcTuning.VIDEO_SCALABILITY_MODE }
            if (!sender.setParameters(parameters)) Log.w(TAG, "Could not use temporal layers for video")
        }
    }

    /**
     * DSCP marks for our packets: EF for voice and AF42 for video when [high],
     * unmarked otherwise. Phones' Wi-Fi drivers map both to WMM's video access
     * category, which wins airtime over best-effort traffic on a busy network.
     * (With BUNDLE, audio and video share one socket, so the mark in force is
     * whichever was set last; both land in the same queue.)
     */
    fun setPacketPriority(pc: PeerConnection, high: Boolean) {
        for (sender in pc.senders) {
            val kind = runCatching { sender.track()?.kind() }.getOrNull() ?: continue
            val priority = when {
                !high -> Priority.LOW
                kind == MediaStreamTrack.AUDIO_TRACK_KIND -> Priority.HIGH
                else -> Priority.MEDIUM
            }
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty() || parameters.encodings.all { it.networkPriority == priority }) continue
            parameters.encodings.forEach { it.networkPriority = priority }
            if (!sender.setParameters(parameters)) Log.w(TAG, "Could not set $kind packet priority")
        }
    }

    private fun rtcConfiguration(
        iceServers: List<IceServerConfig>,
        preferCellular: Boolean,
        fixed: Fixed,
    ): PeerConnection.RTCConfiguration {
        val servers = iceServers.map { config ->
            val builder = PeerConnection.IceServer.builder(config.urls)
            config.username?.let { builder.setUsername(it) }
            config.credential?.let { builder.setPassword(it) }
            builder.createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Keep gathering so a switch from Wi-Fi to mobile data can be recovered quickly.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            // Lets setPacketPriority's marks reach the sockets; without it they're ignored.
            enableDscp = true
            // Only relayed candidates: the call goes through the TURN server's network.
            if (fixed.relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
            // LOW_COST: no candidates on mobile data while a cheaper network (Wi-Fi) is up.
            candidateNetworkPolicy = if (fixed.mobileDataNextToWifi) {
                PeerConnection.CandidateNetworkPolicy.ALL
            } else {
                PeerConnection.CandidateNetworkPolicy.LOW_COST
            }
            // Let the jitter buffer shrink quickly after a network hiccup instead of staying
            // inflated, with room to grow through a long Wi-Fi stall (see WebRtcTuning).
            audioJitterBufferFastAccelerate = true
            audioJitterBufferMaxPackets = WebRtcTuning.JITTER_BUFFER_MAX_PACKETS
            // Ranks above network cost in ICE's choice, so a working mobile-data
            // path wins over Wi-Fi; without it Wi-Fi (cheaper) always wins.
            if (preferCellular) networkPreference = PeerConnection.AdapterType.CELLULAR
            // Fail over in about a second instead of several. WebRTC's defaults
            // check backup paths every 25 s and call a path dead after 5 s
            // without an answer; at the gym, a Wi-Fi stall should move the call
            // to its standby path (mobile data, or a relay) before the jitter
            // buffer runs dry. Costs a few extra STUN pings per second.
            iceConnectionReceivingTimeout = FAILOVER_RECEIVE_TIMEOUT_MS
            iceBackupCandidatePairPingInterval = BACKUP_PING_INTERVAL_MS
            stableWritableConnectionPingIntervalMs = STABLE_PING_INTERVAL_MS
            iceUnwritableTimeMs = UNWRITABLE_TIME_MS
            iceUnwritableMinChecks = UNWRITABLE_MIN_CHECKS
        }
    }

    fun release() {
        stopCamera()
        previewTrack?.removeSink(localPreview)
        previewTrack?.dispose()
        capturer?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        audioSource.dispose()
        factory.dispose()
        audioDeviceModule.release()
    }

    /**
     * Moves the microphone between the phone's own (Hi-Fi) and the earbuds'
     * (call link). Applies to the running recording, no renegotiation.
     */
    fun useEarbudMic(on: Boolean): Boolean {
        val device = if (on) bluetoothMic() ?: return false else builtInMic()
        audioDeviceModule.setPreferredInputDevice(device)
        return true
    }

    @SuppressLint("InlinedApi") // TYPE_BLE_HEADSET is a plain int; on older Android no device has it.
    private fun bluetoothMic(): AudioDeviceInfo? {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun builtInMic(): AudioDeviceInfo? {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun cameraEnumerator(): CameraEnumerator =
        if (Camera2Enumerator.isSupported(appContext)) Camera2Enumerator(appContext) else Camera1Enumerator(true)

    private object AudioErrorLogger :
        JavaAudioDeviceModule.AudioRecordErrorCallback,
        JavaAudioDeviceModule.AudioTrackErrorCallback {
        override fun onWebRtcAudioRecordInitError(errorMessage: String) = log("record init", errorMessage)
        override fun onWebRtcAudioRecordStartError(
            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
            errorMessage: String,
        ) = log("record start", "$errorCode $errorMessage")
        override fun onWebRtcAudioRecordError(errorMessage: String) = log("record", errorMessage)
        override fun onWebRtcAudioTrackInitError(errorMessage: String) = log("playback init", errorMessage)
        override fun onWebRtcAudioTrackStartError(
            errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
            errorMessage: String,
        ) = log("playback start", "$errorCode $errorMessage")
        override fun onWebRtcAudioTrackError(errorMessage: String) = log("playback", errorMessage)

        private fun log(what: String, message: String) {
            Log.e(TAG, "Audio $what error: $message")
        }
    }

    private object CameraEventsLogger : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String) {
            Log.e(TAG, "Camera error: $errorDescription")
        }
        override fun onCameraDisconnected() {
            Log.w(TAG, "Camera disconnected")
        }
        override fun onCameraFreezed(errorDescription: String) {
            Log.w(TAG, "Camera froze: $errorDescription")
        }
        override fun onCameraOpening(cameraName: String) = Unit
        override fun onFirstFrameAvailable() = Unit
        override fun onCameraClosed() = Unit
    }

    companion object {
        private const val TAG = "EarshotRtc"
        private val initialized = AtomicBoolean(false)

        /** No packets for this long on the path in use: switch to another that's receiving. */
        const val FAILOVER_RECEIVE_TIMEOUT_MS = 1_000
        /** Keep standby paths checked often, so they're known-good when needed. */
        const val BACKUP_PING_INTERVAL_MS = 2_000
        const val STABLE_PING_INTERVAL_MS = 1_000
        const val UNWRITABLE_TIME_MS = 2_500
        const val UNWRITABLE_MIN_CHECKS = 3

        fun initializeWebRtc(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    // Redundant audio for bursts of loss, and a jitter buffer sized for spiky Wi-Fi.
                    .setFieldTrials(WebRtcTuning.fieldTrials)
                    .createInitializationOptions(),
            )
            if (BuildConfig.DEBUG) Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
        }

        /** WebRTC's audio processing switches; these names are understood by the native code. */
        fun audioConstraints(profile: AudioProfile) = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", profile.softwareEchoCancellation.toString())
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", profile.softwareNoiseSuppression.toString())
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", profile.softwareAutoGain.toString())
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
            // WebRTC otherwise prepares the microphone (creates Android's AudioRecord) as soon as a
            // connection may send, and never frees one it didn't start. Off, it's prepared when the
            // audio stream starts, a moment later in the same step, and not at all while a call
            // rings and holds its voice ([setAudioSending]). sdk/media_constraints.cc maps this to
            // AudioOptions.init_recording_on_send, which WebRtcVoiceSendChannel::SetSend checks.
            mandatory += MediaConstraints.KeyValuePair("InitAudioRecordingOnSend", "false")
        }
    }
}
