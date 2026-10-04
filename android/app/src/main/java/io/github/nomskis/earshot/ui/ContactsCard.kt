package io.github.nomskis.earshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.nomskis.earshot.messages.Conversation
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
    /** Conversations by address, for the latest message and what's unread. */
    conversations: Map<String, Conversation> = emptyMap(),
    /** Tapping a name opens the conversation with them. */
    onOpen: ((Contact) -> Unit)? = null,
    /** Your own name for them; theirs no longer replaces it. */
    onRename: ((Contact, String) -> Unit)? = null,
    /** Their calls stop ringing and their messages stop arriving (after you confirm). */
    onBlock: ((Contact) -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Contacts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (contacts.isEmpty()) {
                Text(
                    "Invite someone below. After your first call, they're saved here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Whoever you've talked to most recently first, by call or message.
            contacts.sortedByDescending { maxOf(it.lastCallAtMillis, conversations[it.address]?.last?.atMillis ?: 0) }.forEach { contact ->
                ContactRow(contact, now, onCall, onRemove, conversations[contact.address], onOpen, onRename, onBlock)
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
    conversation: Conversation?,
    onOpen: ((Contact) -> Unit)?,
    onRename: ((Contact, String) -> Unit)?,
    onBlock: ((Contact) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    if (renaming && onRename != null) {
        RenameDialog(contact, onDone = { name ->
            renaming = false
            if (name != null) onRename(contact, name)
        })
    }
    if (blocking && onBlock != null) {
        AlertDialog(
            onDismissRequest = { blocking = false },
            title = { Text("Block ${contact.name}?") },
            text = { Text("Their calls and messages won't reach you. They aren't told.") },
            confirmButton = {
                TextButton(onClick = {
                    blocking = false
                    onBlock(contact)
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { blocking = false }) { Text("Cancel") } },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Messages with ${contact.name}") { onOpen(contact) } else Modifier)
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
            val unread = conversation?.unread ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unread > 0) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unread > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(if (unread > 99) "99+" else "$unread") }
                }
            }
            val last = conversation?.last
            val subtitle = when {
                last != null && last.atMillis >= contact.lastCallAtMillis -> (if (last.mine) "You: " else "") + last.text
                contact.lastCallAtMillis > 0 -> "Last call ${lastCallText(contact.lastCallAtMillis, now)}"
                else -> null
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                if (onRename != null) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menu = false
                            renaming = true
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = {
                        menu = false
                        onRemove(contact)
                    },
                )
                if (onBlock != null) {
                    DropdownMenuItem(
                        text = { Text("Block") },
                        onClick = {
                            menu = false
                            blocking = true
                        },
                    )
                }
            }
        }
    }
}

/** Your own name for a contact. Null when cancelled. */
@Composable
private fun RenameDialog(contact: Contact, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(contact.name) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
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
