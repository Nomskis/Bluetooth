package io.github.nomskis.earshot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.earbuds.EarbudDrivers

/** What the tuner knows about the connected earbuds' own game mode. */
data class EarbudInfo(
    val checked: Boolean = false,
    val earbuds: String? = null,
    val family: String? = null,
    val experimental: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
)

@Composable
fun EarbudCard(
    info: EarbudInfo,
    autoGameMode: Boolean,
    onDetect: () -> Unit,
    onSwitch: (Boolean) -> Unit,
    onAutoGameMode: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onDetect()
    }
    LaunchedEffect(needsPermission) { if (!needsPermission && !info.checked) onDetect() }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Earbud game mode", style = MaterialTheme.typography.titleSmall)
            Text(
                "Classic Bluetooth has no standard way to ask earbuds for less delay, but many brands have " +
                    "their own switch. Earshot can flip it for: ${EarbudDrivers.familyNames.joinToString(", ")}.",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                needsPermission -> OutlinedButton(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                    Text("Allow Nearby devices to check my earbuds")
                }
                info.earbuds == null && info.checked -> Text(
                    "No Bluetooth earbuds playing right now. Connect them and check again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info.family == null && info.checked -> Text(
                    "${info.earbuds}: no switch known for this brand. Use its own app, then measure with the label \"Game mode on\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info.family != null -> {
                    Text(
                        "${info.earbuds}: ${info.family}" + if (info.experimental) " (new, not yet confirmed on real earbuds)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSwitch(true) }, enabled = info.busy == null) { Text("Game mode on") }
                        OutlinedButton(onClick = { onSwitch(false) }, enabled = info.busy == null) { Text("Off") }
                    }
                    LabelledSwitch(
                        "Turn it on for every call",
                        "Back to how it was when the call ends.",
                        autoGameMode,
                        onAutoGameMode,
                    )
                }
            }
            (info.busy ?: info.message)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!needsPermission) {
                OutlinedButton(onClick = onDetect, enabled = info.busy == null) { Text("Check again") }
            }
        }
    }
}
