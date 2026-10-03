package io.github.nomskis.earshot.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with jitter: 0.5 s, 1 s, 2 s, 4 s, 8 s, 8 s, ... */
class Backoff(
    private val baseMs: Long = 500,
    private val maxMs: Long = 8_000,
    private val jitter: Double = 0.25,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int): Long {
        val exp = baseMs shl (attempt - 1).coerceIn(0, 20)
        val capped = min(maxMs, exp)
        val factor = 1 - jitter + random.nextDouble() * 2 * jitter
        return (capped * factor).toLong()
    }
}

/**
 * WebSocket connection to the signaling server.
 *
 * Reconnects on its own and re-sends the same `join` every time, so the server
 * resumes our slot in the room instead of treating us as a newcomer. Media
 * flows directly between the phones, so a short signaling outage (switching
 * from gym Wi-Fi to mobile data, say) does not interrupt the call.
 */
class SignalingClient(
    private val http: OkHttpClient,
    private val url: String,
    private val join: ClientMessage.Join,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
) {
    sealed interface State {
        data object Connecting : State
        data object Open : State
        data class Reconnecting(val attempt: Int) : State
        data class Closed(val reason: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _incoming = Channel<ServerMessage>(Channel.UNLIMITED)

    /** Every message from the server, in order. Has a single consumer: the call session. */
    val incoming: ReceiveChannel<ServerMessage> = _incoming

    private val lock = Any()
    private var socket: WebSocket? = null
    private var isOpen = false
    private var closed = false
    private var attempt = 0
    private var retryJob: Job? = null
    private val pending = ArrayDeque<ClientMessage>()

    fun connect() {
        synchronized(lock) {
            closed = false
            openLocked()
        }
    }

    /** Sends now if connected; signals are queued while reconnecting. */
    fun send(message: ClientMessage) {
        synchronized(lock) {
            val ws = socket
            if (isOpen && ws != null && ws.send(encodeClientMessage(message))) return
            if (message is ClientMessage.Signal) {
                pending.addLast(message)
                if (pending.size > MAX_PENDING) pending.removeFirst()
            }
        }
    }

    /** Drops the current socket and dials again right away, e.g. after a network change. */
    fun reconnectNow() {
        synchronized(lock) {
            if (closed) return
            retryJob?.cancel()
            val old = socket
            socket = null
            isOpen = false
            old?.cancel()
            attempt = 0
            openLocked()
        }
    }

    /** Hangs up: tells the server we left and stops reconnecting. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            retryJob?.cancel()
            socket?.let { ws ->
                if (isOpen) ws.send(encodeClientMessage(ClientMessage.Leave))
                ws.close(1000, "bye")
            }
            socket = null
            isOpen = false
        }
        _state.value = State.Closed(null)
        _incoming.close()
    }

    private fun openLocked() {
        _state.value = if (attempt == 0) State.Connecting else State.Reconnecting(attempt)
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, Listener())
    }

    private fun onDropped(ws: WebSocket, code: Int?) {
        synchronized(lock) {
            if (ws !== socket || closed) return
            socket = null
            isOpen = false
            if (code == CLOSE_REPLACED) {
                // The same peerId connected from somewhere else. Don't fight over it.
                closed = true
                _state.value = State.Closed("replaced")
                _incoming.close()
                return
            }
            attempt += 1
            val wait = backoff.delayFor(attempt)
            _state.value = State.Reconnecting(attempt)
            retryJob = scope.launch {
                delay(wait)
                synchronized(lock) {
                    if (!closed && socket == null) openLocked()
                }
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (webSocket !== socket) return
                isOpen = true
                attempt = 0
                webSocket.send(encodeClientMessage(join))
                while (pending.isNotEmpty()) webSocket.send(encodeClientMessage(pending.removeFirst()))
            }
            _state.value = State.Open
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val current = synchronized(lock) { webSocket === socket }
            if (!current) return
            val message = decodeServerMessage(text)
            if (message == null) {
                Log.w(TAG, "Ignoring message this version does not understand: ${text.take(200)}")
                return
            }
            _incoming.trySend(message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            onDropped(webSocket, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDropped(webSocket, code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.i(TAG, "Signaling connection failed: ${t.message}")
            onDropped(webSocket, null)
        }
    }

    private companion object {
        const val TAG = "EarshotSignaling"
        const val MAX_PENDING = 100
        const val CLOSE_REPLACED = 4000
    }
}
