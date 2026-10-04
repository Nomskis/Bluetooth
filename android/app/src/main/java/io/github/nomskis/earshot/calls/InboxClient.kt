package io.github.nomskis.earshot.calls

import android.util.Log
import io.github.nomskis.earshot.signaling.Backoff
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage
import io.github.nomskis.earshot.signaling.decodeServerMessage
import io.github.nomskis.earshot.signaling.encodeClientMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The connection that waits for calls: listens on our inbox at the server
 * and hands over rings as they arrive.
 *
 * Tuned for never missing a ring: the shared client's WebSocket pings (every
 * 15 s) notice a dead connection within seconds, any drop is redialled with a
 * short backoff (capped at 30 s), and a network change redials at once. A
 * `ping` message every minute keeps hosts that sleep when idle (Render's free
 * plan) awake.
 */
class InboxClient(
    private val http: OkHttpClient,
    private val url: String,
    private val inboxKey: String,
    private val scope: CoroutineScope,
    private val onMessage: (ServerMessage) -> Unit,
    private val keepAwakeMs: Long = KEEP_AWAKE_MS,
    private val backoff: Backoff = Backoff(baseMs = 1_000, maxMs = 30_000),
) {
    enum class State { CONNECTING, LISTENING, WAITING_TO_RETRY, STOPPED }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var stopped = true
    private var attempt = 0
    private var retryJob: Job? = null
    private var keepAwakeJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
            attempt = 0
            openLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            retryJob?.cancel()
            keepAwakeJob?.cancel()
            socket?.close(1000, "bye")
            socket = null
        }
        _state.value = State.STOPPED
    }

    /** Dials again straight away, e.g. after a network change. */
    fun reconnectNow() {
        synchronized(lock) {
            if (stopped) return
            retryJob?.cancel()
            keepAwakeJob?.cancel()
            socket?.cancel()
            socket = null
            attempt = 0
            openLocked()
        }
    }

    /** For answers to rings. False if not connected right now. */
    fun send(message: ClientMessage): Boolean = synchronized(lock) {
        socket?.send(encodeClientMessage(message)) == true
    }

    private fun openLocked() {
        _state.value = State.CONNECTING
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun onDropped(ws: WebSocket) {
        synchronized(lock) {
            if (ws !== socket || stopped) return
            socket = null
            keepAwakeJob?.cancel()
            attempt += 1
            val wait = backoff.delayFor(attempt)
            _state.value = State.WAITING_TO_RETRY
            Log.i(TAG, "Call inbox disconnected; retrying in ${wait / 1000} s")
            retryJob = scope.launch {
                delay(wait)
                synchronized(lock) { if (!stopped && socket == null) openLocked() }
            }
        }
    }

    private fun keepServerAwake(ws: WebSocket) {
        keepAwakeJob?.cancel()
        keepAwakeJob = scope.launch {
            while (true) {
                delay(keepAwakeMs)
                if (!ws.send(encodeClientMessage(ClientMessage.Ping))) break
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (webSocket !== socket) return
                webSocket.send(encodeClientMessage(ClientMessage.Listen(inboxKey)))
                keepServerAwake(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { webSocket !== socket }) return
            val message = decodeServerMessage(text) ?: return
            if (message is ServerMessage.Listening) {
                synchronized(lock) { attempt = 0 }
                _state.value = State.LISTENING
            }
            onMessage(message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            onDropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.i(TAG, "Call inbox connection failed: ${t.message}")
            onDropped(webSocket)
        }
    }

    companion object {
        private const val TAG = "EarshotInbox"
        const val KEEP_AWAKE_MS = 60_000L
    }
}
