package io.github.nomskis.earshot.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.nomskis.earshot.audio.AudioProfile
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.AudioRouteMonitor
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.LinkConditions
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.service.CallService
import io.github.nomskis.earshot.settings.SettingsRepository
import io.github.nomskis.earshot.signaling.ServerUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.webrtc.EglBase

/**
 * Process-wide entry point for calls. At most one call runs at a time; it
 * lives here (not in an Activity) so it survives the screen turning off or
 * you switching to your music app.
 */
class CallManager(
    context: Context,
    private val settings: SettingsRepository,
    private val routeMonitor: AudioRouteMonitor,
    private val http: OkHttpClient,
    /** Earbud game mode for the length of a call, where a driver exists. */
    val earbudBoost: EarbudBoost,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One EGL context for the whole app; video renderers and codecs share it. */
    val eglBase: EglBase by lazy { EglBase.create() }

    private val _session = MutableStateFlow<CallSession?>(null)
    val session: StateFlow<CallSession?> = _session.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val cellular = CellularStandby(appContext)

    fun startCall(room: String, withVideo: Boolean) {
        if (_session.value != null) return
        scope.launch {
            val current = settings.current()
            val base = ServerUrls.normalizeBase(current.serverUrl)
            if (base == null) {
                _lastError.value = "Add your server address in Settings first."
                return@launch
            }
            settings.update { it.copy(lastRoom = room) }
            val route = routeMonitor.snapshot()
            val profile = AudioProfile.forCall(current, route)
            val radioPlan = radioPlan(current, route)
            val session = CallSession(
                context = appContext,
                room = room,
                serverBase = base,
                peerId = settings.peerId(),
                settings = current,
                profile = profile,
                eglBase = eglBase,
                http = http,
                withVideo = withVideo,
                radioPlan = radioPlan,
            )
            _lastError.value = null
            _session.value = session
            CallService.start(appContext)
            if (radioPlan.preferCellular) cellular.acquire()
            watchNetwork(session, current)
            session.start()
            // Game mode only matters when the call plays over the music link next to your music.
            val boost = current.autoGameMode && profile.mode == AudioMode.HIFI
            val boostJob = if (boost) launch { earbudBoost.begin() } else null

            val end = session.state.first { !it.isActive }
            if (end.error != null) _lastError.value = end.error
            unwatchNetwork()
            cellular.release()
            if (_session.value === session) _session.value = null
            // Let a switch still in progress finish first, so end() knows what to undo.
            if (boostJob != null) withContext(NonCancellable) {
                boostJob.join()
                earbudBoost.end()
            }
        }
    }

    fun endCall() {
        _session.value?.hangUp()
    }

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Reconnect signaling right away when the phone moves to a new network,
     * and re-plan radio sharing when the Wi-Fi band changes (roaming between
     * access points can move you between 2.4 and 5 GHz).
     */
    private fun watchNetwork(session: CallSession, settings: AppSettings) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                // The first callback just reports the network we already have.
                if (current != null && current != network) session.onNetworkChanged()
                current = network
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val plan = radioPlan(settings, routeMonitor.snapshot())
                if (plan.preferCellular) cellular.acquire()
                session.updateRadioPlan(plan)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun radioPlan(settings: AppSettings, route: AudioRoute): RadioPlan {
        val bluetooth = setOf(DeviceKind.BLUETOOTH_MUSIC, DeviceKind.BLUETOOTH_CALL, DeviceKind.BLUETOOTH_LE)
        val onBluetooth = route.bluetoothMusicAvailable || route.mediaOutput?.kind in bluetooth ||
            route.communicationDevice?.kind in bluetooth
        return RadioPlan.decide(LinkConditions.wifiBand(appContext), onBluetooth, settings)
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
