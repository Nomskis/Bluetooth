package io.github.nomskis.earshot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.nomskis.earshot.calls.InboxClient
import io.github.nomskis.earshot.system.CallReadiness

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

    val broken = status == InboxClient.State.WAITING_TO_RETRY || status == InboxClient.State.STOPPED
    Notice(statusTitle(status, todo.isEmpty()), detail = statusDetail(status), warning = broken) {
        todo.forEach { step ->
            NoticeStep(step.title, step.detail, action = if (step.done == false) "Allow" else "Open", onAction = {
                val ask = step.id == CallReadiness.StepId.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                if (ask) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else CallReadiness.open(context, step.id)
            })
        }
        // Android can't check the phone maker's own switches, so you say when they're done.
        if (todo.isNotEmpty() && todo.all { it.done == null }) {
            NoticeActions { TextButton(onClick = onSetupDone) { Text("Done") } }
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
