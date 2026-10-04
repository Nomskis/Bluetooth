package io.github.nomskis.earshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.call.Chat
import io.github.nomskis.earshot.call.ChatMessage
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.ui.theme.Accent

/** The conversation, quick replies and a text box; replaces the call controls while open. */
@Composable
internal fun ChatPanel(
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    quickReplies: List<String> = QuickReplies.DEFAULT,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    val send = {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Chat",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close chat", tint = Color.White) }
        }
        if (messages.isNotEmpty()) {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { (if (it.mine) "me:" else "them:") + it.id }) { ChatRow(it) }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickReplies) { text ->
                SuggestionChip(
                    onClick = { onSend(text) },
                    label = { Text(text) },
                    colors = SuggestionChipDefaults.suggestionChipColors(labelColor = Color.White),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Chat.MAX_LENGTH) },
                placeholder = { Text("Message") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    cursorColor = Accent,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = send, enabled = draft.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (draft.isNotBlank()) Accent else Color.White.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun ChatRow(message: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .background(
                    if (message.mine) Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(message.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            statusLabel(message.status)?.let { (label, failed) ->
                Text(
                    label,
                    color = if (failed) Color(0xFFFF8A8D) else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun statusLabel(status: ChatMessage.Status): Pair<String, Boolean>? = when (status) {
    ChatMessage.Status.SENDING -> "Sending…" to false
    ChatMessage.Status.DELIVERED -> "Delivered" to false
    ChatMessage.Status.FAILED -> "Not sent" to true
    ChatMessage.Status.RECEIVED -> null
}

/** Their latest message, shown for a few seconds over the video while the chat is closed. */
@Composable
internal fun ChatBubble(name: String, message: ChatMessage, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .widthIn(max = 420.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$name: ${message.text}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
    }
}
