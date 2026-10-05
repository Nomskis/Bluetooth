package io.github.nomskis.earshot.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.messages.Conversation
import io.github.nomskis.earshot.messages.TextMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A conversation with one contact, like a messaging app: their name with call buttons
 * at the top, the messages, and a box to type in. Messages to a phone that's offline
 * wait on the server and arrive when it's back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    contact: Contact,
    conversation: Conversation?,
    onSend: (String) -> Unit,
    onCall: (withVideo: Boolean) -> Unit,
    onBack: () -> Unit,
    /** Off this phone only. */
    onDelete: (TextMessage) -> Unit = {},
    onClear: () -> Unit = {},
    /** Calls with them, shown between the messages like a messaging app does. */
    calls: List<CallRecord> = emptyList(),
) {
    var menu by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear chat?") },
            text = { Text("Deletes the messages on this phone") },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    onClear()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
        )
    }
    BackHandler(onBack = onBack)
    var draft by rememberSaveable(contact.address) { mutableStateOf("") }
    val messages = conversation?.messages.orEmpty()
    val items = remember(messages, calls) { timeline(messages, calls) }
    val list = rememberLazyListState()
    // Newest at the bottom, and in view as they come.
    LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contact.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { onCall(false) }) { Icon(Icons.Filled.Call, contentDescription = "Voice call ${contact.name}") }
                    IconButton(onClick = { onCall(true) }) { Icon(Icons.Filled.Videocam, contentDescription = "Video call ${contact.name}") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                enabled = messages.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    clearing = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Say hi to ${contact.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    items.forEachIndexed { i, entry ->
                        val day = Instant.ofEpochMilli(entry.atMillis).atZone(zone).toLocalDate()
                        val previous = items.getOrNull(i - 1)?.let { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }
                        if (day != previous) {
                            item(key = "day-$day") { DayHeader(dayLabel(day, today)) }
                        }
                        when (entry) {
                            is ChatItem.Text -> item(key = (if (entry.message.mine) "me-" else "them-") + entry.message.id) {
                                MessageBubble(entry.message, onDelete = { onDelete(entry.message) })
                            }
                            is ChatItem.Call -> item(key = "call-${entry.call.atMillis}-$i") {
                                CallEvent(entry.call, onCall = { onCall(entry.call.video) })
                            }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Conversation.MAX_TEXT) },
                    placeholder = { Text("Message") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/** One line of a conversation: a message, or a call with them. */
internal sealed interface ChatItem {
    val atMillis: Long

    data class Text(val message: TextMessage) : ChatItem {
        override val atMillis: Long get() = message.atMillis
    }

    data class Call(val call: CallRecord) : ChatItem {
        override val atMillis: Long get() = call.atMillis
    }
}

/** Messages and calls, oldest first. */
internal fun timeline(messages: List<TextMessage>, calls: List<CallRecord>): List<ChatItem> =
    (messages.map { ChatItem.Text(it) } + calls.map { ChatItem.Call(it) }).sortedBy { it.atMillis }

/** "Missed voice call", "Video call · 12:34", "Voice call · No answer". */
internal fun callEventText(call: CallRecord): String {
    val kind = if (call.video) "Video call" else "Voice call"
    return if (call.missed) "Missed ${kind.lowercase()}" else "$kind · ${call.summary}"
}

/** A call in the conversation, centred like a day header; a tap calls back the same way. */
@Composable
private fun CallEvent(call: CallRecord, onCall: () -> Unit) {
    val time = Instant.ofEpochMilli(call.atMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val color = if (call.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = if (call.video) "Video call back" else "Call back", onClick = onCall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (call.video) Icons.Filled.Videocam else Icons.Filled.Call, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text("${callEventText(call)} · $time", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** "Today", "Yesterday", the weekday within a week, then the date. */
internal fun dayLabel(day: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

/** One message; a long press offers Copy and Delete. */
@Composable
private fun MessageBubble(message: TextMessage, onDelete: () -> Unit) {
    val mine = message.mine
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onLongClickLabel = "Message options", onLongClick = { menu = true }, onClick = {})
                    .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        menu = false
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", message.text))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
        Text(
            messageMeta(message),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "14:05", and for ours how it's doing: "14:05 · Delivered". */
internal fun messageMeta(message: TextMessage, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(message.atMillis).atZone(zone).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    if (!message.mine) return time
    val status = when (message.status) {
        TextMessage.Status.SENDING -> "Sending…"
        TextMessage.Status.WAITING -> "Waiting for their phone"
        TextMessage.Status.SENT -> "Sent"
        TextMessage.Status.DELIVERED -> "Delivered"
        TextMessage.Status.READ -> "Seen"
        TextMessage.Status.RECEIVED -> null
    }
    return listOfNotNull(time, status).joinToString(" · ")
}
