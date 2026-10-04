package io.github.nomskis.earshot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.InterruptedCall
import io.github.nomskis.earshot.signaling.ServerUrls
import io.github.nomskis.earshot.update.AppUpdater

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
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var room by rememberSaveable { mutableStateOf(settings.lastRoom) }
    // Joining by typing a code is the exception; it stays folded away until asked for.
    var showCode by rememberSaveable { mutableStateOf(false) }
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
            onConsumePendingRoom()
        }
    }
    LaunchedEffect(error, permissionError) {
        val message = error ?: permissionError ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onDismissError()
        permissionError = null
    }

    val serverBase = ServerUrls.normalizeBase(settings.serverUrl)
    val normalizedRoom = RoomCodes.normalize(room)

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
        val code = RoomCodes.generate()
        room = code
        withPermissions(withVideo) { video ->
            onJoin(code, video)
            context.shareInvite(ServerUrls.inviteLink(base, code))
        }
    }

    // "Call back" on a missed call: ring them now, unless it's stale.
    LaunchedEffect(callBack) {
        val request = callBack ?: return@LaunchedEffect
        onConsumeCallBack()
        if (request.isFresh(System.currentTimeMillis()) && serverBase != null) callContact(request.contact, request.video)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Earshot", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (serverBase == null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connect a server first", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = onOpenSettings) { Text("Settings") }
                    }
                }
            }

            UpdateCard(update, onUpdate)

            val invitedRoom = invited?.let(RoomCodes::normalize)
            if (invitedRoom != null && serverBase != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You're invited to a call", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }

            if (interrupted != null && serverBase != null && interrupted.isRecent(System.currentTimeMillis())) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your call was cut off", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { joinRoom(interrupted.room, interrupted.withVideo) }) { Text("Rejoin") }
                            TextButton(onClick = onDismissInterrupted) { Text("Dismiss") }
                        }
                    }
                }
            }

            if (askName) {
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (serverBase != null) {
                ContactsCard(
                    contacts,
                    onCall = ::callContact,
                    onRemove = onRemoveContact,
                    conversations = conversations,
                    onOpen = onOpenConversation,
                    onRename = onRenameContact,
                    onBlock = onBlockContact,
                )
            }

            if (serverBase != null) {
                RecentCallsCard(recentCalls, onCallBack = { call ->
                    val address = call.address ?: return@RecentCallsCard
                    callContact(contacts.firstOrNull { it.address == address } ?: Contact(call.name, address), call.video)
                })
            }

            // Like a contact's two buttons: a video call or a voice call, either can switch later.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Invite someone", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { invite(withVideo = true) },
                        enabled = serverBase != null,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Icon(Icons.Filled.Videocam, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Video call")
                    }
                    OutlinedButton(
                        onClick = { invite(withVideo = false) },
                        enabled = serverBase != null,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Voice call")
                    }
                }
            }

            TextButton(onClick = { showCode = !showCode }, enabled = serverBase != null) {
                Text(if (showCode) "Hide room code" else "Join with a room code")
            }
            if (showCode && serverBase != null) {
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Room") },
                    placeholder = { Text("calm-otter-4821") },
                    singleLine = true,
                    isError = room.isNotBlank() && normalizedRoom == null,
                    supportingText = {
                        if (room.isNotBlank() && normalizedRoom == null) Text("3–64 letters, numbers or dashes")
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { normalizedRoom?.let { joinRoom(it, withVideo = true) } }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { normalizedRoom?.let { joinRoom(it, withVideo = true) } }, enabled = normalizedRoom != null) {
                        Text("Join call")
                    }
                    OutlinedButton(onClick = { normalizedRoom?.let { joinRoom(it, withVideo = false) } }, enabled = normalizedRoom != null) {
                        Text("Voice only")
                    }
                }
            }

            if (serverBase != null && settings.receiveCalls && inboxStatus != null) {
                CallsReadyCard(inboxStatus, settings.callSetupDone, onSetupDone = { onUpdateSettings { it.copy(callSetupDone = true) } })
            }

            BackgroundCard(done = settings.backgroundGuideDone) { onUpdateSettings { it.copy(backgroundGuideDone = true) } }

            if (earbuds.family != null && !settings.autoGameMode && !settings.gameModeHintDone) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your earbuds have a game mode", style = MaterialTheme.typography.titleMedium)
                        Text("About half the delay in calls", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onUpdateSettings { it.copy(autoGameMode = true, gameModeHintDone = true) } }) {
                                Text("Use it for calls")
                            }
                            TextButton(onClick = { onUpdateSettings { it.copy(gameModeHintDone = true) } }) { Text("Not now") }
                        }
                    }
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (update is AppUpdater.State.Downloading) {
                LinearProgressIndicator(progress = { update.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            button?.let { Button(onClick = onUpdate) { Text(it) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioModePicker(mode: AudioMode, onChange: (AudioMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == AudioMode.HIFI,
                onClick = { onChange(AudioMode.HIFI) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Hi-Fi") }
            SegmentedButton(
                selected = mode == AudioMode.HEADSET,
                onClick = { onChange(AudioMode.HEADSET) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Headset mic") }
        }
        Text(
            when (mode) {
                AudioMode.HIFI -> "Earbuds stay in music quality; the phone's mic hears you"
                AudioMode.HEADSET -> "Earbuds' mic, in call quality"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
