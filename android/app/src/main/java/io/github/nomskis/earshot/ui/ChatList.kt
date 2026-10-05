package io.github.nomskis.earshot.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.messages.Conversation
import io.github.nomskis.earshot.ui.theme.Tones
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * The people you talk to, whoever you talked to last first: their latest message or call,
 * what's unread, and a voice and a video call button each. Tapping one opens your messages;
 * a long press offers Rename, Remove and Block, which their conversation's menu has too.
 */
@Composable
fun ChatList(
    contacts: List<Contact>,
    onCall: (Contact, withVideo: Boolean) -> Unit,
    onRemove: (Contact) -> Unit,
    now: Long = System.currentTimeMillis(),
    /** Conversations by address, for the latest message and what's unread. */
    conversations: Map<String, Conversation> = emptyMap(),
    onOpen: ((Contact) -> Unit)? = null,
    /** Your own name for them; theirs no longer replaces it. */
    onRename: ((Contact, String) -> Unit)? = null,
    /** Their calls stop ringing and their messages stop arriving (after you confirm). */
    onBlock: ((Contact) -> Unit)? = null,
) {
    if (contacts.isEmpty()) {
        NoChatsYet()
        return
    }
    val sorted = contacts.sortedByDescending { maxOf(it.lastCallAtMillis, conversations[it.address]?.last?.atMillis ?: 0) }
    val color = Tones.row
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        sorted.forEachIndexed { i, contact ->
            ChatRow(
                contact = contact,
                now = now,
                conversation = conversations[contact.address],
                onCall = onCall,
                onRemove = onRemove,
                onOpen = onOpen,
                onRename = onRename,
                onBlock = onBlock,
                modifier = Modifier.groupRow(i, sorted.size, color),
            )
        }
    }
}

@Composable
private fun NoChatsYet() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No one here yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Invite someone. After your first call, they're saved here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatRow(
    contact: Contact,
    now: Long,
    conversation: Conversation?,
    onCall: (Contact, withVideo: Boolean) -> Unit,
    onRemove: (Contact) -> Unit,
    onOpen: ((Contact) -> Unit)?,
    onRename: ((Contact, String) -> Unit)?,
    onBlock: ((Contact) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    if (renaming && onRename != null) {
        RenameDialog(contact, onDone = { name ->
            renaming = false
            if (name != null) onRename(contact, name)
        })
    }
    if (blocking && onBlock != null) {
        BlockDialog(contact, onBlock = { onBlock(contact) }, onDismiss = { blocking = false })
    }
    if (removing) {
        RemoveDialog(contact, onRemove = { onRemove(contact) }, onDismiss = { removing = false })
    }
    val unread = conversation?.unread ?: 0
    Box(modifier) {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClickLabel = "Messages with ${contact.name}",
                onLongClickLabel = "More for ${contact.name}",
                onLongClick = { menu = true },
                onClick = { onOpen?.invoke(contact) },
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Avatar(contact.name, seed = contact.address, size = 48.dp) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        contact.name,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") }
                }
            },
            supportingContent = chatSubtitle(contact, conversation, now)?.let { subtitle ->
                {
                    Text(
                        subtitle,
                        color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(onClick = { onCall(contact, false) }) {
                        Icon(Icons.Filled.Call, contentDescription = "Voice call ${contact.name}")
                    }
                    FilledTonalIconButton(onClick = { onCall(contact, true) }) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Video call ${contact.name}")
                    }
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onRename != null) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                    menu = false
                    renaming = true
                })
            }
            DropdownMenuItem(text = { Text("Remove") }, onClick = {
                menu = false
                removing = true
            })
            if (onBlock != null) {
                DropdownMenuItem(text = { Text("Block") }, onClick = {
                    menu = false
                    blocking = true
                })
            }
        }
    }
}

/** Their latest message, or when you last called. */
private fun chatSubtitle(contact: Contact, conversation: Conversation?, now: Long): String? {
    val last = conversation?.last
    return when {
        last != null && last.atMillis >= contact.lastCallAtMillis -> when {
            last.deleted -> if (last.mine) "You deleted this message" else "This message was deleted"
            else -> (if (last.mine) "You: " else "") + last.text
        }
        contact.lastCallAtMillis > 0 -> "Last call ${lastCallText(contact.lastCallAtMillis, now)}"
        else -> null
    }
}

/** Your own name for a contact. Null when cancelled. */
@Composable
internal fun RenameDialog(contact: Contact, onDone: (String?) -> Unit) {
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

/** "Block Sam?", since it's quiet: they aren't told, so you'd not notice a mistake. */
@Composable
internal fun BlockDialog(contact: Contact, onBlock: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Block ${contact.name}?",
        text = "Their calls and messages won't reach you. They aren't told.",
        confirm = "Block",
        onConfirm = onBlock,
        onDismiss = onDismiss,
    )
}

/** "Remove Sam?": off your list, though a call or message from them brings them back. */
@Composable
internal fun RemoveDialog(contact: Contact, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Remove ${contact.name}?",
        text = "They leave your list. A call or message from them brings them back.",
        confirm = "Remove",
        onConfirm = onRemove,
        onDismiss = onDismiss,
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
