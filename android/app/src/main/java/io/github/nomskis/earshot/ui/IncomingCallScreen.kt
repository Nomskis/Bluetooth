package io.github.nomskis.earshot.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nomskis.earshot.calls.IncomingRing
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.ui.theme.Accent
import io.github.nomskis.earshot.ui.theme.Danger

/** Someone is calling: who, what kind of call, and Accept / Decline like a phone call. */
@Composable
fun IncomingCallScreen(
    ring: IncomingRing,
    onAccept: () -> Unit,
    onAcceptVoiceOnly: () -> Unit,
    onDecline: () -> Unit,
    /** Declines with this message to them; null when we can't write to them (they didn't prove who they are). */
    onReply: ((String) -> Unit)? = null,
    quickReplies: List<String> = QuickReplies.DEFAULT,
) {
    var replying by rememberSaveable { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "ringing").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B5E4B), Color.Black)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.3f))
        Text(
            if (ring.video) "Earshot video call" else "Earshot voice call",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            ring.callerName,
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(0.4f))
        Box(
            Modifier
                .size(132.dp)
                .scale(pulse)
                .background(Accent.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(108.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    ring.callerName.trim().take(1).uppercase().ifEmpty { "?" },
                    color = Color.Black,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnswerButton(Icons.Filled.CallEnd, "Decline", Danger, onDecline)
            AnswerButton(if (ring.video) Icons.Filled.Videocam else Icons.Filled.Call, "Accept", Accent, onAccept)
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onReply != null) {
                TextButton(onClick = { replying = true }) {
                    Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Message", color = Color.White)
                }
            }
            if (ring.video) {
                TextButton(onClick = onAcceptVoiceOnly) {
                    Text("Voice only", color = Color.White)
                }
            }
        }
    }
    if (replying && onReply != null) {
        ReplyWithMessage(quickReplies, onReply = onReply, onDismiss = { replying = false })
    }
}

/** The quick answers, or your own words. Picking one declines the call and sends it. */
@Composable
private fun ReplyWithMessage(quickReplies: List<String>, onReply: (String) -> Unit, onDismiss: () -> Unit) {
    var own by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reply with a message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quickReplies.forEach { reply ->
                    TextButton(onClick = { onReply(reply) }, modifier = Modifier.fillMaxWidth()) {
                        Text(reply, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = own,
                    onValueChange = { own = it },
                    placeholder = { Text("Write your own…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReply(own) }, enabled = own.isNotBlank()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AnswerButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(
            onClick = onClick,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
            modifier = Modifier.size(76.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(34.dp))
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
