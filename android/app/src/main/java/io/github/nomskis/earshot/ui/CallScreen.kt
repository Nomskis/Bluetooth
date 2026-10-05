package io.github.nomskis.earshot.ui

import android.Manifest
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.call.CallSession
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.call.ChatMessage
import io.github.nomskis.earshot.call.DelayBreakdown
import io.github.nomskis.earshot.call.LinkQuality
import io.github.nomskis.earshot.call.LipSync
import io.github.nomskis.earshot.calls.OutgoingRing
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.ui.theme.CallTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    session: CallSession,
    route: AudioRoute,
    keepScreenOn: Boolean,
    inPictureInPicture: Boolean,
    pocketGuard: Boolean = false,
    earbudBoost: EarbudBoost.Status?,
    turboNote: String?,
    onVoiceVolumeSaved: (Float) -> Unit,
    onFlipSaved: (Boolean) -> Unit = {},
    onLeaveScreen: () -> Unit,
    quickReplies: List<String> = QuickReplies.DEFAULT,
) {
    val live by session.state.collectAsStateWithLifecycle()
    val state = calmed(live)
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // Back keeps the call running and goes to the rest of the app; the bar at the top comes back here.
    BackHandler(onBack = onLeaveScreen)

    // Chat: what's unread, and their latest message as a bubble while it's closed.
    var chatOpen by rememberSaveable(session) { mutableStateOf(false) }
    BackHandler(enabled = chatOpen) { chatOpen = false }
    val incoming = state.chat.count { !it.mine }
    var seenIncoming by rememberSaveable(session) { mutableIntStateOf(0) }
    LaunchedEffect(chatOpen, incoming) { if (chatOpen) seenIncoming = incoming }
    var bubble by remember(session) { mutableStateOf<ChatMessage?>(null) }
    var bubbleShownId by rememberSaveable(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.lastIncomingChat?.id, chatOpen) {
        val message = state.lastIncomingChat
        if (chatOpen || message == null || message.id == bubbleShownId) {
            bubble = null
            return@LaunchedEffect
        }
        bubbleShownId = message.id
        bubble = message
        delay(BUBBLE_MS)
        bubble = null
    }

    val showRemoteVideo = state.hasRemoteVideo && !state.remoteMedia.cameraOff
    val showOwnVideo = state.hasCamera && !state.cameraOff && !state.cameraPaused
    // Screen off at your ear, as in a phone call; never while there's video on the screen.
    val atEar = state.speakerOn == false
    PocketGuard(enabled = (pocketGuard || atEar) && !inPictureInPicture && !showRemoteVideo && !showOwnVideo)
    // The head-start cue: lights up as her voice enters the phone, before the
    // Bluetooth delay lets you hear it, so you know not to talk over her.
    val glow by animateFloatAsState(
        targetValue = if (state.remoteSpeaking) 1f else 0f,
        animationSpec = tween(durationMillis = if (state.remoteSpeaking) 60 else 400),
        label = "speaking glow",
    )

    // Tap the small video to swap: yours big, theirs small. Only while both are there to swap.
    var swapped by rememberSaveable(session) { mutableStateOf(false) }
    val ownVideoBig = swapped && showRemoteVideo && showOwnVideo && !chatOpen && !inPictureInPicture
    var area by remember { mutableStateOf(IntSize.Zero) }

    // Once the call is going the buttons get out of the way, and a tap anywhere brings them back
    // or puts them away. While it's still connecting they stay up.
    val videoOnScreen = showRemoteVideo || showOwnVideo
    var controlsShown by rememberSaveable(session) { mutableStateOf(true) }
    var sheet by rememberSaveable(session) { mutableStateOf<CallSheet?>(null) }
    val connected = state.phase == CallPhase.CONNECTED
    LaunchedEffect(controlsShown, videoOnScreen, sheet, chatOpen, connected) {
        if (controlsShown && sheet == null && !chatOpen && connected) {
            delay(if (videoOnScreen) CONTROLS_HIDE_MS else VOICE_CONTROLS_HIDE_MS)
            controlsShown = false
        }
    }
    val showControls = controlsShown || !connected
    // When the buttons last appeared: a quick second tap meant for the screen mustn't hang up.
    var shownAt by remember(session) { mutableLongStateOf(0L) }
    LaunchedEffect(showControls) { if (showControls) shownAt = SystemClock.uptimeMillis() }
    val unread = (incoming - seenIncoming).coerceAtLeast(0)

    // Our camera on over a connection that stays weak: offer to go voice-only, as Meet does.
    // Once per call, and never on its own: the camera is yours to turn off.
    val weakWithVideo = connected && showOwnVideo && weakConnectionLabel(state.delay, null) != null
    var weakOfferDismissed by rememberSaveable(session) { mutableStateOf(false) }
    var weakOffer by remember(session) { mutableStateOf(false) }
    LaunchedEffect(weakWithVideo) {
        weakOffer = false
        if (weakWithVideo) {
            delay(WEAK_VIDEO_OFFER_AFTER_MS)
            weakOffer = true
        }
    }

    // Turning the camera on in a call that started as a voice call may need the permission first.
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) session.setCameraOff(false)
    }
    fun toggleCamera() {
        if (state.cameraOff && !context.hasPermission(Manifest.permission.CAMERA)) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            session.setCameraOff(!state.cameraOff)
        }
    }

    CallTheme {
        DarkSystemBars()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { area = it },
        ) {
            if (ownVideoBig) {
                // The frames as sent, Flip included: what you see is what they see.
                VideoRenderer(sink = session.localPreview, eglContext = session.eglContext, modifier = Modifier.fillMaxSize())
            } else if (showRemoteVideo) {
                VideoRenderer(
                    sink = session.remoteVideo,
                    eglContext = session.eglContext,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                RemotePlaceholder(state, compact = inPictureInPicture)
            }

            if (glow > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(width = if (inPictureInPicture) 3.dp else 6.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = glow)),
                )
            }

            if (inPictureInPicture) return@Box

            if (connected) {
                // Over the video's own surface, which doesn't take touches.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                controlsShown = !controlsShown
                                if (controlsShown) shownAt = SystemClock.uptimeMillis()
                            }
                        }
                        .semantics { contentDescription = if (showControls) "Hide call buttons" else "Show call buttons" },
                )
            }

            // Without video there's nothing to uncover, so who and how long stay up.
            AnimatedVisibility(
                visible = showControls || !videoOnScreen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopBar(state, onInfo = { sheet = CallSheet.INFO }, onMinimize = onLeaveScreen, showWho = videoOnScreen)
            }

            if (showOwnVideo && !chatOpen && area != IntSize.Zero) {
                FloatingVideo(
                    sink = if (ownVideoBig) session.remoteVideo else session.localPreview,
                    eglContext = session.eglContext,
                    area = area,
                    key = session,
                    onTap = { if (showRemoteVideo) swapped = !swapped },
                    description = if (ownVideoBig) "Their video: drag to move, tap to swap" else "Your video: drag to move, tap to swap",
                )
            }

            if (weakOffer && !weakOfferDismissed && !chatOpen) {
                WeakVideoOffer(
                    onTurnOff = {
                        weakOfferDismissed = true
                        session.setCameraOff(true)
                    },
                    onDismiss = { weakOfferDismissed = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = ABOVE_CONTROLS),
                )
            }

            if (state.outputHeld) {
                OutputHeldBanner(
                    name = state.remotePeer?.name?.takeIf { it.isNotBlank() },
                    onPlayOutLoud = { session.setOutputHeld(false) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            bubble?.let { message ->
                ChatBubble(
                    name = state.remotePeer?.name?.takeIf { it.isNotBlank() } ?: "They",
                    message = message,
                    onOpen = { chatOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 136.dp, bottom = ABOVE_CONTROLS + 72.dp),
                )
            }

            if (chatOpen && state.chatAvailable) {
                ChatPanel(
                    messages = state.chat,
                    onSend = session::sendChat,
                    onClose = { chatOpen = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    quickReplies = quickReplies,
                )
            } else {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Controls(
                        state = state,
                        onMic = { session.setMicMuted(!state.micMuted) },
                        onCamera = ::toggleCamera,
                        onSwitchCamera = session::switchCamera,
                        onSpeaker = { session.setSpeaker(state.speakerOn != true) },
                        onMore = { sheet = CallSheet.MORE },
                        onHangUp = { if (SystemClock.uptimeMillis() - shownAt >= HANG_UP_GUARD_MS) session.hangUp() },
                        unreadChat = unread,
                    )
                }
            }

            when (sheet) {
                CallSheet.MORE -> CallSheetHost(onDismiss = { sheet = null }) {
                    MoreMenu(
                        state = state,
                        unreadChat = unread,
                        onChat = {
                            sheet = null
                            chatOpen = true
                        },
                        onSpeaker = { session.setSpeaker(state.speakerOn != true) },
                        onSwitchCamera = session::switchCamera,
                        onFlip = {
                            session.setFlipped(!state.flipped)
                            onFlipSaved(!state.flipped)
                        },
                        onVolume = session::setVoiceVolume,
                        onVolumeDone = onVoiceVolumeSaved,
                        onReplay = session::toggleReplay,
                        onEarbudMic = { session.setEarbudMic(!state.earbudMic) },
                        onInfo = { sheet = CallSheet.INFO },
                    )
                }
                CallSheet.INFO -> CallSheetHost(onDismiss = { sheet = null }) {
                    CallInfo(state, route, listOfNotNull(earbudBoost?.text, turboNote))
                }
                null -> Unit
            }
        }
    }
}

/**
 * The call as it should read: a connection that drops for a moment and comes straight back
 * (common on mobile data) stays "connected", timer and all. Only one that's gone longer than
 * [RECONNECT_SHOWN_AFTER_MS] says "Reconnecting".
 */
@Composable
internal fun calmed(state: CallState): CallState {
    var showReconnecting by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        showReconnecting = false
        if (state.phase == CallPhase.RECONNECTING) {
            delay(RECONNECT_SHOWN_AFTER_MS)
            showReconnecting = true
        }
    }
    val blip = state.phase == CallPhase.RECONNECTING && !showReconnecting && state.connectedAt != null
    return if (blip) state.copy(phase = CallPhase.CONNECTED) else state
}

private const val RECONNECT_SHOWN_AFTER_MS = 2_000L

/** The sheets a call can open over the video. */
internal enum class CallSheet { MORE, INFO }

/** How long the connection has to stay weak with our camera on before voice-only is offered. */
private const val WEAK_VIDEO_OFFER_AFTER_MS = 10_000L

/** "Weak connection" with Turn off video, over the call, in a snackbar's colours. */
@Composable
internal fun WeakVideoOffer(onTurnOff: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.inverseSurface)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Weak connection", color = colors.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onTurnOff) { Text("Turn off video", color = colors.inversePrimary) }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = colors.inverseOnSurface) }
    }
}

/** Clear of the control panel at the bottom: two rows of call buttons and its margins. */
private val ABOVE_CONTROLS = 200.dp

/** How long the call buttons stay up after the last tap: over video, and in a voice call. */
private const val CONTROLS_HIDE_MS = 4_000L
private const val VOICE_CONTROLS_HIDE_MS = 6_000L

/** Hang up ignores taps this soon after the buttons appear. */
private const val HANG_UP_GUARD_MS = 600L

private const val BUBBLE_MS = 6_000L

/**
 * Screen off while the proximity sensor is covered, as in a phone call: in a
 * gym pocket the call screen can't be tapped by accident. The call itself,
 * camera included, carries on.
 */
@Composable
private fun PocketGuard(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val power = context.getSystemService(PowerManager::class.java)
        val lock = if (enabled && power?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "earshot:pocket").apply {
                setReferenceCounted(false)
                acquire(POCKET_GUARD_MAX_MS)
            }
        } else {
            null
        }
        onDispose {
            // Wait for the sensor to clear, so the screen doesn't light up still in the pocket.
            lock?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }
}

/** A safety net; the guard is released when the call screen goes. */
private const val POCKET_GUARD_MAX_MS = 6 * 60 * 60 * 1000L

/** Earbuds went away mid-call: their voice waits rather than coming out of the loudspeaker. */
@Composable
internal fun OutputHeldBanner(name: String?, onPlayOutLoud: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Earbuds disconnected. ${name?.let { "$it's" } ?: "Their"} voice is paused.",
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        FilledTonalButton(onClick = onPlayOutLoud) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Play on speaker")
        }
    }
}

/**
 * The middle of the screen when there's no video: who, large, as in the phone app, then the
 * timer once you're talking and a word on what's happening.
 */
@Composable
internal fun RemotePlaceholder(state: CallState, compact: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.connectedAt) {
        while (state.connectedAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val peerName = state.remotePeer?.name?.takeIf { it.isNotBlank() }
    val outgoing = state.outgoing
    val who = peerName ?: state.contactName ?: outgoing?.name?.takeIf { it.isNotBlank() }
    val text = callingText(state) ?: when (state.phase) {
        CallPhase.CONNECTING -> state.error ?: "Connecting to the server…"
        CallPhase.WAITING -> who?.let { "Waiting for $it to join" } ?: "Waiting for the other person to join\n${state.room}"
        CallPhase.NEGOTIATING -> "Connecting…"
        CallPhase.RECONNECTING -> "Reconnecting…"
        CallPhase.CONNECTED -> when {
            state.remoteMedia.inPocket -> "${peerName?.let { "$it's" } ?: "Their"} camera is paused"
            state.remoteMedia.weakConnection -> "Video paused: weak connection"
            // Neither camera on: a voice call, which either side can turn into video.
            state.remoteMedia.cameraOff && state.cameraOff -> "Voice call"
            state.remoteMedia.cameraOff -> "${peerName ?: "They"} turned the camera off"
            else -> "Connected"
        }
        CallPhase.ENDED -> "Call ended"
        CallPhase.FAILED -> state.error ?: "Call failed"
    }
    val connectedAt = state.connectedAt?.takeIf { state.phase == CallPhase.CONNECTED }
    val working = state.phase == CallPhase.CONNECTING || state.phase == CallPhase.NEGOTIATING || state.phase == CallPhase.RECONNECTING
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (who != null) {
            // Gently, and only while their phone is really ringing.
            val pulse by rememberInfiniteTransition(label = "calling").animateFloat(
                initialValue = 1f,
                targetValue = if (outgoing?.status == OutgoingRing.Status.RINGING) 1.06f else 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 1_100), RepeatMode.Reverse),
                label = "pulse",
            )
            Avatar(who, seed = state.contactAddress ?: who, size = if (compact) 44.dp else 120.dp, modifier = Modifier.scale(pulse))
            Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
            if (!compact) {
                Text(
                    who,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
        } else if (working) {
            CircularProgressIndicator(modifier = Modifier.size(if (compact) 20.dp else 36.dp))
            Spacer(Modifier.height(16.dp))
        }
        if (!compact && connectedAt != null) {
            Text(callTimer(now - connectedAt), color = colors.onSurface, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        )
        if (!compact) {
            state.connectHint?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        }
        // A direct call only offers the link once their phone can't be rung.
        val offerLink = state.phase == CallPhase.WAITING &&
            (outgoing == null || outgoing.status == OutgoingRing.Status.UNREACHABLE)
        if (!compact && offerLink) {
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = { context.shareInvite(state.inviteLink) }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send invite link")
            }
        }
    }
}

/**
 * What to say while ringing a contact (calling, ringing, declined...), or null when this isn't
 * a call to a contact or they've joined. Short: their name is always right next to it.
 */
internal fun callingText(state: CallState): String? {
    val outgoing = state.outgoing ?: return null
    if (state.remotePeer != null || !state.isActive) return null
    if (state.phase == CallPhase.CONNECTING && state.error != null) return null
    return when (outgoing.status) {
        OutgoingRing.Status.CALLING -> "Calling…"
        OutgoingRing.Status.RINGING -> "Ringing…"
        OutgoingRing.Status.ANSWERED -> "Answered. Connecting…"
        OutgoingRing.Status.UNREACHABLE -> if (outgoing.keepsTrying) "Can't reach their phone. Still trying…" else "Can't reach their phone"
        OutgoingRing.Status.DECLINED -> "Declined"
        OutgoingRing.Status.BUSY -> "On another call"
        OutgoingRing.Status.NO_ANSWER -> "No answer"
    }
}

/**
 * Over video: who you're talking to and how long, a word when something needs one, and
 * Minimize. Tapping it opens the call's details. Without video the middle of the screen says
 * who ([showWho] false), so only Minimize and the words stay up here.
 */
@Composable
internal fun TopBar(
    state: CallState,
    modifier: Modifier = Modifier,
    onInfo: () -> Unit = {},
    onMinimize: (() -> Unit)? = null,
    showWho: Boolean = true,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.connectedAt) {
        while (state.connectedAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    // Readable over any picture, without a band of shade across the video.
    val overVideo = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f)
    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Call details", onClick = onInfo)
                .padding(horizontal = 56.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showWho) {
                Text(
                    state.remotePeer?.name?.takeIf { it.isNotBlank() } ?: state.contactName ?: state.room,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(shadow = overVideo),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(callStatus(state, now), color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(shadow = overVideo))
            }
            val badges = buildList {
                if (state.remoteSpeaking) add("Talking" to true)
                if (state.remoteMedia.micMuted) add("${state.remotePeer?.name?.takeIf { it.isNotBlank() } ?: "They"} muted" to false)
                if (state.phase == CallPhase.CONNECTED) weakConnectionLabel(state.delay, null)?.let { add(it to false) }
                if (!state.signalingOnline && state.phase == CallPhase.CONNECTED) add("Server offline" to false)
            }
            if (badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { (text, highlight) -> Badge(text, highlight) }
                }
            }
        }
        if (onMinimize != null) {
            IconButton(
                onClick = onMinimize,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Minimize")
            }
        }
    }
}

/** The timer once connected; otherwise what's happening. */
internal fun callStatus(state: CallState, nowMs: Long): String {
    callingText(state)?.let { return it }
    val connectedAt = state.connectedAt
    return when (state.phase) {
        CallPhase.CONNECTED -> if (connectedAt != null) callTimer(nowMs - connectedAt) else "Connected"
        CallPhase.CONNECTING, CallPhase.NEGOTIATING -> "Connecting…"
        CallPhase.WAITING -> "Waiting for them to join"
        CallPhase.RECONNECTING -> "Reconnecting…"
        CallPhase.ENDED -> "Call ended"
        CallPhase.FAILED -> state.error ?: "Call failed"
    }
}

/** 0:07, 12:34, 1:02:03. */
internal fun callTimer(elapsedMs: Long): String {
    val total = (elapsedMs / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * "Weak connection" when either direction is poor. Not which side it's on: each phone sees
 * its incoming voice arrive late as well as lost, but only lost packets of what it sends, so
 * on a jittery link both phones found the incoming side weak and blamed the other person.
 */
internal fun weakConnectionLabel(delay: DelayBreakdown?, @Suppress("UNUSED_PARAMETER") name: String?): String? {
    val weak = delay?.fromThem == LinkQuality.POOR || delay?.toThem == LinkQuality.POOR
    return if (weak) "Weak connection" else null
}

/** One direction in words: "good", or "poor (12% lost, 4% filled in)". */
internal fun describeDirection(quality: LinkQuality, lossPercent: Double?, concealedPercent: Double? = null): String {
    val word = when (quality) {
        LinkQuality.GOOD -> "good"
        LinkQuality.FAIR -> "fair"
        LinkQuality.POOR -> "poor"
    }
    val details = listOfNotNull(
        lossPercent?.takeIf { it >= 0.5 }?.let { "%.0f%% lost".format(it) },
        concealedPercent?.takeIf { it >= 0.5 }?.let { "%.0f%% filled in".format(it) },
    )
    return if (details.isEmpty()) word else "$word (${details.joinToString(", ")})"
}

/** The call's details: where its audio goes, where the delay comes from, and what Earshot is doing. */
@Composable
internal fun CallInfo(state: CallState, route: AudioRoute, boostNotes: List<String>) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodyMedium
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Call details", style = MaterialTheme.typography.titleMedium)
        RouteChip(route, state.audioMode)
        state.delay?.let { delay ->
            val name = state.remotePeer?.name?.takeIf { it.isNotBlank() } ?: "them"
            delay.totalMs?.let { Text("≈ $it ms from their mouth to your ear", style = MaterialTheme.typography.bodyLarge) }
            delay.fromThem?.let { Text("From $name: ${describeDirection(it, delay.lossPercent, delay.concealedPercent)}", color = dim, style = small) }
            delay.toThem?.let { Text("To $name: ${describeDirection(it, delay.sendLossPercent)}", color = dim, style = small) }
            val packets = if (delay.packetMs > 20) ", ${delay.packetMs} ms packets for a rough link" else ""
            Text("Their phone ≈ ${delay.senderMs} ms (estimate$packets)", color = dim, style = small)
            val loss = delay.lossPercent?.let { if (it < 0.05) ", no packets lost" else ", %.1f%% packets lost (repaired where possible)".format(it) } ?: ""
            val via = when (delay.relayed) {
                true -> " through a relay"
                false -> ", direct"
                null -> ""
            }
            Text("Network ${delay.networkMs} ms$via$loss", color = dim, style = small)
            Text("Smoothing buffer ${delay.jitterBufferMs} ms", color = dim, style = small)
            Text(
                "Your phone and earbuds ${delay.playoutMs} ms" + if (delay.playoutMeasured) " (measured)" else " (Android's estimate)",
                color = dim,
                style = small,
            )
        }
        val lipSync = state.lipSync?.takeIf { state.hasRemoteVideo && !state.earbudMic }?.let {
            "Video held back ${it.videoDelayMs} ms to match the earbuds" + if (it.source == LipSync.Source.MEASURED) " (measured)" else ""
        }
        val notes = boostNotes + listOfNotNull(
            state.radioNote,
            state.routeNote,
            lipSync,
            "Using the earbuds' mic (call quality)".takeIf { state.earbudMic },
            "Weak connection: your video is paused".takeIf { state.videoPausedForVoice },
            "Phone is hot: lighter video".takeIf { state.thermal != null },
            "Echo cancellation on (playing out loud)".takeIf { state.echoGuard },
            "Music dip off: your music app pauses instead".takeIf { state.smartDuckUnsupported },
        )
        notes.forEach { Text(it, color = dim, style = small) }
    }
}

/** A bottom sheet over the call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallSheetHost(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { content() }
}

@Composable
private fun Badge(text: String, highlight: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        color = if (highlight) colors.onPrimary else colors.onSurface,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlight) colors.primary else colors.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The call's buttons on one panel, like the phone app: the camera switch while you're on video
 * (the loudspeaker otherwise), the camera, mute and more, each round when off and a rounded
 * square when on; under them, hang up, wider and red.
 */
@Composable
internal fun Controls(
    state: CallState,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSpeaker: () -> Unit,
    onMore: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
    unreadChat: Int = 0,
) {
    val cameraOn = state.hasCamera && !state.cameraOff
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            when {
                cameraOn -> CallToggleButton(Icons.Filled.Cameraswitch, "Switch camera", checked = false, onClick = onSwitchCamera)
                state.speakerOn != null -> CallToggleButton(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    if (state.speakerOn) "Speaker off" else "Speaker on",
                    checked = state.speakerOn,
                    onClick = onSpeaker,
                )
            }
            if (state.hasCamera) {
                CallToggleButton(
                    icon = if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = if (cameraOn) "Turn camera off" else "Turn camera on",
                    checked = !cameraOn,
                    onClick = onCamera,
                )
            }
            CallToggleButton(
                icon = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (state.micMuted) "Unmute" else "Mute",
                checked = state.micMuted,
                onClick = onMic,
            )
            BadgedBox(
                badge = {
                    if (unreadChat > 0) {
                        androidx.compose.material3.Badge { Text(if (unreadChat > 9) "9+" else "$unreadChat") }
                    }
                },
            ) {
                CallToggleButton(
                    Icons.Filled.MoreHoriz,
                    if (unreadChat > 0) "More, $unreadChat unread messages" else "More",
                    checked = false,
                    onClick = onMore,
                )
            }
        }
        HangUpButton(onClick = onHangUp, width = 136.dp)
    }
}

/** Everything that isn't needed all the time, one tap away. */
@Composable
internal fun MoreMenu(
    state: CallState,
    unreadChat: Int,
    onChat: () -> Unit,
    onSpeaker: () -> Unit,
    onSwitchCamera: () -> Unit,
    onFlip: () -> Unit,
    onVolume: (Float) -> Unit,
    onVolumeDone: (Float) -> Unit,
    onReplay: () -> Unit,
    onEarbudMic: () -> Unit,
    onInfo: () -> Unit,
) {
    val cameraOn = state.hasCamera && !state.cameraOff
    var volume by remember(state.voiceVolume) { mutableFloatStateOf(state.voiceVolume) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (state.chatAvailable) {
            MenuItem(Icons.AutoMirrored.Filled.Chat, if (unreadChat > 0) "Chat ($unreadChat new)" else "Chat", onChat)
        }
        // On video the row has the camera switch; the loudspeaker lives here then.
        if (cameraOn && state.speakerOn != null) {
            MenuItem(Icons.AutoMirrored.Filled.VolumeUp, if (state.speakerOn) "Speaker off" else "Speaker on", onSpeaker)
        }
        if (!cameraOn && state.hasCamera) {
            MenuItem(Icons.Filled.Cameraswitch, "Switch camera", onSwitchCamera)
        }
        if (cameraOn) {
            MenuItem(Icons.Filled.Flip, if (state.flipped) "Mirror: on" else "Mirror: off", onFlip)
        }
        if (state.canReplay) {
            MenuItem(Icons.Filled.Replay, if (state.replaying) "Stop replay" else "Replay last 8 s", onReplay)
        }
        if (state.earbudMicAvailable) {
            MenuItem(
                Icons.Filled.HeadsetMic,
                if (state.earbudMic) "Use phone mic" else "Use earbuds' mic",
                onEarbudMic,
            )
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Volume ${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    onVolume(it)
                },
                onValueChangeFinished = { onVolumeDone(volume) },
                valueRange = 0f..4f,
            )
        }
        MenuItem(Icons.Filled.Info, "Call details", onInfo)
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
