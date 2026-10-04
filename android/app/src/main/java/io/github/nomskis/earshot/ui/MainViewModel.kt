package io.github.nomskis.earshot.ui

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.appGraph
import io.github.nomskis.earshot.audio.AudioProfile
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.CodecInfo
import io.github.nomskis.earshot.audio.Codecs
import io.github.nomskis.earshot.audio.LatencyProbe
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.FastestSetup
import io.github.nomskis.earshot.audio.LinkConditions
import io.github.nomskis.earshot.audio.RadioTest
import io.github.nomskis.earshot.audio.SetupLabels
import io.github.nomskis.earshot.audio.SonarMeter
import io.github.nomskis.earshot.audio.WifiBand
import io.github.nomskis.earshot.call.CallSession
import io.github.nomskis.earshot.earbuds.DriverLog
import io.github.nomskis.earshot.earbuds.DriverResult
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.earbuds.EarbudControl
import io.github.nomskis.earshot.earbuds.describe
import io.github.nomskis.earshot.calls.CallBackRequest
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.calls.InboxClient
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.settings.InterruptedCall
import io.github.nomskis.earshot.signaling.ServerHealth
import io.github.nomskis.earshot.signaling.ServerUrls
import io.github.nomskis.earshot.turbo.BluetoothOutputDiagnostics
import io.github.nomskis.earshot.turbo.CodecStatus
import io.github.nomskis.earshot.turbo.TurboBoost
import io.github.nomskis.earshot.turbo.TurboClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    /** [roundTripMs]: how far away it is; [relay]: whether calls can fall back to a relay (null: older server). */
    data class Ok(val roundTripMs: Long? = null, val relay: Boolean? = null) : ServerCheck
    data class Failed(val reason: String) : ServerCheck
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val CODEC_SETTLE_MS = 3_500L
        const val GAME_MODE_SETTLE_MS = 2_000L
        const val TURBO_READY_MS = 3_000L
        const val RADIO_SETTLE_MS = 2_000L
        /** Render sleeps after 15 minutes without traffic. */
        const val WAKE_EVERY_MS = 5 * 60_000L
        const val WAKE_TIMEOUT_S = 90L
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

    /** A call the system cut off (app killed mid-call); the home screen offers to rejoin it. */
    val interruptedCall: StateFlow<InterruptedCall?> =
        graph.settings.interruptedCall.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissInterruptedCall() {
        viewModelScope.launch { graph.settings.clearActiveCall() }
    }

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

    private suspend fun measureOnce(label: String, codecOverride: String? = null, store: Boolean = true): SonarMeter.Outcome {
        val current = graph.settings.current()
        val profile = AudioProfile.forCall(current, graph.routeMonitor.snapshot())
        val outcome = SonarMeter(getApplication()).measure(profile.playbackAttributes) { stage ->
            _sonar.value = SonarState.Running(stage)
        }
        if (store && outcome is SonarMeter.Outcome.Success) {
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
        val results = sweepCodecs(status) { text -> _turbo.value = _turbo.value.copy(busy = text) }
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

    /** Measures each selectable codec in turn; returns (codec type, ms) for those that measured. */
    private suspend fun sweepCodecs(status: CodecStatus, progress: (String) -> Unit): List<Pair<Int, Double>> {
        val candidates = status.selectableTypes.ifEmpty { listOf(status.codecType) }
        val results = mutableListOf<Pair<Int, Double>>()
        for ((index, type) in candidates.withIndex()) {
            val name = Codecs.name(type)
            progress("Testing $name (${index + 1} of ${candidates.size})…")
            if (!graph.turbo.setCodec(type)) continue
            delay(CODEC_SETTLE_MS) // The stream restarts with the new codec.
            val outcome = measureOnce("Codec: $name (auto)", codecOverride = name)
            _sonar.value = SonarState.Done(outcome)
            if (outcome is SonarMeter.Outcome.Success) results += type to outcome.summary.delayMs
        }
        return results
    }

    /** Android's own belief about the connected earbuds' delay, from a silent probe: (device, ms). */
    private val _estimate = MutableStateFlow<Pair<String, Double>?>(null)
    val estimate: StateFlow<Pair<String, Double>?> = _estimate.asStateFlow()
    private val probed = mutableSetOf<String>()

    /** Probes once per earbuds per app run, only while nothing else plays through Earshot. */
    fun estimateDelay(route: AudioRoute) {
        val output = route.mediaOutput?.takeIf { it.kind == DeviceKind.BLUETOOTH_MUSIC } ?: return
        if (session.value != null || _sonar.value is SonarState.Running || !probed.add(output.name)) return
        viewModelScope.launch {
            // Labelled as plain media, so the probe never switches Bluetooth latency modes under your music.
            val profile = AudioProfile.forCall(graph.settings.current().copy(gameAudioLabel = false), route)
            LatencyProbe.estimateMs(profile.playbackAttributes)?.let { _estimate.value = output.name to it }
        }
    }

    /** A plain-text summary of this phone, these earbuds and every measurement, for sharing. */
    fun report(route: AudioRoute): String {
        val device = route.mediaOutput?.name
        val runs = delayRuns.value.filter { it.device == device }
        return buildString {
            appendLine("Earshot ${BuildConfig.VERSION_NAME} delay report")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Earbuds: ${device ?: "none"}" + (_earbuds.value.family?.let { " ($it driver)" } ?: ""))
            graph.codecWatcher.latest.value?.let { appendLine("Codec: ${it.summary}") }
            appendLine("Wi-Fi: ${when (wifiBand()) { WifiBand.GHZ_2_4 -> "2.4 GHz"; WifiBand.GHZ_5 -> "5 GHz"; WifiBand.GHZ_6 -> "6 GHz"; null -> "not on Wi-Fi" }}")
            _estimate.value?.takeIf { it.first == device }?.let { appendLine("Android's estimate: ${it.second.toInt()} ms") }
            if (runs.isEmpty()) appendLine("No measurements yet.")
            runs.forEach { r ->
                append("- ${r.label}: ${r.delayMs.toInt()} ms")
                r.reportedMs?.let { append(" (Android believes ${it.toInt()} ms)") }
                r.codec?.let { append(", $it") }
                if (!r.calibrated) append(", uncalibrated")
                appendLine()
            }
            val log = DriverLog.snapshot()
            if (log.isNotEmpty()) {
                appendLine("Earbud control log (bytes sent > and received <):")
                log.forEach { appendLine("  $it") }
            }
        }
    }

    data class RadioTestState(val busy: String? = null, val verdict: RadioTest.Verdict? = null)

    private val _radioTest = MutableStateFlow(RadioTestState())
    val radioTest: StateFlow<RadioTestState> = _radioTest.asStateFlow()

    /** Measures the earbuds with Wi-Fi quiet, then while the phone transmits call-sized traffic. */
    fun runRadioTest() {
        if (_radioTest.value.busy != null || _sonar.value is SonarState.Running || session.value != null) return
        viewModelScope.launch {
            _radioTest.value = RadioTestState(busy = "Measuring with Wi-Fi quiet…")
            val idle = measureOnce("Radio test: quiet", store = false).also { _sonar.value = SonarState.Done(it) }
            _radioTest.value = RadioTestState(busy = "Measuring while Wi-Fi is busy…")
            val load = RadioTest.startLoad(getApplication(), viewModelScope)
            if (load == null) {
                _radioTest.value = RadioTestState(verdict = RadioTest.Verdict("Connect to Wi-Fi to run this test.", false))
                return@launch
            }
            try {
                delay(RADIO_SETTLE_MS) // let the earbuds react to the busy radio
                val busy = measureOnce("Radio test: busy", store = false).also { _sonar.value = SonarState.Done(it) }
                val summary = { o: SonarMeter.Outcome -> (o as? SonarMeter.Outcome.Success)?.summary }
                _radioTest.value = RadioTestState(verdict = RadioTest.verdict(summary(idle), summary(busy), wifiBand()))
            } finally {
                load.cancel()
            }
        }
    }

    data class OptimizerState(val busy: String? = null, val message: String? = null)

    private val _optimizer = MutableStateFlow(OptimizerState())
    val optimizer: StateFlow<OptimizerState> = _optimizer.asStateFlow()

    /**
     * One tap: measures the setup as it is, then with the earbuds' game mode
     * (where Earshot can switch it), then every codec (with Turbo), and turns
     * on for calls whatever measurably helped. The earbuds and the codec are
     * left as they were for music; calls switch them as needed.
     */
    fun findFastestSetup() {
        if (_optimizer.value.busy != null || _sonar.value is SonarState.Running || session.value != null) return
        viewModelScope.launch {
            val step = { text: String -> _optimizer.value = OptimizerState(busy = text) }
            var gameModeWasOn: Boolean? = null
            var gameModeTarget: EarbudControl.Target? = null
            val result = runCatching {
                val target = graph.earbuds.currentTarget()?.takeIf { it.driver != null }
                var baseline: Double? = null
                var gameMode: Double? = null
                if (target != null) {
                    step("Turning earbud game mode off for a baseline…")
                    val off = target.driver!!.setLowLatency(target.device, false)
                    if (off is DriverResult.Ok) {
                        gameModeTarget = target
                        gameModeWasOn = off.wasOn
                        delay(GAME_MODE_SETTLE_MS)
                    }
                }
                step("Measuring your setup as it is…")
                baseline = (measureOnce(SetupLabels.NORMAL).also { _sonar.value = SonarState.Done(it) } as? SonarMeter.Outcome.Success)?.summary?.delayMs
                if (gameModeTarget != null) {
                    step("Measuring with earbud game mode on…")
                    if (target!!.driver!!.setLowLatency(target.device, true) is DriverResult.Ok) {
                        delay(GAME_MODE_SETTLE_MS)
                        gameMode = (measureOnce(SetupLabels.GAME_MODE).also { _sonar.value = SonarState.Done(it) } as? SonarMeter.Outcome.Success)?.summary?.delayMs
                    }
                }
                var bestCodec: Pair<String, Double>? = null
                if (graph.turbo.awaitReady(TURBO_READY_MS)) {
                    val status = graph.turbo.codecStatus()
                    if (status != null && status.selectableTypes.size > 1) {
                        val results = sweepCodecs(status) { text -> step(text) }
                        graph.turbo.setCodec(status.codecType, status.codecSpecific1) // music keeps its codec
                        bestCodec = results.minByOrNull { it.second }?.let { Codecs.name(it.first) to it.second }
                    }
                }
                FastestSetup.Result(baseline, gameMode, bestCodec)
            }
            // Leave the earbuds as they were; calls switch game mode themselves.
            gameModeTarget?.let { t -> if (gameModeWasOn != null) t.driver?.setLowLatency(t.device, gameModeWasOn!!) }
            val outcome = result.getOrNull()
            if (outcome != null) {
                graph.settings.update {
                    it.copy(
                        autoGameMode = if (gameModeTarget != null) outcome.useGameMode else it.autoGameMode,
                        turboDuringCalls = it.turboDuringCalls || outcome.bestCodec != null,
                    )
                }
            }
            _optimizer.value = OptimizerState(message = outcome?.summary() ?: "Failed: ${result.exceptionOrNull()?.message}")
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
                else -> result.describe(getApplication(), _earbuds.value.family)
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

    /** People you can ring directly, most recent first. */
    val contacts: StateFlow<List<Contact>> =
        graph.settings.contacts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun callContact(contact: Contact, withVideo: Boolean) = graph.callManager.callContact(contact, withVideo)

    fun removeContact(contact: Contact) {
        viewModelScope.launch { graph.settings.removeContact(contact.address) }
    }

    /** Every call, newest first. */
    val callLog: StateFlow<List<CallRecord>> =
        graph.settings.callLog.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether this phone can be rung right now. */
    val inboxStatus: StateFlow<InboxClient.State> = graph.callInbox.status

    /** A missed call's "Call back", for the home screen to act on. */
    val callBack: StateFlow<CallBackRequest?> = graph.callBack.asStateFlow()

    fun consumeCallBack() {
        graph.callBack.value = null
    }

    fun endCall() = graph.callManager.endCall()

    fun clearError() = graph.callManager.clearError()

    /**
     * From an invite link. [server] is the inviting server's address; it's
     * only adopted when none is set yet, so a link can't move an existing
     * setup to another server.
     */
    fun offerRoom(room: String?, server: String? = null) {
        _pendingRoom.value = room
        val base = server?.let(ServerUrls::normalizeBase) ?: return
        viewModelScope.launch {
            graph.settings.update { if (it.serverUrl.isBlank()) it.copy(serverUrl = base) else it }
        }
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
                    val request = Request.Builder().url("$base/healthz").build()
                    // The first request may have to wake a sleeping server; time the ones after it,
                    // on the same connection, for how far away it is.
                    val first = graph.http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching ServerCheck.Failed("The server answered with HTTP ${response.code}.")
                        ServerHealth.parse(response.body.string())
                    }
                    if (!first.ok) return@runCatching ServerCheck.Failed("That address answers, but it isn't an Earshot server.")
                    val roundTrip = (1..3).mapNotNull {
                        val start = SystemClock.elapsedRealtime()
                        runCatching { graph.http.newCall(request).execute().close() }.getOrNull()?.let { SystemClock.elapsedRealtime() - start }
                    }.minOrNull()
                    ServerCheck.Ok(roundTrip, first.relay)
                }.getOrElse { ServerCheck.Failed(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    fun resetServerCheck() {
        _serverCheck.value = ServerCheck.Idle
    }

    private var lastWake = 0L

    /**
     * Free hosting plans put the server to sleep when it's idle (Render's
     * takes about a minute to wake). A request as the app comes to the front
     * starts that, so it's usually awake by the time you join.
     */
    fun wakeServer() {
        val now = SystemClock.elapsedRealtime()
        if (lastWake != 0L && now - lastWake < WAKE_EVERY_MS) return
        lastWake = now
        viewModelScope.launch(Dispatchers.IO) {
            val base = ServerUrls.normalizeBase(graph.settings.current().serverUrl) ?: return@launch
            val patient = graph.http.newBuilder().callTimeout(WAKE_TIMEOUT_S, TimeUnit.SECONDS).readTimeout(WAKE_TIMEOUT_S, TimeUnit.SECONDS).build()
            runCatching { patient.newCall(Request.Builder().url("$base/healthz").build()).execute().close() }
        }
    }
}
