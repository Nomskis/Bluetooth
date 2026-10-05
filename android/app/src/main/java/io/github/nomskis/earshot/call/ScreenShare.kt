package io.github.nomskis.earshot.call

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.IceServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Our screen, shared through Cloudflare on a connection of its own next to the call
 * (docs/research/screen-share.md). The server publishes our offer to the Cloudflare site
 * nearest us; the other person pulls it from the one nearest them. The call's voice and chat
 * carry on as they were, on their own connection.
 *
 * Lives on the call thread: every method is called there, and WebRTC's callbacks come back
 * through [post]. A connection that fails is published again (the viewer is told and pulls
 * the new one), a few times before giving up.
 */
internal class ScreenShare(
    context: Context,
    private val engine: RtcEngine,
    eglContext: EglBase.Context,
    projection: MediaProjection,
    /** The share's own WebRTC, with the shared app's sound when there is some. */
    private val media: ScreenMedia,
    /** The call's current STUN and TURN servers (TURN credentials are refreshed on rejoin). */
    private val iceServers: () -> List<IceServerConfig>,
    private val mobileDataNextToWifi: Boolean,
    /** Send AV1: they can decode it and we can encode it fast enough. */
    private val av1: Boolean,
    /** The most the screen may use, before the viewer asks for less. */
    private val maxKbps: Int,
    maxShortSide: Int,
    private val send: (ClientMessage) -> Unit,
    private val post: (suspend () -> Unit) -> Unit,
    private val scope: CoroutineScope,
    /** Whether Cloudflare has the screen and it's flowing. */
    private val onLive: (Boolean) -> Unit,
    /** It ended by itself (stopped from outside the app, or it couldn't be published); [why] for the screen, or null. */
    private val onEnded: (why: String?) -> Unit,
) {
    private val source = media.createScreenSource()
    private val capture = ScreenCapture(context, projection, eglContext, source.capturerObserver, maxShortSide) {
        post { end(null) }
    }
    private var pc: PeerConnection? = null
    private var track: VideoTrack? = null
    private var soundTrack: AudioTrack? = null
    private var sender: RtpSender? = null
    /** This publish attempt's name; answers and errors for an earlier one are ignored. */
    private var attempt: String? = null
    /** The room has been told this connection is up. */
    private var announced = false
    private var viewerKbps: Int? = null
    private var failures = 0
    private var ended = false
    private var retryJob: Job? = null
    private var watchdogJob: Job? = null

    suspend fun start() {
        capture.start()
        publish()
    }

    /** Cloudflare's answer: the connection comes up next. */
    suspend fun onPublished(sdp: String, id: String?) {
        if (id != null && id != attempt) return
        val pc = pc ?: return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        try {
            // Their side decodes stereo when asked; this side encodes it when Cloudflare's answer asks.
            val answer = if (soundTrack != null) ScreenTuning.stereoOpus(sdp) else sdp
            pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
        } catch (e: SdpException) {
            Log.w(TAG, "Cloudflare's answer didn't fit our offer", e)
            retryLater()
        }
    }

    /** The server couldn't publish it; [message] is its words for the person. */
    fun onError(code: String, message: String, id: String?) {
        // About an attempt we've already replaced.
        if (id != null && id != attempt) return
        when (code) {
            // Nothing a retry fixes: say why and stop.
            "unavailable", "in-use" -> end(message)
            // A late answer for a connection we've already replaced.
            "stale" -> Unit
            else -> retryLater()
        }
    }

    /** The viewer's ceiling ([ScreenPace]); null = no limit. */
    fun setViewerKbps(kbps: Int?) {
        if (kbps == viewerKbps) return
        viewerKbps = kbps
        Log.i(TAG, kbps?.let { "Viewer asks for at most $it kbps" } ?: "Viewer lifted its limit")
        tune()
    }

    /**
     * Back in the room after the connection to the server dropped; [known]: the server still
     * has our share. When it doesn't (it restarted, or our last publish or `screen-live` was
     * lost on the way), the share is published again so she can see it.
     */
    suspend fun rejoined(known: Boolean) {
        if (ended || known) return
        Log.i(TAG, "The server lost our share; publishing it again")
        retryJob?.cancel()
        failures = 0
        publish()
    }

    /** We stop sharing. */
    fun stop() {
        if (ended) return
        ended = true
        send(ClientMessage.ScreenStop)
        tearDown()
    }

    private fun end(why: String?) {
        if (ended) return
        stop()
        onEnded(why)
    }

    private suspend fun publish() {
        if (ended) return
        closeConnection()
        val observer = Observer()
        val pc = engine.createScreenPeerConnection(iceServers(), observer, mobileDataNextToWifi, media.factory)
        if (pc == null) {
            end("Couldn't start sharing")
            return
        }
        observer.pc = pc
        this.pc = pc
        announced = false
        val track = media.createScreenTrack(source).also { track = it }
        val transceiver = pc.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(STREAM_ID)),
        )
        engine.preferScreenCodecs(transceiver, ScreenTuning.codecOrder(av1), media.factory)
        sender = transceiver.sender
        tune()
        // The shared app's sound, in the same stream as the picture so the viewer keeps them in step.
        val sound = media.createSoundTrack()?.let { track ->
            soundTrack = track
            pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(STREAM_ID)))
        }
        try {
            val offer = pc.awaitCreateOffer()
            if (this.pc !== pc) return
            pc.awaitSetLocal(offer)
            val mid = transceiver.mid
            if (this.pc !== pc || mid == null) return
            val id = Ids.random(6).also { attempt = it }
            send(ClientMessage.ScreenPublish(sdp = offer.description, mid = mid, audioMid = sound?.mid, id = id))
        } catch (e: SdpException) {
            Log.w(TAG, "Could not make the screen's offer", e)
            retryLater()
            return
        }
        // No answer, or no connection, within a while: try again.
        watchdog(pc, CONNECT_TIMEOUT_MS)
    }

    private fun onIce(pc: PeerConnection, state: IceConnectionState) {
        if (pc !== this.pc || ended) return
        when (state) {
            IceConnectionState.CONNECTED, IceConnectionState.COMPLETED -> {
                watchdogJob?.cancel()
                failures = 0
                onLive(true)
                // Now there's something to watch: the server tells the room.
                if (!announced) {
                    announced = true
                    send(ClientMessage.ScreenLive)
                }
            }
            // A blip on mobile data often comes back by itself.
            IceConnectionState.DISCONNECTED -> {
                onLive(false)
                watchdog(pc, RECOVER_MS)
            }
            IceConnectionState.FAILED -> retryLater()
            else -> Unit
        }
    }

    private fun watchdog(pc: PeerConnection, afterMs: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(afterMs)
            post {
                val state = pc.iceConnectionState()
                if (pc === this@ScreenShare.pc && state != IceConnectionState.CONNECTED && state != IceConnectionState.COMPLETED) retryLater()
            }
        }
    }

    private fun retryLater() {
        if (ended || retryJob?.isActive == true) return
        onLive(false)
        failures++
        if (failures > MAX_FAILURES) {
            end("Lost the connection to the screen relay")
            return
        }
        Log.w(TAG, "Screen share faltered; publishing it again (attempt $failures)")
        retryJob = scope.launch {
            delay(RETRY_MS * failures)
            post { publish() }
        }
    }

    private fun tune() {
        val s = sender ?: return
        engine.tuneScreenSender(s, minOf(maxKbps, viewerKbps ?: maxKbps))
    }

    private fun closeConnection() {
        watchdogJob?.cancel()
        val old = pc ?: return
        pc = null
        sender = null
        attempt = null
        old.dispose()
        // The connection let go of the tracks; these are our own holds on them. The sources stay.
        track?.dispose()
        track = null
        soundTrack?.dispose()
        soundTrack = null
    }

    private fun tearDown() {
        retryJob?.cancel()
        closeConnection()
        capture.stop()
        source.dispose()
        media.release()
        onLive(false)
    }

    private inner class Observer : PeerConnection.Observer {
        @Volatile
        var pc: PeerConnection? = null

        override fun onIceConnectionChange(newState: IceConnectionState) {
            val pc = pc ?: return
            post { onIce(pc, newState) }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        // Cloudflare learns our address from our connectivity checks; it needs no candidates.
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
        override fun onTrack(transceiver: RtpTransceiver) = Unit
    }

    private companion object {
        const val TAG = "EarshotScreen"
        const val STREAM_ID = "earshot-screen"
        /** From the offer to a connection to Cloudflare. */
        const val CONNECT_TIMEOUT_MS = 15_000L
        /** A dropped connection that hasn't come back by itself in this long is made again. */
        const val RECOVER_MS = 6_000L
        const val RETRY_MS = 2_000L
        const val MAX_FAILURES = 5
    }
}
