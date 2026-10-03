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
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
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
                    ServerCheck.Ok -> {
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
            Toggle("Start with the back camera", settings.startWithBackCamera) { v ->
                onUpdate { it.copy(startWithBackCamera = v) }
            }
            Toggle("Keep the screen on during calls", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }

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
