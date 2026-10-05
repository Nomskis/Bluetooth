package io.github.nomskis.earshot.call

import android.os.SystemClock
import android.util.Log
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.IceServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * Their shared screen, pulled from the Cloudflare site nearest us on a connection of its own
 * ([ScreenShare] is the other end). What arrives is measured as it comes, and the sharer is
 * asked for less when our link can't keep up and more once it can ([ScreenPace]).
 *
 * Lives on the call thread like [ScreenShare]; WebRTC's callbacks come back through [post].
 */
internal class ScreenWatch(
    private val engine: RtcEngine,
    private val iceServers: () -> List<IceServerConfig>,
    private val mobileDataNextToWifi: () -> Boolean,
    /** Where their screen's frames go; the UI attaches a renderer to it. */
    private val sink: VideoSink,
    private val send: (ClientMessage) -> Unit,
    private val post: (suspend () -> Unit) -> Unit,
    private val scope: CoroutineScope,
    /** Their screen is on its way (true), or over (false). */
    private val onShared: (Boolean) -> Unit,
    /** Frames are arriving. */
    private val onShowing: (Boolean) -> Unit,
    /** The ceiling to ask the sharer for changed; null = no limit. */
    private val onCap: (Int?) -> Unit,
) {
    private var pc: PeerConnection? = null
    private var track: VideoTrack? = null
    private var showing = false
    private val pace = ScreenPace()
    private var statsJob: Job? = null
    private var retryJob: Job? = null
    private var failures = 0

    /** Who's sharing, while there's a share to watch. */
    var from: String? = null
        private set

    /** The share's frames, passed on, noticing the first. */
    private val frames = object : VideoSink {
        @Volatile
        var seen = false

        override fun onFrame(frame: VideoFrame) {
            if (!seen) {
                seen = true
                post { setShowing(true) }
            }
            sink.onFrame(frame)
        }
    }

    /** [from] started sharing (or started again on a new connection): ask for it. */
    fun begin(from: String) {
        this.from = from
        failures = 0
        retryJob?.cancel()
        onShared(true)
        send(ClientMessage.ScreenWatch)
        watchdog(CONNECT_TIMEOUT_MS)
    }

    /** Cloudflare's offer, through the server: answer it. */
    suspend fun onOffer(watch: String, sdp: String) {
        if (from == null) return
        close()
        val observer = Observer()
        val pc = engine.createScreenPeerConnection(iceServers(), observer, mobileDataNextToWifi()) ?: return
        observer.pc = pc
        this.pc = pc
        try {
            pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
            if (this.pc !== pc) return
            val answer = pc.awaitCreateAnswer()
            if (this.pc !== pc) return
            pc.awaitSetLocal(answer)
            if (this.pc !== pc) return
            send(ClientMessage.ScreenAnswer(watch, answer.description))
        } catch (e: SdpException) {
            Log.w(TAG, "Could not answer the screen's offer", e)
            retryLater()
        }
    }

    /** The server couldn't get us the screen this time. */
    fun onError() {
        if (from != null) retryLater()
    }

    /** The share is over, or they left. */
    fun end() {
        if (from == null && pc == null) return
        from = null
        retryJob?.cancel()
        close()
        pace.reset()
        onCap(null)
        onShared(false)
    }

    /** The ceiling we ask the sharer for, for our media-state. */
    val capKbps: Int? get() = pace.capKbps

    private fun onTrack(pc: PeerConnection, transceiver: RtpTransceiver) {
        if (pc !== this.pc) return
        val video = transceiver.receiver.track() as? VideoTrack ?: return
        track?.removeSink(frames)
        frames.seen = false
        track = video
        video.addSink(frames)
    }

    private fun onIce(pc: PeerConnection, state: IceConnectionState) {
        if (pc !== this.pc || from == null) return
        when (state) {
            IceConnectionState.CONNECTED, IceConnectionState.COMPLETED -> {
                retryJob?.cancel()
                failures = 0
                watchStats(pc)
            }
            IceConnectionState.DISCONNECTED -> watchdog(RECOVER_MS)
            IceConnectionState.FAILED -> retryLater()
            else -> Unit
        }
    }

    private fun watchStats(pc: PeerConnection) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                delay(STATS_INTERVAL_MS)
                pc.getStats { report ->
                    val entries = report.statsMap.mapValues { (_, s) -> CallStats.Entry(s.type, s.members) }
                    val counters = CallStats.inboundVideoCounters(entries) ?: return@getStats
                    post {
                        if (pc === this@ScreenWatch.pc && pace.update(counters, SystemClock.elapsedRealtime())) {
                            Log.i(TAG, pace.capKbps?.let { "Asking for at most $it kbps of screen" } ?: "Screen comes through fine; no limit")
                            onCap(pace.capKbps)
                        }
                    }
                }
            }
        }
    }

    private fun watchdog(afterMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(afterMs)
            post {
                val state = pc?.iceConnectionState()
                if (from != null && state != IceConnectionState.CONNECTED && state != IceConnectionState.COMPLETED) retryLater()
            }
        }
    }

    private fun retryLater() {
        val who = from ?: return
        failures++
        if (failures > MAX_FAILURES) {
            Log.w(TAG, "Couldn't get their screen; waiting for them to share again")
            setShowing(false)
            return
        }
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_MS * failures)
            post { if (from == who) begin(who) }
        }
    }

    private fun setShowing(on: Boolean) {
        if (on == showing) return
        showing = on
        onShowing(on)
    }

    private fun close() {
        statsJob?.cancel()
        val old = pc ?: return
        pc = null
        track?.removeSink(frames)
        track = null
        old.dispose()
        setShowing(false)
    }

    private inner class Observer : PeerConnection.Observer {
        @Volatile
        var pc: PeerConnection? = null

        override fun onIceConnectionChange(newState: IceConnectionState) {
            val pc = pc ?: return
            post { onIce(pc, newState) }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val pc = pc ?: return
            post { onTrack(pc, transceiver) }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
    }

    private companion object {
        const val TAG = "EarshotScreen"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val RECOVER_MS = 6_000L
        const val RETRY_MS = 2_000L
        const val MAX_FAILURES = 6
        const val STATS_INTERVAL_MS = 2_000L
    }
}
