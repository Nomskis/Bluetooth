package io.github.nomskis.earshot.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import io.github.nomskis.earshot.appGraph

/** "Reply" typed straight into a message notification, or "Mark as read". */
class MessageReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(MessageNotifications.EXTRA_CONVERSATION) ?: return
        if (intent.action == MessageNotifications.ACTION_MARK_READ) {
            // Read, and they're told, as if the conversation had been opened.
            context.appGraph.messenger.markRead(address)
            MessageNotifications.cancel(context, address)
            return
        }
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifications.KEY_REPLY)?.toString() ?: return
        val messenger = context.appGraph.messenger
        messenger.send(address, text)
        // Replying reads the conversation; the notification has done its job.
        messenger.markRead(address)
        MessageNotifications.cancel(context, address)
    }
}
