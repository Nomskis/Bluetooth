package io.github.nomskis.earshot.ui

import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.nomskis.earshot.ui.theme.Accent
import io.github.nomskis.earshot.ui.theme.Danger
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
) {
    val state by session.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // Back keeps the call running; the notification brings you back.
    BackHandler(onBack = onLeaveScreen)
    PocketGuard(enabled = pocketGuard && !inPictureInPicture)

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
    // The head-start cue: lights up as her voice enters the phone, before the
    // Bluetooth delay lets you hear it, so you know not to talk over her.
    val glow by animateFloatAsState(
        targetValue = if (state.remoteSpeaking) 1f else 0f,
        animationSpec = tween(durationMillis = if (state.remoteSpeaking) 60 else 400),
        label = "speaking glow",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (showRemoteVideo) {
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
                    .border(width = if (inPictureInPicture) 3.dp else 6.dp, color = Accent.copy(alpha = glow)),
            )
        }

        if (inPictureInPicture) return@Box

        TopBar(state, route, listOfNotNull(earbudBoost?.text, turboNote), Modifier.align(Alignment.TopCenter))

        if (state.hasCamera && !state.cameraOff && !state.cameraPaused && !chatOpen) {
            VideoRenderer(
                sink = session.localPreview,
                eglContext = session.eglContext,
                // The frames as sent, Flip included: what you see is what they see.
                mirror = false,
                overlay = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 120.dp)
                    .size(width = 108.dp, height = 156.dp)
                    .clip(RoundedCornerShape(14.dp)),
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
                    .padding(start = 16.dp, end = 136.dp, bottom = 180.dp),
            )
        }

        if (chatOpen && state.chatAvailable) {
            ChatPanel(
                messages = state.chat,
                onSend = session::sendChat,
                onClose = { chatOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            Controls(
                state = state,
                onMic = { session.setMicMuted(!state.micMuted) },
                onCamera = { session.setCameraOff(!state.cameraOff) },
                onSwitchCamera = session::switchCamera,
                onFlip = {
                    session.setFlipped(!state.flipped)
                    onFlipSaved(!state.flipped)
                },
                onVolume = session::setVoiceVolume,
                onVolumeDone = onVoiceVolumeSaved,
                onReplay = session::toggleReplay,
                onEarbudMic = { session.setEarbudMic(!state.earbudMic) },
                onHangUp = session::hangUp,
                onChat = { chatOpen = true },
                unreadChat = (incoming - seenIncoming).coerceAtLeast(0),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

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
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Your earbuds disconnected, so ${name?.let { "$it's" } ?: "their"} voice is paused. They can still hear you.",
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        FilledTonalButton(onClick = onPlayOutLoud) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Play on speaker")
        }
    }
}

@Composable
internal fun RemotePlaceholder(state: CallState, compact: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1B5E4B), Color.Black)))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val peerName = state.remotePeer?.name?.takeIf { it.isNotBlank() }
        val calling = callingText(state)
        val text = calling ?: when (state.phase) {
            CallPhase.CONNECTING -> state.error ?: "Connecting to the server…"
            CallPhase.WAITING -> state.contactName?.let { "Waiting for $it to join" } ?: "Waiting for the other person to join\n${state.room}"
            CallPhase.NEGOTIATING -> "Connecting to ${peerName ?: "the other person"}…"
            CallPhase.RECONNECTING -> "Connection lost. Reconnecting…"
            CallPhase.CONNECTED -> when {
                state.remoteMedia.inPocket -> "Camera paused: ${peerName?.let { "$it's" } ?: "their"} phone is in a pocket"
                state.remoteMedia.weakConnection ->
                    "${peerName?.let { "$it's" } ?: "Their"} video is paused: the connection is too weak for it right now, " +
                        "so the voice gets through. It comes back by itself."
                state.remoteMedia.cameraOff -> "${peerName ?: "They"} turned the camera off"
                else -> "Connected"
            }
            CallPhase.ENDED -> "Call ended"
            CallPhase.FAILED -> state.error ?: "Call failed"
        }
        val outgoing = state.outgoing
        if (calling != null && !compact) {
            CallingAvatar(state.contactName ?: outgoing?.name.orEmpty(), ringing = outgoing?.status == OutgoingRing.Status.RINGING)
            Spacer(Modifier.height(24.dp))
        } else if (state.phase == CallPhase.CONNECTING || state.phase == CallPhase.NEGOTIATING || state.phase == CallPhase.RECONNECTING) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(if (compact) 20.dp else 36.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
        )
        if (!compact) {
            state.connectHint?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        }
        // A direct call only offers the link once their phone can't be rung.
        val offerLink = state.phase == CallPhase.WAITING &&
            (outgoing == null || outgoing.status == OutgoingRing.Status.UNREACHABLE)
        if (!compact && offerLink) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = { context.shareInvite(state.inviteLink) }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send invite link")
            }
        }
    }
}

/**
 * What to say while ringing a contact (calling, ringing, declined...), or null when
 * this isn't a call to a contact or they've joined.
 */
internal fun callingText(state: CallState): String? {
    val outgoing = state.outgoing ?: return null
    if (state.remotePeer != null || !state.isActive) return null
    if (state.phase == CallPhase.CONNECTING && state.error != null) return null
    val name = outgoing.name
    return when (outgoing.status) {
        OutgoingRing.Status.CALLING -> "Calling $name…"
        OutgoingRing.Status.RINGING -> "Ringing $name…"
        OutgoingRing.Status.ANSWERED -> "$name answered. Connecting…"
        OutgoingRing.Status.UNREACHABLE -> if (outgoing.keepsTrying) {
            "$name's phone can't be reached right now.\nEarshot keeps trying, or send the invite link."
        } else {
            "$name's phone can't be reached.\nSend the invite link, or hang up and try later."
        }
        OutgoingRing.Status.DECLINED -> "$name declined"
        OutgoingRing.Status.BUSY -> "$name is on another call"
        OutgoingRing.Status.NO_ANSWER -> "No answer from $name"
    }
}

/** Their initial in a circle, pulsing gently while their phone rings. */
@Composable
private fun CallingAvatar(name: String, ringing: Boolean) {
    val pulse by rememberInfiniteTransition(label = "calling").animateFloat(
        initialValue = 1f,
        targetValue = if (ringing) 1.1f else 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(120.dp)
            .scale(pulse)
            .background(Accent.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(96.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.Black,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun TopBar(state: CallState, route: AudioRoute, boostNotes: List<String>, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.remotePeer?.name?.takeIf { it.isNotBlank() } ?: state.contactName ?: state.room,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.remoteSpeaking) {
                Spacer(Modifier.width(8.dp))
                Badge("Talking", highlight = true)
            }
            if (state.remoteMedia.micMuted) {
                Spacer(Modifier.width(8.dp))
                Badge("Muted")
            }
            val weak = weakConnectionLabel(state.delay, state.remotePeer?.name?.takeIf { it.isNotBlank() })
            if (weak != null && state.phase == CallPhase.CONNECTED) {
                Spacer(Modifier.width(8.dp))
                Badge(weak)
            }
            if (!state.signalingOnline && state.phase == CallPhase.CONNECTED) {
                Spacer(Modifier.width(8.dp))
                Badge("Server offline")
            }
        }
        RouteChip(route, state.audioMode)
        state.delay?.let { DelayChip(it, state.remotePeer?.name?.takeIf { n -> n.isNotBlank() }) }
        // What Earshot is doing for the earbuds behind the scenes.
        if (state.earbudMic) {
            Text(
                "Using your earbuds' mic: they're on the call link, so music sounds like a call until you switch back.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val lipSync = state.lipSync?.takeIf { state.hasRemoteVideo && !state.earbudMic }?.let {
            "Video held back ${it.videoDelayMs} ms to match the earbuds" +
                if (it.source == LipSync.Source.MEASURED) " (measured)" else ""
        }
        (boostNotes + listOfNotNull(state.radioNote, state.routeNote, lipSync)).forEach { note ->
            Text(note, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
        if (state.videoPausedForVoice) {
            Text(
                "Weak connection: your video is paused so your voice gets through. It comes back by itself.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.thermal != null) {
            Text(
                "Your phone is overheating, so your video is lighter until it cools down (otherwise Android would switch the camera off).",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.echoGuard) {
            Text(
                "Playing out loud now, so echo cancellation is on.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.smartDuckUnsupported) {
            Text(
                "Your music app pauses instead of dipping, so the music dip is off for this call.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Which way the connection is weak, when it is: on a call between two
 * countries the weak side is usually one person's uplink, and knowing whose
 * tells you who should move closer to the router or off a busy network.
 */
internal fun weakConnectionLabel(delay: DelayBreakdown?, name: String?): String? {
    val from = delay?.fromThem == LinkQuality.POOR
    val to = delay?.toThem == LinkQuality.POOR
    val who = name ?: "them"
    return when {
        from && to -> "Weak connection"
        from -> "Weak connection from $who"
        to -> "Weak connection to $who"
        else -> null
    }
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

/** "≈ 230 ms mouth to ear"; tap for where the time goes, and how each direction is doing. */
@Composable
private fun DelayChip(delay: DelayBreakdown, name: String?) {
    var open by rememberSaveable { mutableStateOf(false) }
    val total = delay.totalMs ?: return
    Column(
        Modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("≈ $total ms from their mouth to your ear", color = Color.White, style = MaterialTheme.typography.labelMedium)
        if (open) {
            val small = MaterialTheme.typography.bodySmall
            val dim = Color.White.copy(alpha = 0.8f)
            val who = name ?: "them"
            delay.fromThem?.let { Text("From $who: ${describeDirection(it, delay.lossPercent, delay.concealedPercent)}", color = dim, style = small) }
            delay.toThem?.let { quality ->
                val squeezed = when (delay.sendSqueeze) {
                    LinkQuality.POOR -> ", your video paused to fit"
                    LinkQuality.FAIR -> ", your voice leaner to fit"
                    else -> ""
                }
                Text("To $who: ${describeDirection(quality, delay.sendLossPercent)}$squeezed", color = dim, style = small)
            }
            val packets = if (delay.packetMs > 10) ", ${delay.packetMs} ms packets for a rough link" else ""
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
    }
}

@Composable
private fun Badge(text: String, highlight: Boolean = false) {
    Text(
        text,
        color = if (highlight) Color.Black else Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(if (highlight) Accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
internal fun Controls(
    state: CallState,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onFlip: () -> Unit = {},
    onVolume: (Float) -> Unit,
    onVolumeDone: (Float) -> Unit,
    onReplay: () -> Unit,
    onEarbudMic: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier,
    onChat: () -> Unit = {},
    unreadChat: Int = 0,
) {
    var showVolume by rememberSaveable { mutableStateOf(false) }
    var volume by remember(state.voiceVolume) { mutableFloatStateOf(state.voiceVolume) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showVolume) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Their voice: ${(volume * 100).roundToInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        onVolume(it)
                    },
                    onValueChangeFinished = { onVolumeDone(volume) },
                    valueRange = 0f..4f,
                )
                Text(
                    "Separate from your music. Use the phone's volume keys for both together.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // Extras above, the essentials below, so it all fits a narrow phone.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.hasCamera) {
                ControlButton(Icons.Filled.Cameraswitch, "Switch camera", active = false, small = true, onClick = onSwitchCamera)
                ControlButton(Icons.Filled.Flip, "Flip", active = state.flipped, small = true, onClick = onFlip)
            }
            ControlButton(
                Icons.AutoMirrored.Filled.VolumeUp,
                "Voice volume",
                active = showVolume,
                small = true,
                onClick = { showVolume = !showVolume },
            )
            if (state.earbudMicAvailable) {
                ControlButton(
                    Icons.Filled.HeadsetMic,
                    if (state.earbudMic) "Back to Hi-Fi (phone mic)" else "Use the earbuds' mic (call quality)",
                    active = state.earbudMic,
                    small = true,
                    onClick = onEarbudMic,
                )
            }
            if (state.chatAvailable) {
                BadgedBox(
                    badge = {
                        if (unreadChat > 0) {
                            androidx.compose.material3.Badge(containerColor = Accent, contentColor = Color.Black) {
                                Text(if (unreadChat > 9) "9+" else "$unreadChat")
                            }
                        }
                    },
                ) {
                    ControlButton(
                        Icons.AutoMirrored.Filled.Chat,
                        if (unreadChat > 0) "Chat, $unreadChat unread" else "Chat",
                        active = false,
                        small = true,
                        onClick = onChat,
                    )
                }
            }
            if (state.canReplay) {
                ControlButton(
                    Icons.Filled.Replay,
                    if (state.replaying) "Stop replay" else "Replay the last 8 seconds",
                    active = state.replaying,
                    small = true,
                    onClick = onReplay,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlButton(
                icon = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (state.micMuted) "Unmute" else "Mute",
                active = state.micMuted,
                onClick = onMic,
            )
            if (state.hasCamera) {
                ControlButton(
                    icon = if (state.cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    label = if (state.cameraOff) "Camera on" else "Camera off",
                    active = state.cameraOff,
                    onClick = onCamera,
                )
            }
            FilledIconButton(
                onClick = onHangUp,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Danger, contentColor = Color.White),
                modifier = Modifier.size(60.dp),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "Hang up")
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, active: Boolean, small: Boolean = false, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) Color.White else Color.White.copy(alpha = 0.16f),
            contentColor = if (active) Color.Black else Color.White,
        ),
        modifier = Modifier.size(if (small) 44.dp else 52.dp),
    ) {
        Icon(icon, contentDescription = label)
    }
}
