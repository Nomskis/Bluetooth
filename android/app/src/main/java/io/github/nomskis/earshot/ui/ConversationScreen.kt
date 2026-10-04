package io.github.nomskis.earshot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.messages.Conversation
import io.github.nomskis.earshot.messages.TextMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
) {
    BackHandler(onBack = onBack)
    var draft by rememberSaveable(contact.address) { mutableStateOf("") }
    val messages = conversation?.messages.orEmpty()
    val list = rememberLazyListState()
    // Newest at the bottom, and in view as they come.
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }

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
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Say hi to ${contact.name}. If their phone is offline, your message waits until it's back.",
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
                    items(messages, key = { (if (it.mine) "me-" else "them-") + it.id }) { message -> MessageBubble(message) }
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

@Composable
private fun MessageBubble(message: TextMessage) {
    val mine = message.mine
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
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
        TextMessage.Status.RECEIVED -> null
    }
    return listOfNotNull(time, status).joinToString(" · ")
}
