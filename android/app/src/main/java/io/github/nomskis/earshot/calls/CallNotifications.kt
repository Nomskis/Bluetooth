package io.github.nomskis.earshot.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import io.github.nomskis.earshot.MainActivity
import io.github.nomskis.earshot.R

/** The notifications for calls coming in: ringing, missed, and the quiet "ready for calls" one. */
object CallNotifications {
    private const val CHANNEL_INCOMING = "incoming_calls"
    private const val CHANNEL_MISSED = "missed_calls"
    const val CHANNEL_READY = "ready_for_calls"

    const val ID_INCOMING = 10
    const val ID_READY = 11
    private const val ID_MISSED = 12

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // The ringer plays the ringtone and vibrates itself (looping, following the ringer mode),
        // so the channel stays silent and only makes the call pop up.
        val incoming = NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.channel_incoming_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(CHANNEL_MISSED, context.getString(R.string.channel_missed_calls), NotificationManager.IMPORTANCE_DEFAULT)
        val ready = NotificationChannel(CHANNEL_READY, context.getString(R.string.channel_ready_for_calls), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(incoming, missed, ready))
    }

    /** Android's incoming-call notification: full screen on the lock screen, Answer and Decline elsewhere. */
    fun showIncoming(context: Context, ring: IncomingRing) {
        val caller = Person.Builder().setName(ring.callerName).setImportant(true).build()
        val fullScreen = PendingIntent.getActivity(
            context,
            REQUEST_RINGING,
            IncomingCallActivity.intent(context, answer = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Answering opens the app (it needs to be in front to start the microphone and camera).
        val answer = PendingIntent.getActivity(
            context,
            REQUEST_ANSWER,
            IncomingCallActivity.intent(context, answer = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            context,
            REQUEST_DECLINE,
            Intent(context, RingActionReceiver::class.java).setAction(RingActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kind = context.getString(if (ring.video) R.string.incoming_video_call else R.string.incoming_voice_call)
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ring.callerName)
            .setContentText(kind)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(ring.video))
            .build()
        notify(context, ID_INCOMING, notification)
    }

    fun clearIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_INCOMING)
    }

    /** [busy]: they called while you were on another call. */
    fun showMissed(context: Context, ring: IncomingRing, busy: Boolean) {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_MISSED,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(if (busy) R.string.missed_call_busy else R.string.missed_call_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.missed_call_title, ring.callerName))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        notify(context, ID_MISSED, notification)
    }

    /** The foreground notification that keeps Earshot waiting for calls. */
    fun ready(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_READY,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ready_for_calls))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Android 14+ lets people (and Play) withhold full-screen calls; true when it's allowed. */
    fun canRingFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false

    private fun notify(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notification permission withdrawn.
        }
    }

    private const val REQUEST_RINGING = 20
    private const val REQUEST_ANSWER = 21
    private const val REQUEST_DECLINE = 22
    private const val REQUEST_MISSED = 23
    private const val REQUEST_READY = 24
}
