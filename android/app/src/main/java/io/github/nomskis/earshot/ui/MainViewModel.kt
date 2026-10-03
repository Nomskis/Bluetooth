package io.github.nomskis.earshot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nomskis.earshot.appGraph
import io.github.nomskis.earshot.audio.AudioProfile
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.CodecInfo
import io.github.nomskis.earshot.audio.Codecs
import io.github.nomskis.earshot.audio.LinkConditions
import io.github.nomskis.earshot.audio.SonarMeter
import io.github.nomskis.earshot.audio.WifiBand
import io.github.nomskis.earshot.call.CallSession
import io.github.nomskis.earshot.earbuds.DriverResult
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.earbuds.describe
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.signaling.ServerUrls
import io.github.nomskis.earshot.turbo.BluetoothOutputDiagnostics
import io.github.nomskis.earshot.turbo.TurboBoost
import io.github.nomskis.earshot.turbo.TurboClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data object Ok : ServerCheck
    data class Failed(val reason: String) : ServerCheck
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val CODEC_SETTLE_MS = 3_500L
    }

    private val graph = app.appGraph

    val settings: StateFlow<AppSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val session: StateFlow<CallSession?> = graph.callManager.session
    val lastError: StateFlow<String?> = graph.callManager.lastError
    val earbudBoost: StateFlow<EarbudBoost.Status?> = graph.callManager.earbudBoost.status
    val turboBoost: StateFlow<TurboBoost.Status?> = graph.callManager.turboBoost?.status ?: MutableStateFlow(null)

    val route: StateFlow<AudioRoute> =
        graph.routeMonitor.route.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.routeMonitor.snapshot())

    /** A room handed to us by an earshot://join/<room> link. */
    private val _pendingRoom = MutableStateFlow<String?>(null)
    val pendingRoom: StateFlow<String?> = _pendingRoom.asStateFlow()

    private val _serverCheck = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val serverCheck: StateFlow<ServerCheck> = _serverCheck.asStateFlow()

    val delayRuns: StateFlow<List<DelayRun>> =
        graph.settings.delayRuns.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The last codec Android announced for any Bluetooth earbuds. */
    val codec: StateFlow<CodecInfo?> = graph.codecWatcher.latest

    /** Call after BLUETOOTH_CONNECT is granted. */
    fun startCodecWatcher() = graph.codecWatcher.start()

    private val _sonar = MutableStateFlow<SonarState>(SonarState.Idle)
    val sonar: StateFlow<SonarState> = _sonar.asStateFlow()

    fun wifiBand(): WifiBand? = LinkConditions.wifiBand(getApplication())

    /** Measures the earbuds by sound, through the same audio path a call would use. */
    fun measureDelay(label: String) {
        if (_sonar.value is SonarState.Running || session.value != null) return
        _sonar.value = SonarState.Running(SonarMeter.Stage.CALIBRATING)
        viewModelScope.launch { _sonar.value = SonarState.Done(measureOnce(label)) }
    }

    private suspend fun measureOnce(label: String, codecOverride: String? = null): SonarMeter.Outcome {
        val current = graph.settings.current()
        val profile = AudioProfile.forCall(current, graph.routeMonitor.snapshot())
        val outcome = SonarMeter(getApplication()).measure(profile.playbackAttributes) { stage ->
            _sonar.value = SonarState.Running(stage)
        }
        if (outcome is SonarMeter.Outcome.Success) {
            graph.settings.addDelayRun(
                DelayRun(
                    device = outcome.deviceName,
                    label = label,
                    delayMs = outcome.summary.delayMs,
                    reportedMs = outcome.summary.reportedMs,
                    calibrated = outcome.summary.calibrated,
                    gameAudio = current.gameAudioLabel,
                    codec = codecOverride ?: graph.codecWatcher.latest.value
                        ?.takeIf { it.device == null || it.device == outcome.deviceName }?.summary,
                    atMillis = System.currentTimeMillis(),
                ),
            )
        }
        return outcome
    }

    // --- Turbo (Shizuku) ----------------------------------------------------------

    val turboStatus: StateFlow<TurboClient.Status> get() = graph.turbo.status

    data class TurboInfo(
        val diagnostics: BluetoothOutputDiagnostics? = null,
        val message: String? = null,
        val busy: String? = null,
    )

    private val _turbo = MutableStateFlow(TurboInfo())
    val turbo: StateFlow<TurboInfo> = _turbo.asStateFlow()

    fun refreshTurbo() {
        graph.turbo.refresh()
        if (graph.turbo.status.value == TurboClient.Status.Ready) loadTurboDiagnostics()
    }

    fun requestTurboPermission() = graph.turbo.requestPermission()

    fun loadTurboDiagnostics() {
        viewModelScope.launch {
            _turbo.value = _turbo.value.copy(diagnostics = graph.turbo.diagnostics())
        }
    }

    fun turboEnableLowLatency() = turboAction("Turning on Bluetooth low-latency mode…") {
        if (graph.turbo.enableVariableLatency()) {
            "Bluetooth low-latency mode is on. Calls labelled as game audio can now use it where the hardware allows."
        } else {
            "This phone didn't accept it."
        }
    }

    fun turboShortestBuffer() = turboAction("Shrinking the Bluetooth buffer…") {
        val status = graph.turbo.codecStatus()
        val ms = status?.let { graph.turbo.shortestBuffer(it.codecType) }
        if (ms != null) "Phone-side Bluetooth buffer set to $ms ms. Measure again to see the effect."
        else "This phone doesn't let apps change its Bluetooth buffer."
    }

    /** Switches through every codec the earbuds support, measures each by sound, and keeps the fastest. */
    fun turboSweepCodecs() = turboAction("Testing codecs…") {
        val status = graph.turbo.codecStatus() ?: return@turboAction "Couldn't read the codecs from the system."
        val candidates = status.selectableTypes.ifEmpty { listOf(status.codecType) }
        val results = mutableListOf<Pair<Int, Double>>()
        for ((index, type) in candidates.withIndex()) {
            val name = Codecs.name(type)
            _turbo.value = _turbo.value.copy(busy = "Testing $name (${index + 1} of ${candidates.size})…")
            if (!graph.turbo.setCodec(type)) continue
            delay(CODEC_SETTLE_MS) // The stream restarts with the new codec.
            val outcome = measureOnce("Codec: $name (auto)", codecOverride = name)
            _sonar.value = SonarState.Done(outcome)
            if (outcome is SonarMeter.Outcome.Success) results += type to outcome.summary.delayMs
        }
        val best = results.minByOrNull { it.second }
        if (best == null) {
            graph.turbo.setCodec(status.codecType, status.codecSpecific1)
            "Couldn't measure any codec. Hold the earbud against the mic for the whole test."
        } else {
            graph.turbo.setCodec(best.first)
            loadTurboDiagnostics()
            "Fastest: ${Codecs.name(best.first)} at ${best.second.toInt()} ms. Earshot switched to it."
        }
    }

    private fun turboAction(busy: String, block: suspend () -> String) {
        if (_turbo.value.busy != null) return
        _turbo.value = _turbo.value.copy(busy = busy, message = null)
        viewModelScope.launch {
            val message = runCatching { block() }.getOrElse { "Failed: ${it.message}" }
            _turbo.value = _turbo.value.copy(busy = null, message = message)
        }
    }

    private val _earbuds = MutableStateFlow(EarbudInfo())
    val earbuds: StateFlow<EarbudInfo> = _earbuds.asStateFlow()

    fun detectEarbuds() {
        viewModelScope.launch {
            val target = graph.earbuds.currentTarget()
            _earbuds.value = EarbudInfo(
                checked = true,
                earbuds = target?.name,
                family = target?.driver?.family,
                experimental = target?.driver?.experimental == true,
            )
        }
    }

    /** Flips the earbuds' game mode now, so it can be measured. */
    fun setEarbudGameMode(on: Boolean) {
        if (_earbuds.value.busy != null) return
        _earbuds.value = _earbuds.value.copy(busy = if (on) "Turning game mode on…" else "Turning game mode off…", message = null)
        viewModelScope.launch {
            val (_, result) = graph.earbuds.setGameMode(on)
            val message = when {
                result == null -> "Couldn't find the earbuds' switch."
                result is DriverResult.Ok && on -> "Game mode is on. Measure now with the label \"Game mode on\"."
                result is DriverResult.Ok -> "Game mode is off."
                else -> result.describe()
            }
            _earbuds.value = _earbuds.value.copy(busy = null, message = message)
        }
    }

    fun clearDelayRuns(device: String) {
        viewModelScope.launch { graph.settings.clearDelayRuns(device) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    fun startCall(room: String, withVideo: Boolean) = graph.callManager.startCall(room, withVideo)

    fun endCall() = graph.callManager.endCall()

    fun clearError() = graph.callManager.clearError()

    fun offerRoom(room: String?) {
        _pendingRoom.value = room
    }

    fun consumePendingRoom() {
        _pendingRoom.value = null
    }

    /** Hits /healthz so the user knows the address is right before a call. */
    fun checkServer(input: String) {
        val base = ServerUrls.normalizeBase(input)
        if (base == null) {
            _serverCheck.value = ServerCheck.Failed("That doesn't look like a web address.")
            return
        }
        _serverCheck.value = ServerCheck.Checking
        viewModelScope.launch {
            _serverCheck.value = withContext(Dispatchers.IO) {
                runCatching {
                    graph.http.newCall(Request.Builder().url("$base/healthz").build()).execute().use { response ->
                        if (response.isSuccessful && response.body.string().contains("ok")) {
                            ServerCheck.Ok
                        } else {
                            ServerCheck.Failed("The server answered with HTTP ${response.code}.")
                        }
                    }
                }.getOrElse { ServerCheck.Failed(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    fun resetServerCheck() {
        _serverCheck.value = ServerCheck.Idle
    }
}
