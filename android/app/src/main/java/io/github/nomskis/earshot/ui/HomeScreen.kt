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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.nomskis.earshot.R
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.CodecInfo
import io.github.nomskis.earshot.call.RoomCodes
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.settings.DelayRuns
import io.github.nomskis.earshot.signaling.ServerUrls

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
    delayRuns: List<DelayRun>,
    codec: CodecInfo?,
    onJoin: (room: String, withVideo: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTuner: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var room by rememberSaveable { mutableStateOf(settings.lastRoom) }
    var withVideo by rememberSaveable { mutableStateOf(true) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingRoom) {
        if (pendingRoom != null) {
            room = pendingRoom
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] ?: context.hasPermission(Manifest.permission.RECORD_AUDIO)
        val cameraGranted = result[Manifest.permission.CAMERA] ?: context.hasPermission(Manifest.permission.CAMERA)
        if (!micGranted) {
            permissionError = "Earshot needs the microphone for calls."
        } else if (normalizedRoom != null) {
            onJoin(normalizedRoom, withVideo && cameraGranted)
        }
    }

    fun join() {
        val code = normalizedRoom ?: return
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (withVideo) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Earbud game mode talks to the earbuds directly.
            if (settings.autoGameMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filterNot { context.hasPermission(it) }
        if (needed.isEmpty()) onJoin(code, withVideo) else permissionLauncher.launch(needed.toTypedArray())
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
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (serverBase == null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connect a server first", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Calls are set up through your own Earshot server. Add its address in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onOpenSettings) { Text("Open settings") }
                    }
                }
            }

            RouteCard(route, settings.audioMode, codec = codec)

            DelayCard(DelayRuns.latestFor(delayRuns, route.mediaOutput?.name), onOpenTuner)

            BackgroundCard(done = settings.backgroundGuideDone) { onUpdateSettings { it.copy(backgroundGuideDone = true) } }

            AudioModePicker(settings.audioMode) { mode -> onUpdateSettings { it.copy(audioMode = mode) } }

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
                trailingIcon = {
                    IconButton(onClick = { room = RoomCodes.generate() }) {
                        Icon(Icons.Filled.Casino, contentDescription = "New room code")
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { join() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Camera", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = withVideo, onCheckedChange = { withVideo = it })
            }

            Button(
                onClick = { join() },
                enabled = normalizedRoom != null && serverBase != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Join call", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedButton(
                onClick = {
                    if (serverBase != null && normalizedRoom != null) {
                        context.shareInvite(ServerUrls.inviteLink(serverBase, normalizedRoom))
                    }
                },
                enabled = normalizedRoom != null && serverBase != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Send invite link")
            }

            Text(
                "The other person can join from the link in any browser, or with this app using the same room code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shows the earbuds' measured delay and opens the tuner. */
@Composable
private fun DelayCard(latest: DelayRun?, onOpenTuner: () -> Unit) {
    Card(
        onClick = onOpenTuner,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Earbud delay", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    latest?.let { "${it.delayMs.toInt()} ms · ${it.label}" } ?: "Not measured yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Measure it by sound and find the fastest setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                AudioMode.HIFI ->
                    "Music stays in full quality while you hear the call in your earbuds. The phone's own mic picks up your voice."
                AudioMode.HEADSET ->
                    "Uses your earbuds' mic, like a normal call. Bluetooth drops to call quality, music included."
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
