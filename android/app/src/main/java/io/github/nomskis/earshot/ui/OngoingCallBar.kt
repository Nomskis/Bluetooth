package io.github.nomskis.earshot.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.ui.theme.AnswerGreen
import kotlinx.coroutines.delay

/** The call going on while you use the rest of the app: who, how long, and a tap back to it. */
@Composable
internal fun OngoingCallBar(state: CallState, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.connectedAt) {
        while (state.connectedAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val name = state.remotePeer?.name?.takeIf { it.isNotBlank() } ?: state.contactName ?: "Call"
    // The clock and battery sit on the green, so they turn white.
    DarkSystemBars(navigation = false)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AnswerGreen)
            .clickable(onClickLabel = "Return to call", onClick = onClick)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val video = !state.cameraOff || (state.hasRemoteVideo && !state.remoteMedia.cameraOff)
        Icon(if (video) Icons.Filled.Videocam else Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(
            "$name · ${callStatus(state, now)}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.micMuted) Icon(Icons.Filled.MicOff, contentDescription = "Muted", tint = Color.White, modifier = Modifier.size(18.dp))
        Text("Return", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}
