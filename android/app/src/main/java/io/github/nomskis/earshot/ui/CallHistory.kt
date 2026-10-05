package io.github.nomskis.earshot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.ui.theme.Tones
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The phone app's recent calls, a group per day: who, which way, when and how it went,
 * missed calls in red. Tapping one calls them back the same way, voice or video.
 */
@Composable
fun CallHistory(
    calls: List<CallRecord>,
    onCallBack: (CallRecord) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    if (calls.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(contentPadding).padding(vertical = 48.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                "No calls yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // Newest first, as the history is kept.
    val days = remember(calls, zone) { calls.groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() } }
    val color = Tones.row
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        days.forEach { (day, dayCalls) ->
            item(key = "day-$day") { SectionHeader(dayLabel(day, today)) }
            itemsIndexed(dayCalls, key = { i, _ -> "call-$day-$i" }) { i, call ->
                CallRow(call, onCallBack, Modifier.groupRow(i, dayCalls.size, color))
            }
        }
    }
}

@Composable
private fun CallRow(call: CallRecord, onCallBack: (CallRecord) -> Unit, modifier: Modifier = Modifier) {
    val canCall = call.address != null
    val missed = if (call.missed) MaterialTheme.colorScheme.error else null
    val time = Instant.ofEpochMilli(call.atMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    ListItem(
        modifier = modifier.then(
            if (canCall) Modifier.clickable(onClickLabel = "Call ${call.name} back") { onCallBack(call) } else Modifier,
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Avatar(call.name, seed = call.address ?: call.name) },
        headlineContent = {
            Text(
                call.name,
                color = missed ?: MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    when {
                        call.missed -> Icons.AutoMirrored.Filled.CallMissed
                        call.direction == CallRecord.Direction.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                        else -> Icons.AutoMirrored.Filled.CallMade
                    },
                    contentDescription = recentDirection(call),
                    tint = missed ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text("$time · ${call.summary}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = if (canCall) {
            {
                IconButton(onClick = { onCallBack(call) }) {
                    Icon(
                        if (call.video) Icons.Filled.Videocam else Icons.Filled.Call,
                        contentDescription = if (call.video) "Video call ${call.name}" else "Voice call ${call.name}",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            null
        },
    )
}

internal fun recentDirection(call: CallRecord): String = when {
    call.missed -> "Missed call"
    call.direction == CallRecord.Direction.INCOMING -> "Incoming call"
    else -> "Outgoing call"
}

/** What the home screen says for a moment after a call, and whom "Call again" rings. */
data class CallEndNote(val text: String, val callAgain: Contact? = null, val video: Boolean = false)

/**
 * Right after a call: "Call ended · 12:34" if you talked, or why they didn't pick up ("No answer
 * from Sam", "Sam declined") with Call again, like the phone app. Nothing for other endings (the
 * history says what happened), or for a record that isn't from just now.
 */
internal fun callEndNote(call: CallRecord, now: Long = System.currentTimeMillis()): CallEndNote? {
    val endedAt = call.atMillis + call.durationSeconds * 1000
    if (now - endedAt > ENDED_NOTE_FRESH_MS) return null
    if (call.outcome == CallRecord.Outcome.ANSWERED) {
        return if (call.durationSeconds > 0) CallEndNote("Call ended · ${callTimer(call.durationSeconds * 1000)}") else null
    }
    val address = call.address ?: return null
    if (call.direction != CallRecord.Direction.OUTGOING) return null
    val text = when (call.outcome) {
        CallRecord.Outcome.NO_ANSWER -> "No answer from ${call.name}"
        CallRecord.Outcome.DECLINED -> "${call.name} declined"
        CallRecord.Outcome.BUSY -> "${call.name} is on another call"
        else -> return null
    }
    return CallEndNote(text, Contact(call.name, address), call.video)
}

/** Ringing and connecting come before the talking, so the end can be a while after [CallRecord.atMillis] plus the talk. */
private const val ENDED_NOTE_FRESH_MS = 3 * 60_000L
