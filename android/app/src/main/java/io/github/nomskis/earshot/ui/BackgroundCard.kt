package io.github.nomskis.earshot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.nomskis.earshot.system.BackgroundHealth
import io.github.nomskis.earshot.ui.theme.Accent

/**
 * "Keep calls alive with the screen off": the phone maker's battery
 * switches, each with a button to its screen. Re-checked whenever you come
 * back to the app.
 */
@Composable
fun BackgroundCard(done: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var steps by remember { mutableStateOf(BackgroundHealth.current(context)) }
    LifecycleResumeEffect(Unit) {
        steps = BackgroundHealth.current(context)
        onPauseOrDispose { }
    }
    val brand = remember { BackgroundHealth.brandOf(android.os.Build.MANUFACTURER) }
    val needsAttention = steps.any { it.done == false }
    if (!needsAttention && (done || !BackgroundHealth.isAggressive(brand))) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Keep calls going with the screen off", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your phone's battery manager can stop a call in your pocket. A minute here makes calls reliable.",
                style = MaterialTheme.typography.bodySmall,
            )
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (step.done == true) Accent else MaterialTheme.colorScheme.outlineVariant,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(step.title, style = MaterialTheme.typography.bodyMedium)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (step.done != true && step.id != BackgroundHealth.StepId.LOCK_IN_RECENTS) {
                            OutlinedButton(onClick = { BackgroundHealth.open(context, step.id) }) { Text("Open") }
                        }
                    }
                }
            }
            if (!needsAttention) TextButton(onClick = onDone) { Text("All set") }
        }
    }
}
