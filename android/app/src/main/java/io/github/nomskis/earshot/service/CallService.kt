package io.github.nomskis.earshot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.nomskis.earshot.MainActivity
import io.github.nomskis.earshot.R
import io.github.nomskis.earshot.appGraph
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.calls.OutgoingRing
import io.github.nomskis.earshot.settings.AudioMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the call (camera, microphone, network) alive while the app is in the
 * background, e.g. while you pick songs in your music app.
 */
class CallService : LifecycleService() {

    private var wifiLocks: List<WifiManager.WifiLock> = emptyList()
    private lateinit var chatNotifier: ChatNotifier

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val types = foregroundTypes()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), types)
        acquireWifiLock()
        chatNotifier = ChatNotifier(this, Intent(this, CallService::class.java).setAction(ACTION_REPLY))

        val callManager = appGraph.callManager
        lifecycleScope.launch {
            callManager.session.collectLatest { session ->
                if (session == null) {
                    chatNotifier.callEnded()
                    ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                coroutineScope {
                    launch { chatNotifier.follow(session, appGraph.callScreenVisible) }
                    // Only what the notification shows: the call state also changes several
                    // times a second (talking cue, delay readout), and re-posting on each
                    // would get the app rate-limited.
                    session.state
                        .map {
                            NotificationInfo(
                                it.phase,
                                it.remotePeer?.name ?: it.contactName,
                                // A direct call's room is a random code; who it's with means more.
                                it.contactName ?: it.room,
                                it.audioMode,
                                it.micMuted,
                                it.outputHeld,
                                calling = it.outgoing?.takeIf { o -> o.status == OutgoingRing.Status.CALLING || o.status == OutgoingRing.Status.RINGING }?.name,
                            )
                        }
                        .distinctUntilChanged()
                        .collect { info ->
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(info))
                        }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_HANG_UP -> appGraph.callManager.endCall()
            ACTION_TOGGLE_MUTE -> appGraph.callManager.session.value?.let { it.setMicMuted(!it.state.value.micMuted) }
            ACTION_REPLY -> ChatNotifier.replyText(intent)?.let { text ->
                appGraph.callManager.session.value?.sendChat(text) ?: chatNotifier.clear()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLocks.filter { it.isHeld }.forEach { it.release() }
        wifiLocks = emptyList()
        super.onDestroy()
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (cameraGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return types
    }

    /**
     * Keeps Wi-Fi out of power save for the call, which otherwise holds packets for
     * us at the router and sends them in bursts (delay spikes, a fuller jitter buffer).
     * Android applies the low-latency lock only while the screen is on and Earshot is
     * in front; the high-performance one also covers the screen being off (phone in a
     * pocket) on Android 10 to 13. From Android 14 the latter counts as a low-latency
     * lock, so holding both is never worse.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        }
        wifiLocks = modes.map { mode ->
            wifi.createWifiLock(mode, "earshot:call:$mode").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_calls), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class NotificationInfo(
        val phase: CallPhase,
        val peerName: String?,
        val room: String,
        val audioMode: AudioMode,
        val micMuted: Boolean,
        val outputHeld: Boolean,
        /** Ringing this contact. */
        val calling: String? = null,
    )

    private fun buildNotification(state: NotificationInfo?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            state?.calling != null -> getString(R.string.notification_calling, state.calling)
            state?.phase == CallPhase.CONNECTED -> state.peerName?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.notification_in_call_with, it) }
                ?: getString(R.string.notification_in_call)
            state?.phase == CallPhase.WAITING -> getString(R.string.notification_waiting)
            state?.phase == CallPhase.RECONNECTING -> getString(R.string.notification_reconnecting)
            else -> getString(R.string.notification_connecting)
        }
        val text = if (state?.outputHeld == true) {
            // The banner in the app may not be visible (pocket, picture-in-picture).
            getString(R.string.notification_output_held)
        } else {
            buildString {
                append(state?.room ?: "")
                if (state?.audioMode == AudioMode.HIFI) append(" · ").append(getString(R.string.notification_hifi))
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (state != null) {
                    val mute = PendingIntent.getService(
                        this@CallService,
                        2,
                        Intent(this@CallService, CallService::class.java).setAction(ACTION_TOGGLE_MUTE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    addAction(0, getString(if (state.micMuted) R.string.unmute else R.string.mute), mute)
                }
            }
            .addAction(0, getString(R.string.hang_up), hangUp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "calls"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_HANG_UP = "io.github.nomskis.earshot.HANG_UP"
        private const val ACTION_TOGGLE_MUTE = "io.github.nomskis.earshot.TOGGLE_MUTE"
        private const val ACTION_REPLY = "io.github.nomskis.earshot.CHAT_REPLY"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
        }
    }
}
