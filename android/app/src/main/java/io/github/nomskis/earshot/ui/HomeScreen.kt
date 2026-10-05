package io.github.nomskis.earshot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.call.RoomCodes
import io.github.nomskis.earshot.calls.CallBackRequest
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.calls.InboxClient
import io.github.nomskis.earshot.messages.Conversation
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.InterruptedCall
import io.github.nomskis.earshot.signaling.ServerUrls
import io.github.nomskis.earshot.ui.theme.Tones
import io.github.nomskis.earshot.update.AppUpdater

/** Home's two tabs, as in WhatsApp and Signal: the people you talk to, and the call history. */
private enum class HomeTab { CHATS, CALLS }

/**
 * Home: Chats (notices that need you, then the people you talk to) and Calls (the history),
 * with Settings at the top and one button for something new: Invite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    route: AudioRoute,
    error: String?,
    pendingRoom: String?,
    onConsumePendingRoom: () -> Unit,
    onDismissError: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    earbuds: EarbudInfo,
    onDetectEarbuds: () -> Unit,
    onJoin: (room: String, withVideo: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    interrupted: InterruptedCall? = null,
    onDismissInterrupted: () -> Unit = {},
    contacts: List<Contact> = emptyList(),
    onCallContact: (Contact, withVideo: Boolean) -> Unit = { _, _ -> },
    onRemoveContact: (Contact) -> Unit = {},
    callBack: CallBackRequest? = null,
    onConsumeCallBack: () -> Unit = {},
    /** Whether this phone can be rung; null hides it. */
    inboxStatus: InboxClient.State? = null,
    /** A newer build of the app, and how installing it is going. */
    update: AppUpdater.State = AppUpdater.State.Idle,
    onUpdate: () -> Unit = {},
    /** Conversations with contacts, by address. */
    conversations: Map<String, Conversation> = emptyMap(),
    onOpenConversation: ((Contact) -> Unit)? = null,
    /** The call history, newest first. */
    recentCalls: List<CallRecord> = emptyList(),
    onRenameContact: ((Contact, String) -> Unit)? = null,
    onBlockContact: ((Contact) -> Unit)? = null,
    /** "Call ended · 12:34", or why they didn't answer with Call again; shown once. */
    callEnded: CallEndNote? = null,
    onCallEndedShown: () -> Unit = {},
    /** A call is going on (in the bar at the top): no new one starts from here. */
    inCall: Boolean = false,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    var room by rememberSaveable { mutableStateOf(settings.lastRoom) }
    var inviting by rememberSaveable { mutableStateOf(false) }
    // The room an invite link opened the app with, waiting for one tap.
    var invited by rememberSaveable { mutableStateOf<String?>(null) }
    // Until a name is set, ask for it here (it stays while you type); Settings has it too.
    val askName by rememberSaveable { mutableStateOf(settings.displayName.isBlank()) }
    // What's typed lives here, not in the stored name: that one is trimmed, so
    // following it would swallow each space as it's typed.
    var name by rememberSaveable { mutableStateOf(settings.displayName) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    // A look at whether Earshot can switch these earbuds' game mode, once per pair.
    LaunchedEffect(route.mediaOutput?.name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) onDetectEarbuds()
    }
    LaunchedEffect(pendingRoom) {
        if (pendingRoom != null) {
            room = pendingRoom
            invited = pendingRoom
            tab = HomeTab.CHATS
            onConsumePendingRoom()
        }
    }
    // Taken over here at once, so it's shown once even if the screen changes while it's up.
    var endedNote by remember { mutableStateOf<CallEndNote?>(null) }
    LaunchedEffect(callEnded) {
        callEnded?.let {
            endedNote = it
            onCallEndedShown()
        }
    }
    LaunchedEffect(error, permissionError) {
        val message = error ?: permissionError ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onDismissError()
        permissionError = null
    }

    val serverBase = ServerUrls.normalizeBase(settings.serverUrl)
    val canStart = serverBase != null && !inCall

    // The call the permission prompt is for (a room, a rejoin or a contact), and whether with video.
    var requested by remember { mutableStateOf<Pair<Boolean, (Boolean) -> Unit>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] ?: context.hasPermission(Manifest.permission.RECORD_AUDIO)
        val cameraGranted = result[Manifest.permission.CAMERA] ?: context.hasPermission(Manifest.permission.CAMERA)
        val (video, start) = requested ?: return@rememberLauncherForActivityResult
        requested = null
        if (!micGranted) {
            permissionError = "Earshot needs the microphone for calls."
        } else {
            start(video && cameraGranted)
        }
    }

    /** Asks for what a call needs, then [start]s it (with video only if the camera was allowed). */
    fun withPermissions(withVideo: Boolean, start: (withVideo: Boolean) -> Unit) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (withVideo) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Earbud game mode talks to the earbuds directly.
            if (settings.autoGameMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filterNot { context.hasPermission(it) }
        if (needed.isEmpty()) {
            start(withVideo)
        } else {
            requested = withVideo to start
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun joinRoom(code: String, withVideo: Boolean) = withPermissions(withVideo) { video -> onJoin(code, video) }

    fun callContact(contact: Contact, withVideo: Boolean) = withPermissions(withVideo) { video -> onCallContact(contact, video) }

    /** One tap: a new room, the call started in it, and the link on its way to them. */
    fun invite(withVideo: Boolean) {
        val base = serverBase ?: return
        if (inCall) return
        val code = RoomCodes.generate()
        room = code
        withPermissions(withVideo) { video ->
            onJoin(code, video)
            context.shareInvite(ServerUrls.inviteLink(base, code))
        }
    }

    LaunchedEffect(endedNote) {
        val note = endedNote ?: return@LaunchedEffect
        val again = note.callAgain
        val result = snackbar.showSnackbar(note.text, actionLabel = if (again != null) "Call again" else null, withDismissAction = again != null)
        endedNote = null
        if (result == SnackbarResult.ActionPerformed && again != null) {
            // Their name as saved now, if they're a contact.
            callContact(contacts.firstOrNull { it.address == again.address } ?: again, note.video)
        }
    }

    // "Call back" on a missed call: ring them now, unless it's stale.
    LaunchedEffect(callBack) {
        val request = callBack ?: return@LaunchedEffect
        onConsumeCallBack()
        if (request.isFresh(System.currentTimeMillis()) && serverBase != null) callContact(request.contact, request.video)
    }

    val unread = contacts.sumOf { conversations[it.address]?.unread ?: 0 }

    Scaffold(
        containerColor = Tones.page,
        topBar = {
            TopAppBar(
                title = { Text(if (tab == HomeTab.CALLS) "Calls" else "Earshot") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tones.page, scrolledContainerColor = Tones.page),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.CHATS,
                    onClick = { tab = HomeTab.CHATS },
                    icon = {
                        BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                            Icon(if (tab == HomeTab.CHATS) Icons.AutoMirrored.Filled.Chat else Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
                        }
                    },
                    label = { Text("Chats") },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.CALLS,
                    onClick = { tab = HomeTab.CALLS },
                    icon = { Icon(if (tab == HomeTab.CALLS) Icons.Filled.Call else Icons.Outlined.Call, contentDescription = null) },
                    label = { Text("Calls") },
                )
            }
        },
        floatingActionButton = {
            if (canStart) {
                // Not the icon-and-text overload: that one hides its label from TalkBack.
                ExtendedFloatingActionButton(onClick = { inviting = true }) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Invite")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (tab) {
            HomeTab.CHATS -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val invitedRoom = invited?.let(RoomCodes::normalize)
                if (invitedRoom != null && canStart) {
                    Notice("You're invited to a call") {
                        NoticeActions {
                            Button(onClick = {
                                invited = null
                                joinRoom(invitedRoom, withVideo = true)
                            }) { Text("Join") }
                            OutlinedButton(onClick = {
                                invited = null
                                joinRoom(invitedRoom, withVideo = false)
                            }) { Text("Voice only") }
                            TextButton(onClick = { invited = null }) { Text("Not now") }
                        }
                    }
                }

                if (interrupted != null && canStart && interrupted.isRecent(System.currentTimeMillis())) {
                    Notice("Your call was cut off") {
                        NoticeActions {
                            Button(onClick = { joinRoom(interrupted.room, interrupted.withVideo) }) { Text("Rejoin") }
                            TextButton(onClick = onDismissInterrupted) { Text("Dismiss") }
                        }
                    }
                }

                if (serverBase == null) {
                    Notice("Connect a server first") {
                        NoticeActions { Button(onClick = onOpenSettings) { Text("Settings") } }
                    }
                }

                if (askName) {
                    Notice("What's your name?", detail = "Shown to the people you call") {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { typed ->
                                val next = typed.take(MAX_NAME_LENGTH)
                                name = next
                                onUpdateSettings { it.copy(displayName = next) }
                            },
                            label = { Text("Your name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Tones.row, unfocusedContainerColor = Tones.row),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                UpdateCard(update, onUpdate)

                if (serverBase != null && settings.receiveCalls && inboxStatus != null) {
                    CallsReadyCard(inboxStatus, settings.callSetupDone, onSetupDone = { onUpdateSettings { it.copy(callSetupDone = true) } })
                }

                BackgroundCard(done = settings.backgroundGuideDone) { onUpdateSettings { it.copy(backgroundGuideDone = true) } }

                if (earbuds.family != null && !settings.autoGameMode && !settings.gameModeHintDone) {
                    Notice("Your earbuds have a game mode", detail = "About half the delay in calls") {
                        NoticeActions {
                            Button(onClick = { onUpdateSettings { it.copy(autoGameMode = true, gameModeHintDone = true) } }) {
                                Text("Use it for calls")
                            }
                            TextButton(onClick = { onUpdateSettings { it.copy(gameModeHintDone = true) } }) { Text("Not now") }
                        }
                    }
                }

                if (serverBase != null) {
                    ChatList(
                        contacts,
                        onCall = ::callContact,
                        onRemove = onRemoveContact,
                        conversations = conversations,
                        onOpen = onOpenConversation,
                        onRename = onRenameContact,
                        onBlock = onBlockContact,
                    )
                }
            }
            HomeTab.CALLS -> CallHistory(
                calls = recentCalls,
                onCallBack = { call ->
                    val address = call.address ?: return@CallHistory
                    callContact(contacts.firstOrNull { it.address == address } ?: Contact(call.name, address), call.video)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = FAB_CLEARANCE),
            )
        }
    }

    if (inviting && canStart) {
        ModalBottomSheet(onDismissRequest = { inviting = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            InviteOptions(
                room = room,
                onRoomChange = { room = it },
                onInvite = { video ->
                    inviting = false
                    invite(video)
                },
                onJoin = { code, video ->
                    inviting = false
                    joinRoom(code, video)
                },
            )
        }
    }
}

/** Room under the floating Invite button, so it never covers a row's call buttons. */
private val FAB_CLEARANCE = 96.dp

/**
 * Something new: a video or voice call whose link goes straight to them, or joining
 * someone else's with its room code (folded away until asked for).
 */
@Composable
internal fun InviteOptions(
    room: String,
    onRoomChange: (String) -> Unit,
    onInvite: (withVideo: Boolean) -> Unit,
    onJoin: (room: String, withVideo: Boolean) -> Unit,
) {
    var showCode by rememberSaveable { mutableStateOf(false) }
    val normalizedRoom = RoomCodes.normalize(room)
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text("Invite someone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        ListItem(
            headlineContent = { Text("Video call") },
            supportingContent = { Text("Starts it and sends them the link") },
            leadingContent = { Icon(Icons.Filled.Videocam, contentDescription = null) },
            colors = clear,
            modifier = Modifier.clickable { onInvite(true) }.padding(horizontal = 8.dp),
        )
        ListItem(
            headlineContent = { Text("Voice call") },
            supportingContent = { Text("Either of you can add video later") },
            leadingContent = { Icon(Icons.Filled.Call, contentDescription = null) },
            colors = clear,
            modifier = Modifier.clickable { onInvite(false) }.padding(horizontal = 8.dp),
        )
        ListItem(
            headlineContent = { Text("Join with a room code") },
            leadingContent = { Icon(Icons.Filled.Dialpad, contentDescription = null) },
            colors = clear,
            modifier = Modifier.clickable { showCode = !showCode }.padding(horizontal = 8.dp),
        )
        if (showCode) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = room,
                    onValueChange = onRoomChange,
                    label = { Text("Room") },
                    placeholder = { Text("calm-otter-4821") },
                    singleLine = true,
                    isError = room.isNotBlank() && normalizedRoom == null,
                    supportingText = {
                        if (room.isNotBlank() && normalizedRoom == null) Text("3–64 letters, numbers or dashes")
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { normalizedRoom?.let { onJoin(it, true) } }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { normalizedRoom?.let { onJoin(it, true) } }, enabled = normalizedRoom != null) { Text("Join call") }
                    OutlinedButton(onClick = { normalizedRoom?.let { onJoin(it, false) } }, enabled = normalizedRoom != null) { Text("Voice only") }
                }
            }
        }
    }
}

/** A newer build: one tap downloads it and Android asks to update, keeping contacts and settings. */
@Composable
internal fun UpdateCard(update: AppUpdater.State, onUpdate: () -> Unit) {
    val (title, detail, button) = when (update) {
        is AppUpdater.State.Available -> Triple("Update available", "Build ${update.release.build}", "Update")
        is AppUpdater.State.Downloading -> Triple("Updating…", "${update.percent}%", null)
        is AppUpdater.State.NeedsPermission -> Triple("Allow updates", "Allow Earshot to install apps, then come back.", "Allow")
        AppUpdater.State.Installing -> Triple("Updating…", "Confirm on the next screen", null)
        is AppUpdater.State.Failed -> Triple("Update didn't finish", update.reason, null)
        AppUpdater.State.Idle, AppUpdater.State.Checking, is AppUpdater.State.UpToDate, AppUpdater.State.CheckFailed -> return
    }
    Notice(title, detail = detail) {
        if (update is AppUpdater.State.Downloading) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { update.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        button?.let { NoticeActions { Button(onClick = onUpdate) { Text(it) } } }
    }
}

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.shareInvite(link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Join my Earshot call: $link")
    startActivity(Intent.createChooser(send, "Send invite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Same limit as the server and the web client. */
private const val MAX_NAME_LENGTH = 64
