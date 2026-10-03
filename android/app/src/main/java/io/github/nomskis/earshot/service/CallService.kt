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
import io.github.nomskis.earshot.settings.AudioMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the call (camera, microphone, network) alive while the app is in the
 * background, e.g. while you pick songs in your music app.
 */
class CallService : LifecycleService() {

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val types = foregroundTypes()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), types)
        acquireWifiLock()

        val callManager = appGraph.callManager
        lifecycleScope.launch {
            callManager.session.collectLatest { session ->
                if (session == null) {
                    ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                // Only what the notification shows: the call state also changes several
                // times a second (talking cue, delay readout), and re-posting on each
                // would get the app rate-limited.
                session.state
                    .map { NotificationInfo(it.phase, it.remotePeer?.name, it.room, it.audioMode, it.micMuted) }
                    .distinctUntilChanged()
                    .collect { info ->
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(info))
                    }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_HANG_UP -> appGraph.callManager.endCall()
            ACTION_TOGGLE_MUTE -> appGraph.callManager.session.value?.let { it.setMicMuted(!it.state.value.micMuted) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLock?.takeIf { it.isHeld }?.release()
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

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "earshot:call").apply {
            setReferenceCounted(false)
            acquire()
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
        val title = when (state?.phase) {
            CallPhase.CONNECTED -> state.peerName?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.notification_in_call_with, it) }
                ?: getString(R.string.notification_in_call)
            CallPhase.WAITING -> getString(R.string.notification_waiting)
            CallPhase.RECONNECTING -> getString(R.string.notification_reconnecting)
            else -> getString(R.string.notification_connecting)
        }
        val text = buildString {
            append(state?.room ?: "")
            if (state?.audioMode == AudioMode.HIFI) append(" · ").append(getString(R.string.notification_hifi))
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
        }
    }
}
