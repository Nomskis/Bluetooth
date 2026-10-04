package io.github.nomskis.earshot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.CallRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The phone app's recent calls: who, which way, when, and how it went, missed calls in
 * red. Tapping one calls them back the same way (voice or video).
 */
@Composable
fun RecentCallsCard(
    calls: List<CallRecord>,
    onCallBack: (CallRecord) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    if (calls.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) calls.take(MAX_ALL) else calls.take(MAX_SHOWN)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            shown.forEach { call -> RecentRow(call, now, onCallBack) }
            if (calls.size > MAX_SHOWN) {
                TextButton(onClick = { all = !all }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (all) "Show fewer" else "Show more")
                }
            }
        }
    }
}

@Composable
private fun RecentRow(call: CallRecord, now: Long, onCallBack: (CallRecord) -> Unit) {
    val canCall = call.address != null
    val tint = if (call.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (canCall) Modifier.clickable(onClickLabel = "Call ${call.name} back") { onCallBack(call) } else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when {
                call.missed -> Icons.AutoMirrored.Filled.CallMissed
                call.direction == CallRecord.Direction.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                else -> Icons.AutoMirrored.Filled.CallMade
            },
            contentDescription = recentDirection(call),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                call.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (call.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${recentWhen(call.atMillis, now)} · ${call.summary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canCall) {
            IconButton(onClick = { onCallBack(call) }) {
                Icon(
                    if (call.video) Icons.Filled.Videocam else Icons.Filled.Call,
                    contentDescription = if (call.video) "Video call ${call.name}" else "Voice call ${call.name}",
                )
            }
        }
    }
}

internal fun recentDirection(call: CallRecord): String = when {
    call.missed -> "Missed call"
    call.direction == CallRecord.Direction.INCOMING -> "Incoming call"
    else -> "Outgoing call"
}

/** "14:05" today, "Yesterday", the weekday this week, then the date. */
internal fun recentWhen(atMillis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), today)
    return when {
        days <= 0 -> at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        days == 1L -> "Yesterday"
        days < 7 -> at.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        else -> at.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

private const val MAX_SHOWN = 5
private const val MAX_ALL = 50
