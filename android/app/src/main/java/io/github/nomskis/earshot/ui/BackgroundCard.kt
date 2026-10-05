package io.github.nomskis.earshot.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.nomskis.earshot.system.BackgroundHealth

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

    Notice("Keep calls going with the screen off") {
        steps.forEach { step ->
            val canOpen = step.done != true && step.id != BackgroundHealth.StepId.LOCK_IN_RECENTS
            NoticeStep(step.title, step.detail, action = if (canOpen) "Open" else null, onAction = { BackgroundHealth.open(context, step.id) }, done = step.done == true)
        }
        if (!needsAttention) NoticeActions { TextButton(onClick = onDone) { Text("All set") } }
    }
}
