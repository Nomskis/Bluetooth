package io.github.nomskis.earshot.call

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
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
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
    private val audioSource: AudioSource
    private val videoSource: VideoSource?
    private val surfaceTextureHelper: SurfaceTextureHelper?
    private val capturer: CameraVideoCapturer?
    private val previewTrack: VideoTrack?

    val hasVideo: Boolean
    var isFrontCamera: Boolean
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

    fun createSendTracks(): SendTracks = SendTracks(
        audio = factory.createAudioTrack(Ids.random(6, "a"), audioSource),
        video = videoSource?.let { factory.createVideoTrack(Ids.random(6, "v"), it) },
    )

    fun createPeerConnection(iceServers: List<IceServerConfig>, observer: PeerConnection.Observer): PeerConnection? {
        val servers = iceServers.map { config ->
            val builder = PeerConnection.IceServer.builder(config.urls)
            config.username?.let { builder.setUsername(it) }
            config.credential?.let { builder.setPassword(it) }
            builder.createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Keep gathering so a switch from Wi-Fi to mobile data can be recovered quickly.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
        return factory.createPeerConnection(rtcConfig, observer)
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

        fun initializeWebRtc(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
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
        }
    }
}
