package io.github.nomskis.earshot.call

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Coroutine wrappers around WebRTC's callback-style SDP API. */

class SdpException(message: String) : Exception(message)

private abstract class SdpCallbacks : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

suspend fun PeerConnection.awaitCreateOffer(iceRestart: Boolean = false): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }
        createOffer(object : SdpCallbacks() {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String) = cont.resumeWithException(SdpException("createOffer: $error"))
        }, constraints)
    }

suspend fun PeerConnection.awaitCreateAnswer(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpCallbacks() {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String) = cont.resumeWithException(SdpException("createAnswer: $error"))
        }, MediaConstraints())
    }

suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpCallbacks() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String) = cont.resumeWithException(SdpException("setLocalDescription: $error"))
        }, sdp)
    }

suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpCallbacks() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String) = cont.resumeWithException(SdpException("setRemoteDescription: $error"))
        }, sdp)
    }

suspend fun PeerConnection.awaitAddIceCandidate(candidate: IceCandidate): Unit =
    suspendCancellableCoroutine { cont ->
        addIceCandidate(candidate, object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() = cont.resume(Unit)
            override fun onAddFailure(error: String) = cont.resumeWithException(SdpException("addIceCandidate: $error"))
        })
    }
