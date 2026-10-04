package io.github.nomskis.earshot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import io.github.nomskis.earshot.calls.InboxClient
import io.github.nomskis.earshot.system.CallReadiness
import io.github.nomskis.earshot.ui.theme.Accent

/**
 * The switches that make this phone ring properly (checked again whenever you come back to
 * the app), or that the server can't be reached. Nothing at all once it's ready.
 */
@Composable
fun CallsReadyCard(
    status: InboxClient.State,
    setupDone: Boolean,
    onSetupDone: () -> Unit,
    stepsOverride: List<CallReadiness.Step>? = null,
) {
    val context = LocalContext.current
    var steps by remember { mutableStateOf(stepsOverride ?: CallReadiness.current(context)) }
    LifecycleResumeEffect(Unit) {
        steps = stepsOverride ?: CallReadiness.current(context)
        onPauseOrDispose { }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused before, so Android won't ask again: its settings page is the way.
        if (!granted) CallReadiness.open(context, CallReadiness.StepId.NOTIFICATIONS)
        steps = stepsOverride ?: CallReadiness.current(context)
    }
    val todo = steps.filter { it.done == false || (it.done == null && !setupDone) }
    if (todo.isEmpty() && (status == InboxClient.State.LISTENING || status == InboxClient.State.CONNECTING)) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (status) {
                    InboxClient.State.LISTENING -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (todo.isEmpty()) Accent else MaterialTheme.colorScheme.outline,
                    )
                    InboxClient.State.CONNECTING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    InboxClient.State.WAITING_TO_RETRY, InboxClient.State.STOPPED ->
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.weight(1f)) {
                    Text(statusTitle(status, todo.isEmpty()), style = MaterialTheme.typography.titleSmall)
                    statusDetail(status)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            todo.forEach { step ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(step.title, style = MaterialTheme.typography.bodyMedium)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = {
                            val ask = step.id == CallReadiness.StepId.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                            if (ask) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else CallReadiness.open(context, step.id)
                        }) { Text(if (step.done == false) "Allow" else "Open") }
                    }
                }
            }
            // Android can't check the phone maker's own switches, so you say when they're done.
            if (todo.isNotEmpty() && todo.all { it.done == null }) {
                TextButton(onClick = onSetupDone) { Text("Done") }
            }
        }
    }
}

internal fun statusTitle(status: InboxClient.State, setUp: Boolean): String = when (status) {
    InboxClient.State.LISTENING -> if (setUp) "Ready for calls" else "Finish setting up calls"
    InboxClient.State.CONNECTING -> "Connecting…"
    InboxClient.State.WAITING_TO_RETRY -> "Can't reach your server"
    InboxClient.State.STOPPED -> "Not receiving calls"
}

internal fun statusDetail(status: InboxClient.State): String? = when (status) {
    InboxClient.State.LISTENING, InboxClient.State.CONNECTING -> null
    InboxClient.State.WAITING_TO_RETRY -> "Calls can't ring this phone. Retrying."
    InboxClient.State.STOPPED -> "Turn on Receive calls in Settings"
}
