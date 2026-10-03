package io.github.nomskis.earshot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.audio.AdviceInput
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.CodecInfo
import io.github.nomskis.earshot.audio.EarbudApps
import io.github.nomskis.earshot.audio.SetupLabels
import io.github.nomskis.earshot.audio.SonarMeter
import io.github.nomskis.earshot.audio.Tip
import io.github.nomskis.earshot.audio.TunerAdvice
import io.github.nomskis.earshot.audio.WifiBand
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.settings.DelayRuns
import io.github.nomskis.earshot.ui.theme.Accent
import io.github.nomskis.earshot.ui.theme.Warning
import java.text.DateFormat
import java.util.Date

sealed interface SonarState {
    data object Idle : SonarState
    data class Running(val stage: SonarMeter.Stage) : SonarState
    data class Done(val outcome: SonarMeter.Outcome) : SonarState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(
    settings: AppSettings,
    route: AudioRoute,
    runs: List<DelayRun>,
    sonar: SonarState,
    codec: CodecInfo?,
    onCodecPermissionGranted: () -> Unit,
    wifiBand: WifiBand?,
    inCall: Boolean,
    onMeasure: (label: String) -> Unit,
    optimizer: MainViewModel.OptimizerState,
    onFindFastest: () -> Unit,
    onCopyReport: () -> String,
    onClearRuns: (device: String) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    earbuds: @Composable () -> Unit,
    turbo: @Composable () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val device = route.mediaOutput?.name
    var label by rememberSaveable { mutableStateOf(SetupLabels.NORMAL) }
    val installedApps = remember { EarbudApps.installed(context) }
    var findFastestAfterPermission by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (findFastestAfterPermission) onFindFastest() else onMeasure(label)
        }
        findFastestAfterPermission = false
    }
    val running = sonar is SonarState.Running
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onCodecPermissionGranted()
    }
    val needsBluetoothPermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
        !context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delay tuner") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Measures how long your earbuds take to play sound, by sound. Then try settings and keep the fastest.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RouteCard(route, settings.audioMode, codec = codec)
            if (needsBluetoothPermission) {
                OutlinedButton(onClick = { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                    Text("Show which Bluetooth codec is used")
                }
            } else if (codec == null) {
                Text(
                    "The codec shows up here the next time your earbuds connect or you change it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ResultCard(sonar)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How to measure", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. Turn the volume up to about 70%. Music is paused for you.\n" +
                            "2. Take one earbud out and hold its speaker against the phone's microphone (usually the small hole at the bottom).\n" +
                            "3. Pick what you're testing, then tap Measure. Keep still for about 7 seconds.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("What's set up right now:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SetupLabels.ALL.forEach { option ->
                            FilterChip(selected = label == option, onClick = { label = option }, label = { Text(option) })
                        }
                    }
                    Button(
                        onClick = {
                            if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                                onMeasure(label)
                            } else {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !running && !inCall,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (running) "Measuring…" else "Measure") }
                    OutlinedButton(
                        onClick = {
                            if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                                onFindFastest()
                            } else {
                                findFastestAfterPermission = true
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !running && !inCall && optimizer.busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Find my fastest setup (about a minute)") }
                    Text(
                        "Tries your setup as it is, your earbuds' game mode where Earshot can switch it, and every codec " +
                            "with Turbo. Keep the earbud against the mic until it's done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (optimizer.busy ?: optimizer.message)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (inCall) {
                        Text("End the call to measure; the chirps would play into it.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            History(runs.filter { it.device == device }, onClear = { device?.let(onClearRuns) })
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Earshot delay report", onCopyReport()))
                android.widget.Toast.makeText(context, "Report copied. Paste it into an issue to help other people with these earbuds.", android.widget.Toast.LENGTH_LONG).show()
            }) { Text("Copy report") }

            Text("What to try", style = MaterialTheme.typography.titleMedium)
            TunerAdvice.tips(AdviceInput(device, runs, wifiBand, settings.gameAudioLabel)).forEach { tip ->
                TipCard(tip, installedApps, context, onUpdateSettings)
            }

            earbuds()

            turbo()

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Call audio path", style = MaterialTheme.typography.titleSmall)
                    LabelledSwitch(
                        "Game audio label",
                        "Lets phones and earbuds that support it switch Bluetooth to low-latency mode.",
                        settings.gameAudioLabel,
                    ) { v -> onUpdateSettings { it.copy(gameAudioLabel = v) } }
                    LabelledSwitch(
                        "Low-latency playback",
                        "Android's fast audio path. Turn off if you hear crackling.",
                        settings.lowLatencyPlayback,
                    ) { v -> onUpdateSettings { it.copy(lowLatencyPlayback = v) } }
                    Text(
                        "The measurement uses the same path as calls, so measure with each to compare.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultCard(sonar: SonarState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (sonar) {
                SonarState.Idle -> Text("Not measured yet", style = MaterialTheme.typography.titleMedium)
                is SonarState.Running -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(
                        when (sonar.stage) {
                            SonarMeter.Stage.CALIBRATING -> "Calibrating with the phone speaker…"
                            SonarMeter.Stage.MEASURING -> "Listening to your earbud…"
                            SonarMeter.Stage.ANALYZING -> "Working it out…"
                        },
                    )
                }
                is SonarState.Done -> when (val outcome = sonar.outcome) {
                    is SonarMeter.Outcome.Success -> {
                        val s = outcome.summary
                        Text("${s.delayMs.toInt()} ms", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                        Text("from the app to your ear, ${outcome.deviceName}", style = MaterialTheme.typography.bodySmall)
                        s.reportedMs?.let { reported ->
                            val diff = s.delayMs - reported
                            val verdict = when {
                                diff > 15 -> "your earbuds under-report by ${diff.toInt()} ms"
                                diff < -15 -> "your earbuds over-report by ${(-diff).toInt()} ms"
                                else -> "close to the truth"
                            }
                            Text("Android estimated ${reported.toInt()} ms, $verdict.", style = MaterialTheme.typography.bodySmall)
                        }
                        outcome.warnings.forEach { warning ->
                            Text(
                                when (warning) {
                                    SonarMeter.Warning.LOW_VOLUME -> "Volume was low; turn it up for a more reliable result."
                                    SonarMeter.Warning.NOT_CALIBRATED -> "Couldn't calibrate the phone's mic, so this may be a few ms off."
                                    SonarMeter.Warning.INCONSISTENT -> "Readings varied a lot (${s.spreadMs.toInt()} ms). Try again somewhere quieter."
                                    SonarMeter.Warning.FEW_HITS -> "Only heard ${s.hits} of ${s.attempts} chirps; hold the earbud closer."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Warning,
                            )
                        }
                    }
                    SonarMeter.Outcome.NotHeard -> Text(
                        "Didn't hear the chirps. Hold the earbud's speaker right against the phone's microphone and turn the volume up.",
                        color = Warning,
                    )
                    SonarMeter.Outcome.NoEarbuds -> Text("Connect your Bluetooth earbuds first.", color = Warning)
                    is SonarMeter.Outcome.Failed -> Text("Measurement failed: ${outcome.reason}", color = Warning)
                }
            }
        }
    }
}

@Composable
private fun History(runs: List<DelayRun>, onClear: () -> Unit) {
    if (runs.isEmpty()) return
    val fastest = DelayRuns.fastestFor(runs, runs.first().device)
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your measurements", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
            runs.reversed().take(10).forEach { run ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(run.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            format.format(Date(run.atMillis)) + (run.codec?.let { " · $it" } ?: "") +
                                if (run.gameAudio) "" else " · media label",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (run === fastest) {
                        Text("Fastest", color = Accent, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text("${run.delayMs.toInt()} ms", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun TipCard(
    tip: Tip,
    installedApps: List<EarbudApps.App>,
    context: Context,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tip.title, style = MaterialTheme.typography.titleSmall)
            Text(tip.body, style = MaterialTheme.typography.bodySmall)
            when (tip.kind) {
                Tip.Kind.GAME_MODE -> {
                    installedApps.forEach { app ->
                        OutlinedButton(onClick = { EarbudApps.launch(context, app) }) { Text("Open ${app.name}") }
                    }
                    OutlinedButton(onClick = { context.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }) {
                        Text("Bluetooth settings")
                    }
                }
                Tip.Kind.CODEC -> {
                    if (developerOptionsEnabled(context)) {
                        OutlinedButton(onClick = { context.openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }) {
                            Text("Open Developer options")
                        }
                    } else {
                        Text(
                            "Developer options are hidden: in Settings › About phone, tap the build number " +
                                "(\"OS version\" on Xiaomi) seven times.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Tip.Kind.WIFI_BAND -> {
                    OutlinedButton(onClick = { context.openSettings(Settings.ACTION_WIFI_SETTINGS) }) {
                        Text("Wi-Fi settings")
                    }
                    OutlinedButton(onClick = { onUpdateSettings { it.copy(mobileDataOn24GHz = true) } }) {
                        Text("Use mobile data for calls on 2.4 GHz")
                    }
                }
                Tip.Kind.GAME_AUDIO_LABEL -> OutlinedButton(onClick = { onUpdateSettings { it.copy(gameAudioLabel = true) } }) {
                    Text("Turn it on")
                }
                else -> Unit
            }
        }
    }
}

@Composable
internal fun LabelledSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun developerOptionsEnabled(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

private fun Context.openSettings(action: String) {
    runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
