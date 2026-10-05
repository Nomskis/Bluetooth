package io.github.nomskis.earshot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.EchoCancellation
import io.github.nomskis.earshot.settings.MicSource
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.settings.VideoQuality
import io.github.nomskis.earshot.signaling.ServerHealth
import io.github.nomskis.earshot.ui.theme.Tones
import io.github.nomskis.earshot.update.AppUpdater

/**
 * Settings as Android's own app groups them: a few rounded groups under short headings, each
 * row a name and, where the name alone doesn't say enough, a few words. The fine-tuning most
 * people never need is folded under Advanced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    serverCheck: ServerCheck,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onCheckServer: (String) -> Unit,
    onOpenTuner: () -> Unit,
    onBack: () -> Unit,
    /** The latest call that connected, for its quality report. */
    lastCall: CallRecord? = null,
    /** People you blocked; the section only shows when there are some. */
    blocked: List<Contact> = emptyList(),
    onUnblock: (Contact) -> Unit = {},
    /** Newer builds: null when this build doesn't update itself. */
    update: AppUpdater.State? = null,
    onCheckForUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var displayName by rememberSaveable { mutableStateOf(settings.displayName) }
    // The server and earbud fine-tuning most people never need (open until a server is set).
    var advanced by rememberSaveable { mutableStateOf(settings.serverUrl.isBlank()) }

    fun saveText() = onUpdate { it.copy(serverUrl = serverUrl, displayName = displayName) }

    Scaffold(
        containerColor = Tones.page,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        saveText()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tones.page, scrolledContainerColor = Tones.page),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Group(
                null,
                listOf<@Composable () -> Unit>(
                    {
                        TextFieldRow {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = {
                                    displayName = it
                                    onUpdate { s -> s.copy(displayName = it) }
                                },
                                label = { Text("Your name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    { Toggle("Receive calls", settings.receiveCalls) { v -> onUpdate { it.copy(receiveCalls = v) } } },
                ),
            )

            Group(
                "Calls",
                listOf<@Composable () -> Unit>(
                    { TextFieldRow { AudioModePicker(settings.audioMode) { mode -> onUpdate { it.copy(audioMode = mode) } } } },
                    { Toggle("Noise suppression", settings.noiseSuppression) { v -> onUpdate { it.copy(noiseSuppression = v) } } },
                    { Toggle("Automatic mic volume", settings.autoGainControl) { v -> onUpdate { it.copy(autoGainControl = v) } } },
                    { Toggle("Use less data", settings.lessData, "Lighter video") { v -> onUpdate { it.copy(lessData = v) } } },
                    { Toggle("Route through relay", settings.relayRoute, "Can help calls abroad") { v -> onUpdate { it.copy(relayRoute = v) } } },
                    {
                        Toggle("Mobile data backup", settings.mobileDataBackup, "When Wi-Fi stalls. Uses data") { v ->
                            onUpdate { it.copy(mobileDataBackup = v) }
                        }
                    },
                    { Toggle("Keep screen on", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } } },
                    { Toggle("Screen off in pocket", settings.pocketGuard) { v -> onUpdate { it.copy(pocketGuard = v) } } },
                ),
            )

            Group(
                "Video",
                listOf<@Composable () -> Unit>(
                    {
                        Choice(
                            label = "Quality",
                            selected = settings.videoQuality,
                            options = VideoQuality.entries,
                            describe = { "${it.height}p, ${it.fps} fps" },
                            onSelect = { value -> onUpdate { it.copy(videoQuality = value) } },
                        )
                    },
                    { Toggle("Start with back camera", settings.startWithBackCamera) { v -> onUpdate { it.copy(startWithBackCamera = v) } } },
                    { Toggle("Mirror my video", settings.flip) { v -> onUpdate { it.copy(flip = v) } } },
                ),
            )

            QuickRepliesEditor(settings.quickReplies) { replies -> onUpdate { it.copy(quickReplies = replies) } }

            if (blocked.isNotEmpty()) {
                Group(
                    "Blocked",
                    blocked.map<Contact, @Composable () -> Unit> { person ->
                        {
                            SettingRow(
                                headline = person.name,
                                leading = { Avatar(person.name, seed = person.address) },
                                trailing = { TextButton(onClick = { onUnblock(person) }) { Text("Unblock") } },
                            )
                        }
                    },
                )
            }

            val quality = lastCall?.quality
            Group(
                "Last call",
                listOf<@Composable () -> Unit>(
                    {
                        if (lastCall != null && quality != null) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${lastCall.name}: ${quality.verdict.name.lowercase()}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    quality.report(lastCall.durationSeconds),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Earshot call report", quality.report(lastCall.durationSeconds)))
                                }) { Text("Copy report") }
                            }
                        } else {
                            SettingRow(headline = "No calls yet")
                        }
                    },
                ),
            )

            Spacer(Modifier.height(20.dp))
            Group(
                null,
                listOf<@Composable () -> Unit>(
                    {
                        SettingRow(
                            headline = "Advanced",
                            modifier = Modifier.clickable { advanced = !advanced },
                            trailing = { Icon(if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                        )
                    },
                ),
            )
            if (advanced) {
                Group(
                    "Server",
                    listOf<@Composable () -> Unit>(
                        {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = serverUrl,
                                    onValueChange = {
                                        serverUrl = it
                                        onUpdate { s -> s.copy(serverUrl = it) }
                                    },
                                    label = { Text("Server address") },
                                    placeholder = { Text("https://calls.example.com") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { onCheckServer(serverUrl) }) { Text("Test") }
                                    Spacer(Modifier.width(12.dp))
                                    when (serverCheck) {
                                        ServerCheck.Idle -> Unit
                                        ServerCheck.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
                                        is ServerCheck.Ok -> {
                                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Reachable", style = MaterialTheme.typography.bodySmall)
                                        }
                                        is ServerCheck.Failed -> {
                                            Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                            Spacer(Modifier.width(6.dp))
                                            Text(serverCheck.reason, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                (serverCheck as? ServerCheck.Ok)?.let { ok ->
                                    ServerHealth.notes(ok.roundTripMs, ok.relay).forEach { Hint(it) }
                                }
                            }
                        },
                    ),
                )

                Group(
                    "Audio",
                    listOf<@Composable () -> Unit>(
                        {
                            Choice(
                                label = "Hi-Fi microphone",
                                selected = settings.micSource,
                                options = MicSource.entries,
                                describe = {
                                    when (it) {
                                        MicSource.MIC -> "Phone mic"
                                        MicSource.CAMCORDER -> "Camera mic"
                                        MicSource.VOICE_RECOGNITION -> "Speech mic"
                                        MicSource.UNPROCESSED -> "Raw mic"
                                        MicSource.VOICE_COMMUNICATION -> "Call mic"
                                    }
                                },
                                onSelect = { value -> onUpdate { it.copy(micSource = value) } },
                            )
                        },
                        {
                            Choice(
                                label = "Echo cancellation",
                                selected = settings.echoCancellation,
                                options = EchoCancellation.entries,
                                describe = {
                                    when (it) {
                                        EchoCancellation.AUTO -> "Automatic"
                                        EchoCancellation.ON -> "Always on"
                                        EchoCancellation.OFF -> "Off"
                                    }
                                },
                                onSelect = { value -> onUpdate { it.copy(echoCancellation = value) } },
                            )
                        },
                    ),
                )

                Group(
                    "Earbuds",
                    listOf<@Composable () -> Unit>(
                        { Toggle("Game audio label", settings.gameAudioLabel, "Lower Bluetooth delay where supported") { v -> onUpdate { it.copy(gameAudioLabel = v) } } },
                        { Toggle("Low-latency playback", settings.lowLatencyPlayback) { v -> onUpdate { it.copy(lowLatencyPlayback = v) } } },
                        { Toggle("Talking cue", settings.headStartCue, "Glow when they start talking") { v -> onUpdate { it.copy(headStartCue = v) } } },
                        { Toggle("Dip music while they talk", settings.smartDuck) { v -> onUpdate { it.copy(smartDuck = v) } } },
                        { Toggle("Earbud game mode in calls", settings.autoGameMode) { v -> onUpdate { it.copy(autoGameMode = v) } } },
                        { Toggle("Turbo in calls", settings.turboDuringCalls, "Needs Shizuku") { v -> onUpdate { it.copy(turboDuringCalls = v) } } },
                        { Toggle("Lip sync", settings.lipSync, "Delay video to match the earbuds") { v -> onUpdate { it.copy(lipSync = v) } } },
                        { Toggle("Lighter video on 2.4 GHz Wi-Fi", settings.bluetoothFriendlyVideo) { v -> onUpdate { it.copy(bluetoothFriendlyVideo = v) } } },
                        { Toggle("Mobile data instead of 2.4 GHz Wi-Fi", settings.mobileDataOn24GHz) { v -> onUpdate { it.copy(mobileDataOn24GHz = v) } } },
                        {
                            SettingRow(
                                headline = "Delay tuner",
                                modifier = Modifier.clickable(onClick = onOpenTuner),
                                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            )
                        },
                    ),
                )
            }

            Group(
                "About",
                buildList<@Composable () -> Unit> {
                    add { SettingRow(headline = "Earshot ${BuildConfig.VERSION_NAME}, build ${BuildConfig.VERSION_CODE}") }
                    if (update != null) add { UpdateRow(update, onCheckForUpdate, onInstallUpdate) }
                },
            )
        }
    }
}

/** "Check for updates", and how that went. */
@Composable
private fun UpdateRow(update: AppUpdater.State, onCheck: () -> Unit, onInstall: () -> Unit) {
    val check: @Composable () -> Unit = { OutlinedButton(onClick = onCheck) { Text("Check for updates") } }
    when (update) {
        is AppUpdater.State.Available -> SettingRow(
            headline = "Build ${update.release.build} available",
            trailing = { Button(onClick = onInstall) { Text("Update") } },
        )
        is AppUpdater.State.Downloading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Downloading… ${update.percent}%", style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { update.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is AppUpdater.State.NeedsPermission -> SettingRow(
            headline = "Allow updates",
            supporting = "Allow Earshot to install apps, then come back.",
            trailing = { Button(onClick = onInstall) { Text("Allow") } },
        )
        AppUpdater.State.Installing -> SettingRow(headline = "Installing…")
        AppUpdater.State.Checking -> SettingRow(headline = "Checking…")
        is AppUpdater.State.UpToDate -> SettingRow(headline = "Up to date", trailing = check)
        AppUpdater.State.CheckFailed -> SettingRow(headline = "Couldn't check", supporting = "Try again later", trailing = check)
        is AppUpdater.State.Failed -> SettingRow(headline = "Update didn't finish", supporting = update.reason, trailing = check)
        AppUpdater.State.Idle -> SettingRow(headline = "Updates", trailing = check)
    }
}

/** The quick replies, each editable or removable, and room for more. */
@Composable
private fun QuickRepliesEditor(replies: List<String>, onChange: (List<String>) -> Unit) {
    // Which one is being edited: its index, or replies.size for a new one.
    var editing by remember { mutableStateOf<Int?>(null) }
    Group(
        "Quick replies",
        buildList<@Composable () -> Unit> {
            replies.forEachIndexed { i, reply ->
                add {
                    SettingRow(
                        headline = reply,
                        modifier = Modifier.clickable(onClickLabel = "Edit") { editing = i },
                        trailing = {
                            IconButton(onClick = { onChange(replies.filterIndexed { j, _ -> j != i }) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove $reply")
                            }
                        },
                    )
                }
            }
            if (replies.size < QuickReplies.MAX) {
                add {
                    SettingRow(
                        headline = "Add",
                        modifier = Modifier.clickable { editing = replies.size },
                        leading = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            if (replies != QuickReplies.DEFAULT) {
                add { SettingRow(headline = "Reset to the usual ones", modifier = Modifier.clickable { onChange(QuickReplies.DEFAULT) }) }
            }
        },
    )
    editing?.let { index ->
        QuickReplyDialog(
            initial = replies.getOrNull(index).orEmpty(),
            onDone = { text ->
                editing = null
                if (text != null) {
                    onChange(if (index < replies.size) replies.mapIndexed { j, r -> if (j == index) text else r } else replies + text)
                }
            },
        )
    }
}

/** One quick reply to write or change. Null when cancelled. */
@Composable
private fun QuickReplyDialog(initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (initial.isEmpty()) "New quick reply" else "Edit quick reply") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(QuickReplies.MAX_LENGTH) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioModePicker(mode: AudioMode, onChange: (AudioMode) -> Unit) {
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
        Hint(
            when (mode) {
                AudioMode.HIFI -> "Earbuds stay in music quality; the phone's mic hears you"
                AudioMode.HEADSET -> "Earbuds' mic, in call quality"
            },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A row in a group: its name, a few words under it if needed, and what's at its ends. */
@Composable
private fun SettingRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(headline, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

/** A row holding a text box or a picker, with the same insets as the rest. */
@Composable
private fun TextFieldRow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp)) { content() }
}

/** A switch with its name, and a few words under it when the name alone doesn't say enough. The whole row flips it. */
@Composable
private fun Toggle(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    SettingRow(
        headline = label,
        supporting = detail,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun <T> Choice(
    label: String,
    selected: T,
    options: List<T>,
    describe: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(describe(selected), color = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(describe(option)) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}
