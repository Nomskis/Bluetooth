package io.github.nomskis.earshot.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.nomskis.earshot.appGraph

/** Decline, from the incoming-call notification. */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DECLINE) context.appGraph.callInbox.decline()
    }

    companion object {
        const val ACTION_DECLINE = "io.github.nomskis.earshot.DECLINE_CALL"
    }
}
