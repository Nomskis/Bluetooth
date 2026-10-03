package io.github.nomskis.earshot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import io.github.nomskis.earshot.MainActivity
import io.github.nomskis.earshot.R
import io.github.nomskis.earshot.call.CallSession
import io.github.nomskis.earshot.call.Chat
import io.github.nomskis.earshot.call.ChatMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Their chat messages as a notification while you can't see the call screen
 * (phone in your pocket, or the call shrunk to picture-in-picture), with
 * quick replies you can tap from the notification or the lock screen.
 */
class ChatNotifier(private val context: Context, private val replyIntent: Intent) {
    private val manager = NotificationManagerCompat.from(context)
    private var lastShown: List<ChatMessage> = emptyList()
    private var peerName: String? = null

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_chat),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Runs for one call: shows what arrives while the screen is away, clears it when you're back. */
    suspend fun follow(session: CallSession, callScreenVisible: StateFlow<Boolean>) {
        var seen = session.state.value.chat.size
        var alertedId: String? = null
        combine(
            session.state.map { it.chat to it.remotePeer?.name }.distinctUntilChanged(),
            callScreenVisible,
        ) { (chat, name), visible -> Triple(chat, name, visible) }
            .collect { (chat, name, visible) ->
                peerName = name?.takeIf { it.isNotBlank() }
                if (visible) {
                    seen = chat.size
                    clear()
                    return@collect
                }
                val fresh = chat.drop(seen)
                val newest = fresh.lastOrNull { !it.mine }
                // Your own replies only update a notification that's already up.
                if (newest == null) {
                    if (lastShown.isNotEmpty()) show(fresh, alert = false, canReply = true)
                    return@collect
                }
                show(fresh, alert = newest.id != alertedId, canReply = true)
                alertedId = newest.id
            }
    }

    /** The call is over: keep what they said readable, without a reply that can't be sent. */
    fun callEnded() {
        if (lastShown.isNotEmpty()) show(lastShown, alert = false, canReply = false)
    }

    /** After a reply from the notification when there's no call to send it to. */
    fun clear() {
        lastShown = emptyList()
        manager.cancel(NOTIFICATION_ID)
    }

    private fun show(messages: List<ChatMessage>, alert: Boolean, canReply: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        lastShown = messages
        val me = Person.Builder().setName(context.getString(R.string.chat_you)).build()
        val them = Person.Builder().setName(peerName ?: context.getString(R.string.chat_someone)).build()
        val style = NotificationCompat.MessagingStyle(me)
        messages.takeLast(MAX_SHOWN).forEach { style.addMessage(it.text, it.atMillis, if (it.mine) null else them) }
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
        if (canReply) builder.addAction(replyAction())
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission withdrawn mid-call.
        }
    }

    private fun replyAction(): NotificationCompat.Action {
        val input = RemoteInput.Builder(KEY_REPLY)
            .setLabel(context.getString(R.string.chat_reply))
            .setChoices(Chat.QUICK_REPLIES.toTypedArray())
            .build()
        // Mutable so Android can put the typed reply into it.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val reply = PendingIntent.getService(context, REQUEST_REPLY, replyIntent, mutable or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, context.getString(R.string.chat_reply), reply)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    companion object {
        const val KEY_REPLY = "reply"
        private const val CHANNEL_ID = "chat"
        private const val NOTIFICATION_ID = 2
        private const val REQUEST_OPEN = 10
        private const val REQUEST_REPLY = 11
        private const val MAX_SHOWN = 8

        /** The text of a reply typed or picked in the notification, if this intent carries one. */
        fun replyText(intent: Intent): String? =
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.takeIf { it.isNotBlank() }
    }
}
