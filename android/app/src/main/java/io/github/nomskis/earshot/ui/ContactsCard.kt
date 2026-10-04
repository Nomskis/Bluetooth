package io.github.nomskis.earshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.Contact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * The people you can ring directly, with a voice and a video call button
 * each. Empty until your first call with someone, so it says how they get here.
 */
@Composable
fun ContactsCard(
    contacts: List<Contact>,
    onCall: (Contact, withVideo: Boolean) -> Unit,
    onRemove: (Contact) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Call",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (contacts.isEmpty()) {
                Text(
                    "Tap Invite someone and send them the link. After your first call you're saved here on both " +
                        "phones, and either of you can ring the other like a normal call.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            contacts.forEach { contact ->
                ContactRow(contact, now, onCall, onRemove)
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    now: Long,
    onCall: (Contact, withVideo: Boolean) -> Unit,
    onRemove: (Contact) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contact.name.trim().take(1).uppercase().ifEmpty { "?" },
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (contact.lastCallAtMillis > 0) {
                Text(
                    "Last call ${lastCallText(contact.lastCallAtMillis, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalIconButton(onClick = { onCall(contact, false) }) {
            Icon(Icons.Filled.Call, contentDescription = "Voice call ${contact.name}")
        }
        FilledTonalIconButton(onClick = { onCall(contact, true) }) {
            Icon(Icons.Filled.Videocam, contentDescription = "Video call ${contact.name}")
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More for ${contact.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from Earshot") },
                    onClick = {
                        menu = false
                        onRemove(contact)
                    },
                )
            }
        }
    }
}

/** "today", "yesterday", "3 days ago", then the date. */
internal fun lastCallText(atMillis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    return when {
        days <= 0 -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        else -> "on " + day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}
