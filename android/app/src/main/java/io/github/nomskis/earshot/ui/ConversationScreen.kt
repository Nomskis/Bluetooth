package io.github.nomskis.earshot.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.messages.Conversation
import io.github.nomskis.earshot.messages.Quote
import io.github.nomskis.earshot.messages.TextMessage
import io.github.nomskis.earshot.ui.theme.Tones
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A conversation with one contact, like a messaging app: who, with call buttons, at the top,
 * the messages and calls in between, and a box to type in. Messages to a phone that's
 * offline wait on the server and arrive when it's back. Hold a message (or swipe it right)
 * to answer it; answers quote what they answer, and a tap on the quote goes back to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    contact: Contact,
    conversation: Conversation?,
    /** [reply]: the message this one answers. */
    onSend: (text: String, reply: Quote?) -> Unit,
    onCall: (withVideo: Boolean) -> Unit,
    onBack: () -> Unit,
    /** Off this phone only. */
    onDelete: (TextMessage) -> Unit = {},
    /** One of ours, off their phone too. */
    onDeleteForEveryone: (TextMessage) -> Unit = {},
    onClear: () -> Unit = {},
    /** Calls with them, shown between the messages like a messaging app does. */
    calls: List<CallRecord> = emptyList(),
    onRename: ((String) -> Unit)? = null,
    onBlock: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    now: () -> Long = System::currentTimeMillis,
    /** A picture from the gallery or the camera, with its caption, to make ready and send. */
    onSendPhoto: (uri: Uri, caption: String, reply: Quote?) -> Unit = { _, _, _ -> },
    /** Where a picture in a message is kept; null where there are none (previews, tests). */
    photoFile: (String) -> File? = { null },
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TextMessage?>(null) }
    if (clearing) {
        ConfirmDialog(
            title = "Clear chat?",
            text = "Deletes the messages on this phone",
            confirm = "Clear",
            onConfirm = onClear,
            onDismiss = { clearing = false },
        )
    }
    if (renaming && onRename != null) {
        RenameDialog(contact, onDone = { name ->
            renaming = false
            if (name != null) onRename(name)
        })
    }
    if (blocking && onBlock != null) {
        BlockDialog(contact, onBlock = onBlock, onDismiss = { blocking = false })
    }
    if (removing && onRemove != null) {
        RemoveDialog(contact, onRemove = onRemove, onDismiss = { removing = false })
    }
    deleting?.let { message ->
        DeleteMessageDialog(
            forEveryone = conversation?.canUnsend(message, now()) == true,
            onForMe = { onDelete(message) },
            onForEveryone = { onDeleteForEveryone(message) },
            onDismiss = { deleting = null },
        )
    }
    BackHandler(onBack = onBack)
    var draft by rememberSaveable(contact.address) { mutableStateOf("") }
    var replyingTo by remember(contact.address) { mutableStateOf<Quote?>(null) }
    // Pictures: one picked and waiting to be sent, one open on its own.
    var picked by remember { mutableStateOf<Uri?>(null) }
    var viewing by remember { mutableStateOf<TextMessage?>(null) }
    var attaching by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) picked = uri }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> if (taken) picked = shot }
    fun takePicture() {
        val uri = runCatching { cameraUri(context) }.getOrNull() ?: return
        shot = uri
        runCatching { camera.launch(uri) }
    }
    // The camera app needs Earshot's camera permission, which video calls ask for anyway.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) takePicture() }
    picked?.let { uri ->
        PhotoPreview(
            uri,
            theirName = contact.name,
            onSend = { caption ->
                onSendPhoto(uri, caption, replyingTo)
                replyingTo = null
                picked = null
            },
            onDismiss = { picked = null },
        )
    }
    viewing?.let { message ->
        val file = message.photo?.let { photoFile(it.file) }
        if (file == null) viewing = null else PhotoViewer(file, caption = message.text, onDismiss = { viewing = null })
    }
    val messages = conversation?.messages.orEmpty()
    val rows = remember(messages, calls) { conversationRows(timeline(messages, calls)) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val typing = remember { FocusRequester() }
    /** A message a quote was tapped for: it lights up for a moment. */
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(HIGHLIGHT_MS)
            highlighted = null
        }
    }
    // Newest at the bottom, and in view as they come.
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1) }
    fun answer(message: TextMessage) {
        replyingTo = message.quoted()
        typing.requestFocusSafely()
    }

    Scaffold(
        containerColor = Tones.page,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(contact.name, seed = contact.address, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { onCall(false) }) { Icon(Icons.Filled.Call, contentDescription = "Voice call ${contact.name}") }
                    IconButton(onClick = { onCall(true) }) { Icon(Icons.Filled.Videocam, contentDescription = "Video call ${contact.name}") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (onRename != null) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                    menu = false
                                    renaming = true
                                })
                            }
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                enabled = messages.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    clearing = true
                                },
                            )
                            if (onBlock != null) {
                                DropdownMenuItem(text = { Text("Block") }, onClick = {
                                    menu = false
                                    blocking = true
                                })
                            }
                            if (onRemove != null) {
                                DropdownMenuItem(text = { Text("Remove") }, onClick = {
                                    menu = false
                                    removing = true
                                })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tones.page, scrolledContainerColor = Tones.page),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (rows.isEmpty()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Avatar(contact.name, seed = contact.address, size = 88.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Say hi to ${contact.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ConversationRow.Day -> DayHeader(row.label)
                            is ConversationRow.Entry -> when (val entry = row.item) {
                                is ChatItem.Text -> MessageBubble(
                                    entry.message,
                                    joinsPrevious = row.joinsPrevious,
                                    joinsNext = row.joinsNext,
                                    theirName = contact.name,
                                    highlighted = highlighted == row.key,
                                    photoFile = entry.message.photo?.let { photoFile(it.file) },
                                    onOpenPhoto = { viewing = entry.message },
                                    onReply = { answer(entry.message) },
                                    onDelete = { deleting = entry.message },
                                    onQuoteTap = { quote ->
                                        val target = rows.indexOfFirst { it.key == messageKey(quote.id, quote.mine) }
                                        if (target >= 0) {
                                            scope.launch { list.animateScrollToItem(target) }
                                            highlighted = rows[target].key
                                        }
                                    },
                                )
                                is ChatItem.Call -> CallEvent(entry.call, onCall = { onCall(entry.call.video) })
                            }
                        }
                    }
                }
            }
            replyingTo?.let { quote ->
                ReplyBar(quote, theirName = contact.name, onCancel = { replyingTo = null })
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    IconButton(onClick = { attaching = true }) { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Send a picture") }
                    DropdownMenu(expanded = attaching, onDismissRequest = { attaching = false }) {
                        DropdownMenuItem(
                            text = { Text("Gallery") },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                            onClick = {
                                attaching = false
                                runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Camera") },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            onClick = {
                                attaching = false
                                if (context.hasPermission(Manifest.permission.CAMERA)) takePicture() else cameraPermission.launch(Manifest.permission.CAMERA)
                            },
                        )
                    }
                }
                TextField(
                    value = draft,
                    onValueChange = { draft = it.take(Conversation.MAX_TEXT) },
                    placeholder = { Text("Message") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 5,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Tones.bubble,
                        unfocusedContainerColor = Tones.bubble,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(typing),
                )
                FilledIconButton(
                    onClick = {
                        onSend(draft, replyingTo)
                        draft = ""
                        replyingTo = null
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/** A focus request that doesn't throw before the field is laid out. */
private fun FocusRequester.requestFocusSafely() {
    runCatching { requestFocus() }
}

private const val HIGHLIGHT_MS = 1_200L

/** One row of the conversation's list: a day's header, or a message or call. */
internal sealed interface ConversationRow {
    val key: String

    data class Day(val label: String, override val key: String) : ConversationRow

    data class Entry(
        val item: ChatItem,
        override val key: String,
        /** Same person, close in time, as the row before / after: drawn as one run. */
        val joinsPrevious: Boolean,
        val joinsNext: Boolean,
    ) : ConversationRow
}

/** A message's row key; quotes find their message by it. */
internal fun messageKey(id: String, mine: Boolean): String = (if (mine) "me-" else "them-") + id

/** The timeline as rows: a header where the day changes, then each message or call. */
internal fun conversationRows(
    items: List<ChatItem>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<ConversationRow> {
    val rows = ArrayList<ConversationRow>(items.size + 4)
    var previousDay: LocalDate? = null
    items.forEachIndexed { i, entry ->
        val day = Instant.ofEpochMilli(entry.atMillis).atZone(zone).toLocalDate()
        if (day != previousDay) rows += ConversationRow.Day(dayLabel(day, today), "day-$day")
        previousDay = day
        val key = when (entry) {
            is ChatItem.Text -> messageKey(entry.message.id, entry.message.mine)
            is ChatItem.Call -> "call-${entry.call.atMillis}-$i"
        }
        rows += ConversationRow.Entry(
            entry,
            key,
            joinsPrevious = sameRun(items.getOrNull(i - 1), entry, zone),
            joinsNext = sameRun(entry, items.getOrNull(i + 1), zone),
        )
    }
    return rows
}

/**
 * "Delete message?": for everyone (ours, not long after it was sent) or from this phone only.
 * Laid out like WhatsApp's: the choices stacked, Cancel last.
 */
@Composable
internal fun DeleteMessageDialog(forEveryone: Boolean, onForMe: () -> Unit, onForEveryone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete message?") },
        text = if (forEveryone) null else { { Text("It's deleted from this phone.") } },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (forEveryone) {
                    TextButton(onClick = {
                        onDismiss()
                        onForEveryone()
                    }) { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = {
                    onDismiss()
                    onForMe()
                }) { Text("Delete for me", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** "Replying to Sam" over the box you type in, with what you're answering and a way out. */
@Composable
private fun ReplyBar(quote: Quote, theirName: String, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tones.bubble)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (quote.mine) "Replying to yourself" else "Replying to $theirName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                quoteText(quote),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Don't reply") }
    }
}

/** What a quote shows of the message it quotes. */
internal fun quoteText(quote: Quote): String = when {
    quote.photo && quote.text.isNotBlank() -> "Photo: ${quote.text}"
    quote.photo -> "Photo"
    else -> quote.text.ifBlank { "Message" }
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

/**
 * Two messages from the same person a few minutes apart read as one run: closer together,
 * with the time only under the last.
 */
internal fun sameRun(a: ChatItem?, b: ChatItem?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (a !is ChatItem.Text || b !is ChatItem.Text) return false
    if (a.message.mine != b.message.mine) return false
    if (b.atMillis - a.atMillis > RUN_GAP_MS) return false
    return Instant.ofEpochMilli(a.atMillis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b.atMillis).atZone(zone).toLocalDate()
}

private const val RUN_GAP_MS = 5 * 60_000L

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
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Tones.bubble)
                .clickable(onClickLabel = if (call.video) "Video call back" else "Call back", onClick = onCall)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (call.video) Icons.Filled.Videocam else Icons.Filled.Call, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text("${callEventText(call)} · $time", style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
    )
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

/**
 * One message. Runs from the same person sit close, their inner corners tighter, and only the
 * last says when (and, for ours, how it's doing). An answer shows what it answers on top; a
 * tap there goes back to it. A long press offers Reply, Copy and Delete; a swipe to the right
 * is Reply too. One deleted for everyone shows only that it was.
 */
@Composable
private fun MessageBubble(
    message: TextMessage,
    joinsPrevious: Boolean,
    joinsNext: Boolean,
    theirName: String,
    highlighted: Boolean,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onQuoteTap: (Quote) -> Unit,
    /** The picture's file, if it's a picture. */
    photoFile: File? = null,
    onOpenPhoto: () -> Unit = {},
) {
    val mine = message.mine
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var menu by remember { mutableStateOf(false) }
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val replyAt = with(density) { SWIPE_TO_REPLY.toPx() }
    val round = 20.dp
    val tight = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(topStart = round, topEnd = if (joinsPrevious) tight else round, bottomEnd = if (joinsNext) tight else round, bottomStart = round)
    } else {
        RoundedCornerShape(topStart = if (joinsPrevious) tight else round, topEnd = round, bottomEnd = round, bottomStart = if (joinsNext) tight else round)
    }
    val base = if (mine) MaterialTheme.colorScheme.primaryContainer else Tones.bubble
    val color by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else base, label = "highlight")
    val textColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = if (joinsPrevious) 0.dp else 6.dp)
            .then(
                if (message.deleted) {
                    Modifier
                } else {
                    Modifier.pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipe.value >= replyAt) onReply()
                                scope.launch { swipe.animateTo(0f) }
                            },
                            onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                            onHorizontalDrag = { change, dx ->
                                val before = swipe.value
                                val next = (before + dx).coerceIn(0f, replyAt * 1.3f)
                                if (before < replyAt && next >= replyAt) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                change.consume()
                                scope.launch { swipe.snapTo(next) }
                            },
                        )
                    }
                },
            ),
    ) {
        // Shows from behind as the message is pulled right.
        if (swipe.value > 0f) {
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = (swipe.value / replyAt).coerceIn(0f, 1f)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(22.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipe.value.roundToInt(), 0) },
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Box {
                val photo = message.photo
                Column(
                    Modifier
                        .widthIn(max = 300.dp)
                        .clip(shape)
                        .background(color)
                        .combinedClickable(onLongClickLabel = "Message options", onLongClick = { menu = true }, onClick = {})
                        .padding(if (photo != null) PaddingValues(4.dp) else PaddingValues(horizontal = 14.dp, vertical = 9.dp)),
                ) {
                    if (photo != null && !message.deleted) {
                        message.reply?.let { quote ->
                            Box(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 4.dp)) {
                                QuoteBlock(quote, theirName, mine, onClick = { onQuoteTap(quote) })
                            }
                        }
                        PhotoInBubble(
                            photo,
                            photoFile,
                            sending = mine && message.status == TextMessage.Status.SENDING,
                            onOpen = onOpenPhoto,
                        )
                        if (message.text.isNotBlank()) {
                            Text(
                                message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    } else if (message.deleted) {
                        Text(
                            if (mine) "You deleted this message" else "This message was deleted",
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = textColor.copy(alpha = 0.7f),
                        )
                    } else {
                        message.reply?.let { quote ->
                            QuoteBlock(quote, theirName, mine, onClick = { onQuoteTap(quote) })
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(message.text, style = MaterialTheme.typography.bodyLarge, color = textColor)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!message.deleted) {
                        DropdownMenuItem(
                            text = { Text("Reply") },
                            onClick = {
                                menu = false
                                onReply()
                            },
                        )
                        if (message.text.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = {
                                    menu = false
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", message.text))
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
            if (!joinsNext) {
                Text(
                    messageMeta(message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** How far to pull a message right to answer it. */
private val SWIPE_TO_REPLY = 64.dp

/** The message an answer answers, inside the answer's bubble: whose, and what it said. */
@Composable
private fun QuoteBlock(quote: Quote, theirName: String, inMine: Boolean, onClick: () -> Unit) {
    val accent = if (quote.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background((if (inMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.07f))
            .clickable(onClickLabel = "Show the message it answers", onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                if (quote.mine) "You" else theirName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                quoteText(quote),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
