package io.github.nomskis.earshot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.call.CallSession
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.call.DelayBreakdown
import io.github.nomskis.earshot.call.LipSync
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.ui.theme.Accent
import io.github.nomskis.earshot.ui.theme.Danger
import kotlin.math.roundToInt

@Composable
fun CallScreen(
    session: CallSession,
    route: AudioRoute,
    keepScreenOn: Boolean,
    inPictureInPicture: Boolean,
    earbudBoost: EarbudBoost.Status?,
    turboNote: String?,
    onVoiceVolumeSaved: (Float) -> Unit,
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

        if (state.hasCamera && !state.cameraOff) {
            VideoRenderer(
                sink = session.localPreview,
                eglContext = session.eglContext,
                mirror = state.frontCamera,
                overlay = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 120.dp)
                    .size(width = 108.dp, height = 156.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }

        Controls(
            state = state,
            onMic = { session.setMicMuted(!state.micMuted) },
            onCamera = { session.setCameraOff(!state.cameraOff) },
            onSwitchCamera = session::switchCamera,
            onVolume = session::setVoiceVolume,
            onVolumeDone = onVoiceVolumeSaved,
            onReplay = session::toggleReplay,
            onEarbudMic = { session.setEarbudMic(!state.earbudMic) },
            onHangUp = session::hangUp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun RemotePlaceholder(state: CallState, compact: Boolean) {
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
        val text = when (state.phase) {
            CallPhase.CONNECTING -> state.error ?: "Connecting to the server…"
            CallPhase.WAITING -> "Waiting for the other person to join\n${state.room}"
            CallPhase.NEGOTIATING -> "Connecting to ${peerName ?: "the other person"}…"
            CallPhase.RECONNECTING -> "Connection lost. Reconnecting…"
            CallPhase.CONNECTED -> if (state.remoteMedia.cameraOff) "${peerName ?: "They"} turned the camera off" else "Connected"
            CallPhase.ENDED -> "Call ended"
            CallPhase.FAILED -> state.error ?: "Call failed"
        }
        if (state.phase == CallPhase.CONNECTING || state.phase == CallPhase.NEGOTIATING || state.phase == CallPhase.RECONNECTING) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(if (compact) 20.dp else 36.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
        )
        if (!compact && state.phase == CallPhase.WAITING) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = { context.shareInvite(state.inviteLink) }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send invite link")
            }
        }
    }
}

@Composable
private fun TopBar(state: CallState, route: AudioRoute, boostNotes: List<String>, modifier: Modifier) {
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
                state.remotePeer?.name?.takeIf { it.isNotBlank() } ?: state.room,
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
            if (!state.signalingOnline && state.phase == CallPhase.CONNECTED) {
                Spacer(Modifier.width(8.dp))
                Badge("Server offline")
            }
        }
        RouteChip(route, state.audioMode)
        state.delay?.let { DelayChip(it) }
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
        (boostNotes + listOfNotNull(state.radioNote, lipSync)).forEach { note ->
            Text(note, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
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

/** "≈ 230 ms mouth to ear"; tap for where the time goes. */
@Composable
private fun DelayChip(delay: DelayBreakdown) {
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
            Text("Their phone ≈ ${delay.senderMs} ms (estimate)", color = dim, style = small)
            Text("Network ${delay.networkMs} ms", color = dim, style = small)
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
private fun Controls(
    state: CallState,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onVolume: (Float) -> Unit,
    onVolumeDone: (Float) -> Unit,
    onReplay: () -> Unit,
    onEarbudMic: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier,
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
