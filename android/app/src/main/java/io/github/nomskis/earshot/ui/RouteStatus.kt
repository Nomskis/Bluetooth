package io.github.nomskis.earshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.CodecInfo
import io.github.nomskis.earshot.audio.RouteQuality
import io.github.nomskis.earshot.audio.Tone
import io.github.nomskis.earshot.audio.describeRoute
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.ui.theme.Accent
import io.github.nomskis.earshot.ui.theme.Warning

private fun Tone.color(): Color = when (this) {
    Tone.GOOD -> Accent
    Tone.NEUTRAL -> Color(0xFF9AA3B2)
    Tone.WARNING -> Warning
}

private fun icon(route: AudioRoute, tone: Tone): ImageVector = when {
    tone == Tone.WARNING -> Icons.Filled.Warning
    route.quality == RouteQuality.PHONE_SPEAKER -> Icons.Filled.Speaker
    else -> Icons.Filled.Headphones
}

/** The "where is my audio going" card on the home screen. */
@Composable
fun RouteCard(route: AudioRoute, mode: AudioMode, modifier: Modifier = Modifier, codec: CodecInfo? = null) {
    val description = describeRoute(route, mode)
    val codecLine = codec?.takeIf {
        route.mediaOutput?.kind == io.github.nomskis.earshot.audio.DeviceKind.BLUETOOTH_MUSIC &&
            (it.device == null || it.device == route.mediaOutput.name)
    }?.summary
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon(route, description.tone), contentDescription = null, tint = description.tone.color())
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Audio output", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(description.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                codecLine?.let {
                    Text("Codec: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Compact version for the call screen. */
@Composable
fun RouteChip(route: AudioRoute, mode: AudioMode, modifier: Modifier = Modifier) {
    val description = describeRoute(route, mode, inCall = true)
    val label = when {
        description.tone == Tone.WARNING -> description.title
        mode == AudioMode.HIFI && route.quality == RouteQuality.BLUETOOTH_HIFI -> "Hi-Fi · ${description.title}"
        else -> description.title
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon(route, description.tone), contentDescription = null, tint = description.tone.color(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
