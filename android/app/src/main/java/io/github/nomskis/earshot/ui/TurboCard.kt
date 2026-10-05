package io.github.nomskis.earshot.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.nomskis.earshot.turbo.BluetoothOutputDiagnostics
import io.github.nomskis.earshot.turbo.TurboClient
import io.github.nomskis.earshot.ui.theme.Tones

/** The optional Shizuku-powered controls in the delay tuner. */
@Composable
fun TurboCard(
    status: TurboClient.Status,
    info: MainViewModel.TurboInfo,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onLoadDiagnostics: () -> Unit,
    onEnableLowLatency: () -> Unit,
    onShortestBuffer: () -> Unit,
    onSweepCodecs: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onRefresh() }
    LaunchedEffect(status) { if (status == TurboClient.Status.Ready && info.diagnostics == null) onLoadDiagnostics() }

    Card(colors = CardDefaults.cardColors(containerColor = Tones.row)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Turbo (advanced)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Codec, Bluetooth buffer and low-latency mode, through Shizuku",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (status) {
                TurboClient.Status.NotInstalled -> OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, TurboClient.SHIZUKU_SITE.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Get Shizuku") }
                TurboClient.Status.NotRunning -> {
                    Text("Shizuku is installed but not running. Open it and start it with Wireless debugging.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRefresh) { Text("Check again") }
                }
                TurboClient.Status.NeedsPermission -> OutlinedButton(onClick = onRequestPermission) { Text("Allow Earshot in Shizuku") }
                TurboClient.Status.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                }
                is TurboClient.Status.Failed -> {
                    Text(status.reason, color = Tones.warning, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRefresh) { Text("Try again") }
                }
                TurboClient.Status.Ready -> Ready(info, onLoadDiagnostics, onEnableLowLatency, onShortestBuffer, onSweepCodecs)
            }
        }
    }
}

@Composable
private fun Ready(
    info: MainViewModel.TurboInfo,
    onLoadDiagnostics: () -> Unit,
    onEnableLowLatency: () -> Unit,
    onShortestBuffer: () -> Unit,
    onSweepCodecs: () -> Unit,
) {
    info.diagnostics?.let { Diagnostics(it) }
    info.busy?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    info.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    val idle = info.busy == null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onSweepCodecs, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text("Test every codec and keep the fastest (~1 min)")
        }
        Text(
            "Hold the earbud against the mic for the whole test, like a normal measurement.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onEnableLowLatency, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text("Turn on Bluetooth low-latency mode")
        }
        OutlinedButton(onClick = onShortestBuffer, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text("Use the shortest Bluetooth buffer")
        }
        OutlinedButton(onClick = onLoadDiagnostics, enabled = idle) { Text("Refresh") }
    }
}

@Composable
private fun Diagnostics(d: BluetoothOutputDiagnostics) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("What the system says", style = MaterialTheme.typography.labelLarge)
        d.codec?.let { Line("Codec", it) }
        d.reportedDelayMs?.let { Line("Earbuds claim", "${it.toInt()} ms") }
        d.halBufferMs?.let { Line("Phone Bluetooth buffer period", "%.1f ms".format(it)) }
        d.hasFastMixer?.let { Line("Fast audio path to Bluetooth", if (it) "yes" else "no") }
        if (d.supportedLatencyModes.isNotEmpty()) Line("Latency modes", d.supportedLatencyModes.joinToString())
        d.latencyModesEnabled?.let { Line("Low-latency switching", if (it) "enabled" else "disabled") }
        val verdict = when (d.gameLabelCanLowerLatency) {
            true -> "The game-audio label can put this phone's Bluetooth into low-latency mode."
            false -> "This phone can't switch Bluetooth latency for apps; earbud game mode and codec choice are your levers."
            null -> null
        }
        verdict?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (d.gameLabelCanLowerLatency == true) MaterialTheme.colorScheme.primary else Tones.warning) }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
