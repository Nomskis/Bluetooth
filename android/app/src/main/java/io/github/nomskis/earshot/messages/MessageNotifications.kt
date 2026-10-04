package io.github.nomskis.earshot.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import io.github.nomskis.earshot.MainActivity
import io.github.nomskis.earshot.R

/** A notification per conversation, with their latest messages and a Reply right there. */
object MessageNotifications {
    const val CHANNEL = "messages"
    const val EXTRA_CONVERSATION = "io.github.nomskis.earshot.CONVERSATION"
    const val KEY_REPLY = "reply"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun show(context: Context, name: String, address: String, conversation: Conversation) {
        val them = Person.Builder().setName(name).setKey(address).build()
        val me = Person.Builder().setName(context.getString(R.string.chat_you)).build()
        val style = NotificationCompat.MessagingStyle(me)
        // Their unread messages, and what was said just before for context.
        conversation.messages.takeLast(MAX_SHOWN).forEach { m ->
            style.addMessage(m.text, m.atMillis, if (m.mine) null else them)
        }
        val open = PendingIntent.getActivity(
            context,
            requestCode(address),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_CONVERSATION, address)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reply = PendingIntent.getBroadcast(
            context,
            requestCode(address),
            Intent(context, MessageReplyReceiver::class.java).putExtra(EXTRA_CONVERSATION, address),
            // Mutable: Android fills in the typed reply.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.chat_reply),
            reply,
        )
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.chat_reply)).build())
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(replyAction)
            .setShortcutId(address)
            .build()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(TAG, requestCode(address), notification)
        } catch (_: SecurityException) {
            // Notification permission withdrawn.
        }
    }

    fun cancel(context: Context, address: String) {
        NotificationManagerCompat.from(context).cancel(TAG, requestCode(address))
    }

    private fun requestCode(address: String) = address.hashCode()

    private const val TAG = "conversation"
    private const val MAX_SHOWN = 6
}
