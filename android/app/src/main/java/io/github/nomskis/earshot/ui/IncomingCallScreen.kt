package io.github.nomskis.earshot.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.IncomingRing
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.ui.theme.AnswerGreen
import io.github.nomskis.earshot.ui.theme.CallTheme
import io.github.nomskis.earshot.ui.theme.HangUpRed

/**
 * Someone is calling, like the phone app: who, large, and what kind of call; Decline and
 * Accept as two wide buttons with their words on them; replying with a message or answering
 * without video one tap away. Always dark, over the lock screen.
 */
@Composable
fun IncomingCallScreen(
    ring: IncomingRing,
    onAccept: () -> Unit,
    onAcceptVoiceOnly: () -> Unit,
    onDecline: () -> Unit,
    /** Declines with this message to them; null when we can't write to them (they didn't prove who they are). */
    onReply: ((String) -> Unit)? = null,
    quickReplies: List<String> = QuickReplies.DEFAULT,
) = CallTheme {
    DarkSystemBars()
    var replying by rememberSaveable { mutableStateOf(false) }
    // The only thing in Earshot that pulses: a phone that's really ringing.
    val pulse by rememberInfiniteTransition(label = "ringing").animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_100), RepeatMode.Reverse),
        label = "pulse",
    )
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.5f))
        Text(
            if (ring.video) "Earshot video call" else "Earshot voice call",
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(24.dp))
        Avatar(ring.callerName, seed = ring.callerAddress ?: ring.callerName, size = 112.dp, modifier = Modifier.scale(pulse))
        Spacer(Modifier.height(24.dp))
        Text(
            ring.callerName,
            color = colors.onSurface,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onReply != null) {
                AssistChip(
                    onClick = { replying = true },
                    label = { Text("Message") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null) },
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = colors.onSurface),
                )
            }
            if (ring.video) {
                AssistChip(
                    onClick = onAcceptVoiceOnly,
                    label = { Text("Voice only") },
                    leadingIcon = { Icon(Icons.Outlined.Call, contentDescription = null) },
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = colors.onSurface),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AnswerPill(Icons.Filled.CallEnd, "Decline", HangUpRed, onDecline, Modifier.weight(1f))
            AnswerPill(if (ring.video) Icons.Filled.Videocam else Icons.Filled.Call, "Accept", AnswerGreen, onAccept, Modifier.weight(1f))
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
