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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.signaling.ServerHealth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.earbuds.EarbudDrivers
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.EchoCancellation
import io.github.nomskis.earshot.settings.MicSource
import io.github.nomskis.earshot.settings.VideoQuality
import io.github.nomskis.earshot.ui.theme.Accent

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
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var displayName by rememberSaveable { mutableStateOf(settings.displayName) }

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
                OutlinedButton(onClick = { onCheckServer(serverUrl) }) { Text("Test connection") }
                Spacer(Modifier.width(12.dp))
                when (serverCheck) {
                    ServerCheck.Idle -> Unit
                    ServerCheck.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
                    is ServerCheck.Ok -> {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Accent)
                        Spacer(Modifier.width(6.dp))
                        Text("Server is reachable", style = MaterialTheme.typography.bodySmall)
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
            Hint("Run your own server (see docs/deploy.md). The invite links you send point at this address.")

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
            Toggle("Receive calls", settings.receiveCalls) { v ->
                onUpdate { it.copy(receiveCalls = v) }
            }
            Hint(
                "Your phone rings when someone you've had a call with calls you, even with Earshot closed. " +
                    "It keeps a small connection to your server open, with a quiet \"Ready for calls\" notification. " +
                    "Off, people can only reach you with a room code or invite link.",
            )

            HorizontalDivider()
            Section("Call audio")
            AudioModePicker(settings.audioMode) { mode -> onUpdate { it.copy(audioMode = mode) } }

            Choice(
                label = "Microphone (Hi-Fi mode)",
                selected = settings.micSource,
                options = MicSource.entries,
                describe = {
                    when (it) {
                        MicSource.MIC -> "Phone mic (recommended)"
                        MicSource.CAMCORDER -> "Camera mic"
                        MicSource.VOICE_RECOGNITION -> "Speech-tuned mic"
                        MicSource.UNPROCESSED -> "Raw mic"
                        MicSource.VOICE_COMMUNICATION -> "Call-tuned mic (experimental)"
                    }
                },
                onSelect = { value -> onUpdate { it.copy(micSource = value) } },
            )
            Hint("Every option uses the phone's own microphone. If your voice sounds off, try another; phones differ.")

            Choice(
                label = "Echo cancellation",
                selected = settings.echoCancellation,
                options = EchoCancellation.entries,
                describe = {
                    when (it) {
                        EchoCancellation.AUTO -> "Automatic (off with earbuds)"
                        EchoCancellation.ON -> "Always on"
                        EchoCancellation.OFF -> "Off"
                    }
                },
                onSelect = { value -> onUpdate { it.copy(echoCancellation = value) } },
            )
            Toggle("Game audio label (lower Bluetooth delay where supported)", settings.gameAudioLabel) { v ->
                onUpdate { it.copy(gameAudioLabel = v) }
            }
            Toggle("Low-latency playback", settings.lowLatencyPlayback) { v -> onUpdate { it.copy(lowLatencyPlayback = v) } }
            Toggle("Show when they start talking (head-start cue)", settings.headStartCue) { v ->
                onUpdate { it.copy(headStartCue = v) }
            }
            Toggle("Dip my music while they talk", settings.smartDuck) { v -> onUpdate { it.copy(smartDuck = v) } }
            Toggle("Turn on my earbuds' game mode during calls", settings.autoGameMode) { v ->
                onUpdate { it.copy(autoGameMode = v) }
            }
            Hint(
                "For earbuds whose game mode Earshot knows how to switch (" +
                    EarbudDrivers.familyNames.joinToString(", ") +
                    "). It goes back to how it was when the call ends. Needs the Nearby devices permission.",
            )
            Toggle("Use Turbo during calls (when Shizuku is set up)", settings.turboDuringCalls) { v ->
                onUpdate { it.copy(turboDuringCalls = v) }
            }
            Hint("Low-latency Bluetooth, the shortest buffer, and the codec the delay tuner measured fastest, for each call. Your music codec comes back afterwards.")
            OutlinedButton(onClick = onOpenTuner) { Text("Open delay tuner") }
            Toggle("Noise suppression", settings.noiseSuppression) { v -> onUpdate { it.copy(noiseSuppression = v) } }
            Toggle("Automatic mic volume", settings.autoGainControl) { v -> onUpdate { it.copy(autoGainControl = v) } }
            Hint("Changes apply to your next call.")

            HorizontalDivider()
            Section("Video")
            Choice(
                label = "Video quality",
                selected = settings.videoQuality,
                options = VideoQuality.entries,
                describe = { "${it.height}p, ${it.fps} fps" },
                onSelect = { value -> onUpdate { it.copy(videoQuality = value) } },
            )
            Toggle("Keep their lips in time with Bluetooth audio", settings.lipSync) { v -> onUpdate { it.copy(lipSync = v) } }
            Hint(
                "Earbuds on the music link play sound later than calls expect, so video runs ahead of the voice. " +
                    "This holds video back by the difference; measure your earbuds in the delay tuner for the best match.",
            )
            Toggle("Start with the back camera", settings.startWithBackCamera) { v ->
                onUpdate { it.copy(startWithBackCamera = v) }
            }
            Toggle("Flip", settings.flip) { v -> onUpdate { it.copy(flip = v) } }
            Hint("Mirrors your video left to right. You and the other person see the same picture. There's a Flip button during calls too.")
            Toggle("Keep the screen on during calls", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
            Toggle("Screen off in your pocket", settings.pocketGuard) { v -> onUpdate { it.copy(pocketGuard = v) } }
            Hint(
                "Uses the proximity sensor, like a phone call, so a pocket can't mute or hang up. The camera pauses too, " +
                    "so the other side sees \"phone in pocket\" instead of a black picture, and the Wi-Fi airtime goes back to your earbuds. " +
                    "Turn off if the screen goes dark while the phone is propped up.",
            )

            HorizontalDivider()
            Section("Sharing the radio with Bluetooth")
            Hint(
                "Phones run 2.4 GHz Wi-Fi and Bluetooth on the same radio, taking turns. A video call over " +
                    "2.4 GHz Wi-Fi takes turns away from your earbuds, which can make them stutter or lag.",
            )
            Toggle("Lighter video on 2.4 GHz Wi-Fi", settings.bluetoothFriendlyVideo) { v ->
                onUpdate { it.copy(bluetoothFriendlyVideo = v) }
            }
            Toggle("Use mobile data instead of 2.4 GHz Wi-Fi", settings.mobileDataOn24GHz) { v ->
                onUpdate { it.copy(mobileDataOn24GHz = v) }
            }
            Hint("Mobile data doesn't share the radio at all. Uses your data plan; falls back to Wi-Fi if mobile data fails.")
            Toggle("Mobile data as a backup during calls", settings.mobileDataBackup) { v ->
                onUpdate { it.copy(mobileDataBackup = v) }
            }
            Hint(
                "Off by default: while you're on Wi-Fi, calls don't use mobile data at all. On, mobile data stays ready next to Wi-Fi, " +
                    "and the call moves onto it when Wi-Fi stalls or keeps dropping packets, then back. That uses your data plan: " +
                    "a video call on mobile data takes roughly 0.5 to 1.5 GB an hour.",
            )

            HorizontalDivider()
            Section("Call reports")
            val quality = lastCall?.quality
            if (lastCall != null && quality != null) {
                Text(
                    "Last call with ${lastCall.name}: ${quality.verdict.name.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                Text("After a call, how the connection held up shows here.", style = MaterialTheme.typography.bodyMedium)
            }
            Hint("What the connection did during the call: the route, delay, what was lost and repaired, video freezes. Send it along if a call went badly.")

            HorizontalDivider()
            Section("About")
            Text("Earshot ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Hint("Free and open source under the Apache 2.0 license.")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
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
