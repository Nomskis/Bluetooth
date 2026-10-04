package io.github.nomskis.earshot.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.nomskis.earshot.audio.AudioProfile
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.AudioRouteMonitor
import io.github.nomskis.earshot.audio.CodecInfo
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.LatencyProbe
import io.github.nomskis.earshot.audio.LinkConditions
import io.github.nomskis.earshot.calls.CallRecords
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.calls.InboxKeys
import io.github.nomskis.earshot.calls.IncomingRing
import io.github.nomskis.earshot.calls.OutgoingRing
import io.github.nomskis.earshot.calls.RingingOut
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.service.CallService
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.DelayRuns
import io.github.nomskis.earshot.settings.EchoCancellation
import io.github.nomskis.earshot.settings.InterruptedCall
import io.github.nomskis.earshot.settings.SettingsRepository
import io.github.nomskis.earshot.signaling.ServerUrls
import io.github.nomskis.earshot.turbo.TurboBoost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    /** Turbo's privileged switches for the length of a call, when Shizuku is set up. */
    val turboBoost: TurboBoost? = null,
    /** The Bluetooth codec in use, when known; picks the matching delay measurement. */
    private val codec: () -> CodecInfo? = { null },
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

    /** Putting earbud game mode and the codec back after the last call; the next call waits for it. */
    private var cleanup: Job? = null

    /** A call is being set up but its session doesn't exist yet (main thread only). */
    private val starting = MutableStateFlow(false)
    /** The ring the current call sends, if it's a call to a contact. */
    @Volatile
    private var outgoing: OutgoingRing? = null
    /** Left for their call when we rang each other at once; not a call of its own in the history. */
    private var supersededRing: OutgoingRing? = null

    /** On a call, or about to be. */
    val busy: Boolean get() = _session.value != null || starting.value

    /**
     * A call ringing here, connected while it rings ([CallSession] `ringing`), from the moment
     * it's asked; [session] and what it was made with are filled in once it exists. Main thread only.
     */
    private class Early(var ringId: String) {
        var session: CallSession? = null
        var profile: AudioProfile? = null
        var withVideo = false
    }
    private var early: Early? = null

    /**
     * [ring] is ringing here: join its room and connect now, sending and playing nothing and
     * leaving the music, audio mode and camera alone, so answering only has to switch the sound
     * (and camera) on. Over a slow route abroad, setting a call up (server, offer and answer,
     * the route, encryption) takes seconds; this way they pass while the phone rings, not after
     * you've said hello. Answering with the same voice or video and the same audio route takes
     * this connection; anything else, or not answering here, leaves it behind.
     */
    fun preconnect(ring: IncomingRing) {
        if (busy || early != null) return
        // Answering needs the microphone; if it isn't allowed yet, answering asks and starts afresh.
        if (!granted(Manifest.permission.RECORD_AUDIO)) return
        val pending = Early(ring.ringId)
        early = pending
        scope.launch {
            val current = settings.current()
            val base = ServerUrls.normalizeBase(current.serverUrl)
            val withVideo = ring.video && granted(Manifest.permission.CAMERA)
            val plan = plan(current, ring.callerAddress)
            if (early !== pending) return@launch // answered or gone meanwhile
            if (base == null) {
                early = null
                return@launch
            }
            val session = newSession(plan, ring.room, base, withVideo, outgoing = null, contactName = ring.callerName, ringing = true)
            pending.session = session
            pending.profile = plan.profile
            pending.withVideo = withVideo
            Log.i(TAG, "Connecting while it rings")
            session.start()
            // It can end by itself (they hung up, the room filled up): nothing to take then.
            session.state.first { !it.isActive }
            if (early === pending) early = null
        }
    }

    /** [ringId] stopped ringing without being answered here: leave the room it connected to. */
    fun dropPreconnect(ringId: String) {
        val e = early?.takeIf { it.ringId == ringId } ?: return
        early = null
        e.session?.hangUp()
    }

    /**
     * The caller rang again for the same call (its app reconnected, and the server ends a ring
     * with the connection that sent it): the early connection now belongs to [newRingId].
     */
    fun rekeyPreconnect(oldRingId: String, newRingId: String) {
        early?.takeIf { it.ringId == oldRingId }?.ringId = newRingId
    }

    /** On a call in [room] (a ring for it is the same call, rung again). */
    fun inCall(room: String): Boolean = _session.value?.room == room

    /**
     * Wired the same way for a call. Not the echo canceller: that follows the audio route during
     * the call anyway ([followRoute]), so earbuds put in while it rang don't need a new connection.
     */
    private fun AudioProfile.wiredLike(other: AudioProfile) = copy(softwareEchoCancellation = other.softwareEchoCancellation) == other

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** What a call is set up with, from the settings and where the audio goes right now. */
    private class Plan(
        val current: AppSettings,
        val route: AudioRoute,
        val profile: AudioProfile,
        val radioPlan: RadioPlan,
        val peerId: String,
        val me: Chat.Frame.Contact,
        val inboxKey: String,
        val theirAddress: String?,
        val network: String?,
        val memory: LinkMemory?,
    )

    private suspend fun plan(current: AppSettings, theirAddress: String?): Plan {
        val route = routeMonitor.snapshot()
        // Swapped with the other side during the call, so you can call each other directly next time.
        val inboxKey = settings.inboxKey()
        // Start where the last call with them, from this kind of network, got to.
        val network = LinkConditions.networkKind(appContext)
        return Plan(
            current = current,
            route = route,
            profile = AudioProfile.forCall(current, route),
            radioPlan = radioPlan(current, route),
            peerId = settings.peerId(),
            me = Chat.Frame.Contact(current.displayName, InboxKeys.address(inboxKey)),
            inboxKey = inboxKey,
            theirAddress = theirAddress,
            network = network,
            memory = LinkMemories.find(settings.linkMemories(), theirAddress, network, System.currentTimeMillis()),
        )
    }

    private fun newSession(
        plan: Plan,
        room: String,
        base: String,
        withVideo: Boolean,
        outgoing: OutgoingRing?,
        contactName: String?,
        ringing: Boolean = false,
        answersRing: Boolean = false,
    ) = CallSession(
        context = appContext,
        room = room,
        serverBase = base,
        peerId = plan.peerId,
        settings = plan.current,
        profile = plan.profile,
        eglBase = eglBase,
        http = http,
        withVideo = withVideo,
        radioPlan = plan.radioPlan,
        me = plan.me,
        onContact = { contact -> scope.launch { settings.saveContact(contact) } },
        outgoing = outgoing,
        contactName = contactName,
        remoteAddress = plan.theirAddress,
        startFrom = plan.memory,
        network = plan.network,
        onLearned = { learned -> scope.launch { settings.saveLinkMemory(learned) } },
        ringing = ringing,
        answersRing = answersRing,
    )

    /** The contact the current call is ringing, while that ring is still going. */
    fun ringingOut(): RingingOut? {
        val ring = outgoing?.takeIf { it.alive } ?: return null
        return RingingOut(ring.contact.address, ring.video)
    }

    /**
     * We were calling each other at the same moment and their call won: leave ours
     * (once it exists) and take theirs.
     */
    fun switchTo(ring: IncomingRing, withVideo: Boolean) {
        scope.launch {
            starting.first { !it }
            supersededRing = outgoing
            _session.value?.hangUp()
            _session.first { it == null }
            answerCall(ring, withVideo)
        }
    }

    /** Joins [room] and waits for whoever has the link. */
    fun startCall(room: String, withVideo: Boolean) = begin(room, withVideo, calling = null, answering = null)

    /** Rings [contact]'s phone and waits for them in a new private room. */
    fun callContact(contact: Contact, withVideo: Boolean) = begin(RoomCodes.forDirectCall(), withVideo, calling = contact, answering = null)

    /** Takes a call that rang this phone: joins the room the caller is waiting in. */
    fun answerCall(ring: IncomingRing, withVideo: Boolean) = begin(ring.room, withVideo, calling = null, answering = ring)

    private fun begin(room: String, withVideo: Boolean, calling: Contact?, answering: IncomingRing?) {
        if (_session.value != null || starting.value) return
        starting.value = true
        // Answering the ring that connected early takes its connection; anything else leaves it.
        val earlier = early
        early = null
        scope.launch {
            // A call ended a moment ago may still be restoring the earbuds; don't overlap.
            cleanup?.join()
            val current = settings.current()
            val base = ServerUrls.normalizeBase(current.serverUrl)
            if (base == null) {
                earlier?.session?.hangUp()
                _lastError.value = "Add your server address in Settings first."
                starting.value = false
                return@launch
            }
            // A direct call's room is single-use; the room box keeps the one you typed.
            if (calling == null && answering == null) settings.update { it.copy(lastRoom = room) }
            val startedAtMillis = System.currentTimeMillis()
            val plan = plan(current, calling?.address ?: answering?.callerAddress)
            val route = plan.route
            val radioPlan = plan.radioPlan
            val outgoing = calling?.let { OutgoingRing(it, current.displayName, plan.inboxKey, withVideo) }
            this@CallManager.outgoing = outgoing
            calling?.let { settings.saveContact(it.copy(lastCallAtMillis = System.currentTimeMillis())) }
            // Made for this ring with what's still true now (voice or video, how the sound is wired): take it.
            val taken = earlier?.session?.takeIf {
                earlier.ringId == answering?.ringId && earlier.withVideo == withVideo &&
                    earlier.profile?.wiredLike(plan.profile) == true && it.state.value.isActive
            }
            if (taken == null) earlier?.session?.hangUp()
            val session = taken ?: newSession(
                plan,
                room,
                base,
                withVideo,
                outgoing,
                contactName = calling?.name ?: answering?.callerName,
                answersRing = answering != null,
            )
            val profile = session.profile
            _lastError.value = null
            _session.value = session
            starting.value = false
            CallService.start(appContext)
            if (radioPlan.preferCellular || current.mobileDataBackup) cellular.acquire()
            watchNetwork(session, current)
            if (taken != null) {
                Log.i(TAG, "Answered over the connection made while it rang")
                // It was planned when it started ringing; the route may have moved since.
                session.updateRadioPlan(radioPlan)
                session.answer()
            } else {
                session.start()
            }
            // Their name as the call went, for the history (they may have left by the end).
            var peerName: String? = null
            val nameJob = launch { session.state.collect { s -> s.remotePeer?.name?.takeIf { it.isNotBlank() }?.let { peerName = it } } }
            // If the system kills the app mid-call, the next launch can offer to rejoin.
            val aliveJob = launch {
                while (true) {
                    settings.markCallAlive(room, withVideo)
                    delay(InterruptedCall.ALIVE_EVERY_MS)
                }
            }
            // Game mode only matters when the call plays over the music link next to your music.
            val boost = current.autoGameMode && profile.mode == AudioMode.HIFI
            val boostJob = if (boost) launch { earbudBoost.begin() } else null
            val turbo = turboBoost?.takeIf { current.turboDuringCalls && profile.mode == AudioMode.HIFI }
            val turboJob = turbo?.let {
                val device = route.mediaOutput?.takeIf { o -> o.kind == DeviceKind.BLUETOOTH_MUSIC }?.name
                launch { it.begin(settings.delayRuns.first(), device) }
            }
            val lipSyncJob = if (profile.mode == AudioMode.HIFI) {
                launch { keepLipSync(session, profile, current, listOfNotNull(boostJob, turboJob)) }
            } else {
                null
            }
            val routeJob = launch { followRoute(session, profile, current) }
            val thermalJob = launch { followTemperature(session) }
            // In a pocket the camera films the lining and costs battery, heat and Wi-Fi airtime.
            val pocketJob = if (current.pocketGuard && withVideo) {
                launch {
                    PocketSensor(appContext).covered().collectLatest { covered ->
                        if (covered) delay(POCKET_SETTLE_MS) // not for a hand passing over it
                        session.setCameraPaused(covered)
                    }
                }
            } else {
                null
            }
            // Earbuds back from the case mid-call: many reset game mode, and HyperOS resets the codec.
            val reapplyJob = if (boost || turbo != null) {
                launch {
                    listOfNotNull(boostJob, turboJob).forEach { it.join() }
                    routeMonitor.route.map { it.bluetoothMusicAvailable }.distinctUntilChanged().drop(1).collect { available ->
                        if (!available) return@collect
                        delay(RECONNECT_SETTLE_MS)
                        if (boost) earbudBoost.begin()
                        val name = routeMonitor.snapshot().mediaOutput?.takeIf { it.kind == DeviceKind.BLUETOOTH_MUSIC }?.name
                        turbo?.begin(settings.delayRuns.first(), name)
                    }
                }
            } else {
                null
            }

            val end = session.state.first { !it.isActive }
            val endedAt = SystemClock.elapsedRealtime()
            nameJob.cancel()
            aliveJob.cancel()
            if (outgoing == null || outgoing !== supersededRing) {
                CallRecords.ended(
                    calling = calling,
                    answering = answering,
                    room = room,
                    peerName = peerName,
                    learnedAddress = session.theirAddress,
                    ringStatus = outgoing?.status,
                    connectedForMs = end.connectedAt?.let { endedAt - it },
                    video = withVideo,
                    startedAtMillis = startedAtMillis,
                    quality = end.quality,
                )?.let { settings.addCallRecord(it) }
            }
            settings.clearActiveCall()
            lipSyncJob?.cancel()
            routeJob.cancel()
            thermalJob.cancel()
            pocketJob?.cancel()
            reapplyJob?.cancelAndJoin()
            if (end.error != null) _lastError.value = end.error
            unwatchNetwork()
            cellular.release()
            if (this@CallManager.outgoing === outgoing) this@CallManager.outgoing = null
            if (_session.value === session) _session.value = null
            // Let a switch still in progress finish first, so end() knows what to undo.
            cleanup = scope.launch(NonCancellable) {
                if (boostJob != null) {
                    boostJob.join()
                    earbudBoost.end()
                }
                if (turboJob != null) {
                    turboJob.join()
                    turbo.end()
                }
            }
        }
    }

    /**
     * Keeps the app-to-ear delay, and [LipSync] with it, matched to wherever
     * the audio is playing, re-planning when the output changes (earbuds
     * connected or taken out).
     */
    private suspend fun keepLipSync(session: CallSession, profile: AudioProfile, current: AppSettings, settling: List<Job>) {
        // Game mode and Turbo change the delay; plan once they have settled.
        settling.forEach { it.join() }
        routeMonitor.route.map { it.mediaOutput }.distinctUntilChanged().collectLatest { output ->
            // LE Audio earbuds add their own delay too (less than A2DP, but enough to see).
            val onBluetooth = output?.kind == DeviceKind.BLUETOOTH_MUSIC || output?.kind == DeviceKind.BLUETOOTH_LE
            // Only Bluetooth outputs get measured in the delay tuner.
            val measured = if (onBluetooth) {
                DelayRuns.bestMatch(
                    settings.delayRuns.first(),
                    device = output.name,
                    gameModeOn = earbudBoost.status.value?.active == true,
                    codec = codec()?.takeIf { it.device == null || it.device == output.name }?.summary,
                    gameAudio = current.gameAudioLabel,
                )?.delayMs
            } else {
                null
            }
            val estimated = if (measured == null) {
                delay(PROBE_SETTLE_MS) // let the call's own playback start first
                LatencyProbe.estimateMs(profile.playbackAttributes)
            } else {
                null
            }
            val plan = if (current.lipSync) LipSync.plan(onBluetooth, measuredMs = measured, estimatedMs = estimated) else null
            session.setPlayout(plan, playoutMs = measured ?: estimated, measured = measured != null)
        }
    }

    /**
     * Keeps the call matched to where its audio plays as earbuds come and go:
     * echo cancellation (Hi-Fi with the automatic setting) and radio sharing.
     */
    private suspend fun followRoute(session: CallSession, profile: AudioProfile, current: AppSettings) {
        val echoFollowsRoute = profile.mode == AudioMode.HIFI && current.echoCancellation == EchoCancellation.AUTO
        val hold = OutputHold(startedPersonal = routeMonitor.snapshot().outputIsPersonal)
        routeMonitor.route.collectLatest { route ->
            session.updateRadioPlan(radioPlan(current, route))
            // In Hi-Fi the call plays as media, so it gets media's manners.
            if (profile.mode == AudioMode.HIFI) hold.onOutput(route.outputIsPersonal)?.let(session::setOutputHeld)
            if (!echoFollowsRoute) return@collectLatest
            val personal = route.outputIsPersonal
            // On at once (an echo is heard right away); off only once the earbuds have settled.
            if (personal) delay(ECHO_OFF_SETTLE_MS)
            session.setEchoCancellation(!personal)
        }
    }

    /** Protects the video when the phone overheats (Android 10+ reports its thermal status). */
    private suspend fun followTemperature(session: CallSession) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val power = appContext.getSystemService(PowerManager::class.java) ?: return
        callbackFlow {
            trySend(power.currentThermalStatus)
            val listener = PowerManager.OnThermalStatusChangedListener { trySend(it) }
            power.addThermalStatusListener(appContext.mainExecutor, listener)
            awaitClose { power.removeThermalStatusListener(listener) }
        }.distinctUntilChanged().collect { status -> session.setThermal(ThermalPlan.forStatus(status)) }
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

    private companion object {
        const val TAG = "EarshotCalls"
        const val PROBE_SETTLE_MS = 1_500L
        /** After earbuds reconnect, let A2DP and the companion channel come up first. */
        const val RECONNECT_SETTLE_MS = 3_000L
        const val ECHO_OFF_SETTLE_MS = 2_000L
        const val POCKET_SETTLE_MS = 2_000L
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
