package io.github.nomskis.earshot.calls

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.nomskis.earshot.settings.SettingsRepository
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage
import io.github.nomskis.earshot.signaling.ServerUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Incoming calls. While "Receive calls" is on and a server is set, keeps the
 * inbox connection up (with a foreground service, so Android lets it run),
 * rings the phone when someone calls, and answers or declines for you.
 */
class CallInbox(
    context: Context,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    /** Already on a call (or starting one)? Then a ring is answered "busy". */
    private val isBusy: () -> Boolean,
    /** Joins the caller's room to take an answered call. */
    private val startCall: (ring: IncomingRing, withVideo: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ringer = Ringer(appContext)

    private val _ringing = MutableStateFlow<IncomingRing?>(null)
    /** The call ringing right now, if any. */
    val ringing: StateFlow<IncomingRing?> = _ringing.asStateFlow()

    private val _status = MutableStateFlow(InboxClient.State.STOPPED)
    /** Whether this phone can be rung right now. */
    val status: StateFlow<InboxClient.State> = _status.asStateFlow()

    private var client: InboxClient? = null
    private var clientJob: Job? = null
    private var timeoutJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Starts following the settings: listening whenever calls are on and there's a server. */
    fun follow() {
        CallNotifications.createChannels(appContext)
        scope.launch {
            settings.settings
                .map { s -> ServerUrls.normalizeBase(s.serverUrl).takeIf { s.receiveCalls } }
                .distinctUntilChanged()
                .collectLatest { base -> if (base != null) listen(base) else stopListening() }
        }
    }

    /** Stops listening and ringing for good (tests; the app keeps one inbox for its lifetime). */
    fun close() {
        stopRinging()
        stopListening()
        scope.cancel()
    }

    /** At boot or after an update: start the service if calls are on, while Android allows it. */
    fun ensureService() {
        scope.launch {
            val s = settings.current()
            if (s.receiveCalls && ServerUrls.normalizeBase(s.serverUrl) != null) startService()
        }
    }

    private suspend fun listen(base: String) {
        stopListening()
        val key = settings.inboxKey()
        val c = InboxClient(http, ServerUrls.webSocketUrl(base), key, scope, onMessage = { msg -> scope.launch { onMessage(msg) } })
        client = c
        clientJob = scope.launch { c.state.collect { _status.value = it } }
        c.start()
        watchNetwork(c)
        startService()
        Log.i(TAG, "Waiting for calls at $base")
    }

    private fun stopListening() {
        unwatchNetwork()
        client?.stop()
        client = null
        clientJob?.cancel()
        _status.value = InboxClient.State.STOPPED
        appContext.stopService(Intent(appContext, CallInboxService::class.java))
    }

    private suspend fun onMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Incoming -> onIncoming(message)
            is ServerMessage.RingCancelled -> {
                val ring = _ringing.value ?: return
                if (ring.ringId != message.ringId) return
                stopRinging()
                if (message.reason != "answered-elsewhere") CallNotifications.showMissed(appContext, ring, busy = false)
            }
            else -> Unit
        }
    }

    private suspend fun onIncoming(message: ServerMessage.Incoming) {
        val contacts = settings.contacts.first()
        val name = contacts.firstOrNull { it.address == message.from.address }?.name
            ?: message.from.name.ifBlank { "Someone" }
        val ring = IncomingRing(message.ringId, message.room, name, message.from.address, message.video)
        // Already on a call, or another one is ringing: say so, and leave a note.
        if (isBusy() || _ringing.value != null) {
            client?.send(ClientMessage.RingAnswer(ring.ringId, accepted = false, reason = "busy"))
            CallNotifications.showMissed(appContext, ring, busy = true)
            return
        }
        _ringing.value = ring
        ringer.start()
        CallNotifications.showIncoming(appContext, ring)
        timeoutJob?.cancel()
        // The server ends a ring after a minute; this covers a cancel lost with the connection.
        timeoutJob = scope.launch {
            delay(LOCAL_TIMEOUT_MS)
            if (_ringing.value?.ringId == ring.ringId) {
                stopRinging()
                CallNotifications.showMissed(appContext, ring, busy = false)
            }
        }
    }

    /** Accepts the ringing call and starts it. Returns false if nothing is ringing. */
    fun answer(withVideo: Boolean): Boolean {
        val ring = _ringing.value ?: return false
        client?.send(ClientMessage.RingAnswer(ring.ringId, accepted = true))
        stopRinging()
        startCall(ring, withVideo)
        return true
    }

    fun decline() {
        val ring = _ringing.value ?: return
        client?.send(ClientMessage.RingAnswer(ring.ringId, accepted = false, reason = "declined"))
        stopRinging()
    }

    private fun stopRinging() {
        timeoutJob?.cancel()
        ringer.stop()
        CallNotifications.clearIncoming(appContext)
        _ringing.value = null
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, CallInboxService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ won't start it from the background unless Earshot is exempt from battery
            // optimisation; the connection still runs while the app is alive, and the home screen
            // walks through allowing it.
            Log.w(TAG, "Could not start the call service: ${e.message}")
        }
    }

    private fun watchNetwork(c: InboxClient) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                // The first callback reports the network we already have.
                if (current != null && current != network) c.reconnectNow()
                current = network
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val TAG = "EarshotCalls"
        const val LOCAL_TIMEOUT_MS = 70_000L
    }
}
