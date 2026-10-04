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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.EchoCancellation
import io.github.nomskis.earshot.settings.MicSource
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.settings.VideoQuality
import io.github.nomskis.earshot.signaling.ServerHealth
import io.github.nomskis.earshot.ui.theme.Accent
import io.github.nomskis.earshot.update.AppUpdater

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
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        saveText()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
            Toggle("Receive calls", settings.receiveCalls) { v -> onUpdate { it.copy(receiveCalls = v) } }

            HorizontalDivider()
            Section("Calls")
            AudioModePicker(settings.audioMode) { mode -> onUpdate { it.copy(audioMode = mode) } }
            Toggle("Noise suppression", settings.noiseSuppression) { v -> onUpdate { it.copy(noiseSuppression = v) } }
            Toggle("Automatic mic volume", settings.autoGainControl) { v -> onUpdate { it.copy(autoGainControl = v) } }
            Toggle("Route through relay", settings.relayRoute, "Can help calls abroad") { v -> onUpdate { it.copy(relayRoute = v) } }
            Toggle("Mobile data backup", settings.mobileDataBackup, "When Wi-Fi stalls. Uses data") { v ->
                onUpdate { it.copy(mobileDataBackup = v) }
            }
            Toggle("Keep screen on", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
            Toggle("Screen off in pocket", settings.pocketGuard) { v -> onUpdate { it.copy(pocketGuard = v) } }

            HorizontalDivider()
            Section("Video")
            Choice(
                label = "Quality",
                selected = settings.videoQuality,
                options = VideoQuality.entries,
                describe = { "${it.height}p, ${it.fps} fps" },
                onSelect = { value -> onUpdate { it.copy(videoQuality = value) } },
            )
            Toggle("Start with back camera", settings.startWithBackCamera) { v -> onUpdate { it.copy(startWithBackCamera = v) } }
            Toggle("Mirror my video", settings.flip) { v -> onUpdate { it.copy(flip = v) } }

            HorizontalDivider()
            Section("Quick replies")
            QuickRepliesEditor(settings.quickReplies) { replies -> onUpdate { it.copy(quickReplies = replies) } }

            if (blocked.isNotEmpty()) {
                HorizontalDivider()
                Section("Blocked")
                blocked.forEach { person ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(person.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { onUnblock(person) }) { Text("Unblock") }
                    }
                }
            }

            HorizontalDivider()
            Section("Last call")
            val quality = lastCall?.quality
            if (lastCall != null && quality != null) {
                Text("${lastCall.name}: ${quality.verdict.name.lowercase()}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    quality.report(lastCall.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Earshot call report", quality.report(lastCall.durationSeconds)))
                }) { Text("Copy report") }
            } else {
                Hint("No calls yet")
            }

            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { advanced = !advanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (advanced) {
                Section("Server")
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
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Accent)
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

                Section("Audio")
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

                Section("Earbuds")
                Toggle("Game audio label", settings.gameAudioLabel, "Lower Bluetooth delay where supported") { v -> onUpdate { it.copy(gameAudioLabel = v) } }
                Toggle("Low-latency playback", settings.lowLatencyPlayback) { v -> onUpdate { it.copy(lowLatencyPlayback = v) } }
                Toggle("Talking cue", settings.headStartCue, "Glow when they start talking") { v -> onUpdate { it.copy(headStartCue = v) } }
                Toggle("Dip music while they talk", settings.smartDuck) { v -> onUpdate { it.copy(smartDuck = v) } }
                Toggle("Earbud game mode in calls", settings.autoGameMode) { v -> onUpdate { it.copy(autoGameMode = v) } }
                Toggle("Turbo in calls", settings.turboDuringCalls, "Needs Shizuku") { v -> onUpdate { it.copy(turboDuringCalls = v) } }
                Toggle("Lip sync", settings.lipSync, "Delay video to match the earbuds") { v -> onUpdate { it.copy(lipSync = v) } }
                Toggle("Lighter video on 2.4 GHz Wi-Fi", settings.bluetoothFriendlyVideo) { v -> onUpdate { it.copy(bluetoothFriendlyVideo = v) } }
                Toggle("Mobile data instead of 2.4 GHz Wi-Fi", settings.mobileDataOn24GHz) { v -> onUpdate { it.copy(mobileDataOn24GHz = v) } }
                OutlinedButton(onClick = onOpenTuner) { Text("Delay tuner") }
            }

            HorizontalDivider()
            Section("About")
            Text("Earshot ${BuildConfig.VERSION_NAME}, build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodyMedium)
            if (update != null) UpdateRow(update, onCheckForUpdate, onInstallUpdate)
        }
    }
}

/** "Check for updates", and how that went. */
@Composable
private fun UpdateRow(update: AppUpdater.State, onCheck: () -> Unit, onInstall: () -> Unit) {
    when (update) {
        is AppUpdater.State.Available -> {
            Text("Build ${update.release.build} available", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onInstall) { Text("Update") }
        }
        is AppUpdater.State.Downloading -> {
            Text("Downloading… ${update.percent}%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { update.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is AppUpdater.State.NeedsPermission -> {
            Hint("Allow Earshot to install apps, then come back.")
            Button(onClick = onInstall) { Text("Allow") }
        }
        AppUpdater.State.Installing -> Hint("Installing…")
        AppUpdater.State.Checking -> Hint("Checking…")
        else -> {
            when (update) {
                is AppUpdater.State.UpToDate -> Hint("Up to date")
                AppUpdater.State.CheckFailed -> Hint("Couldn't check. Try again later.")
                is AppUpdater.State.Failed -> Hint(update.reason)
                else -> Unit
            }
            OutlinedButton(onClick = onCheck) { Text("Check for updates") }
        }
    }
}

/** The quick replies, each editable or removable, and room for more. */
@Composable
private fun QuickRepliesEditor(replies: List<String>, onChange: (List<String>) -> Unit) {
    // Which one is being edited: its index, or replies.size for a new one.
    var editing by remember { mutableStateOf<Int?>(null) }
    replies.forEachIndexed { i, reply ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                reply,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Edit") { editing = i }
                    .padding(vertical = 6.dp),
            )
            IconButton(onClick = { onChange(replies.filterIndexed { j, _ -> j != i }) }) {
                Icon(Icons.Filled.Close, contentDescription = "Remove $reply")
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (replies.size < QuickReplies.MAX) {
            OutlinedButton(onClick = { editing = replies.size }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
        if (replies != QuickReplies.DEFAULT) TextButton(onClick = { onChange(QuickReplies.DEFAULT) }) { Text("Reset") }
    }
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

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A switch with its name, and a few words under it when the name alone doesn't say enough. */
@Composable
private fun Toggle(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(describe(selected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
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
