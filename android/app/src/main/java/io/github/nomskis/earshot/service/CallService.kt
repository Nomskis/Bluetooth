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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
    /** Capturing the screen for a share: the service says so to Android while it lasts. */
    private var projecting = false
    private var projection: MediaProjection? = null
    private val pointer by lazy { ScreenPointer(this) }
    private var lastInfo: NotificationInfo? = null

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
                    // The capture ending with the call mustn't bring the notification back.
                    projection = null
                    projecting = false
                    ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                coroutineScope {
                    launch { appGraph.settings.settings.collect { chatNotifier.quickReplies = it.quickReplies } }
                    // Where they point on our shared screen, over whatever app is in front.
                    launch {
                        try {
                            session.points.collect { pointer.show(it.x, it.y) }
                        } finally {
                            pointer.release()
                        }
                    }
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
                                connectedAt = it.connectedAt,
                                sharingScreen = it.sharingScreen,
                            )
                        }
                        .distinctUntilChanged()
                        .collect { info ->
                            lastInfo = info
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
            ACTION_SHARE_SCREEN -> shareScreen(intent)
            ACTION_STOP_SHARE -> appGraph.callManager.session.value?.stopScreenShare()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLocks.filter { it.isHeld }.forEach { it.release() }
        wifiLocks = emptyList()
        super.onDestroy()
    }

    /**
     * The consent Android gave for a share: the service first says it captures the screen (Android
     * 14 refuses the capture otherwise, and only allows saying so after consent), then makes the
     * capture and hands it to the call. It stops saying so when the capture ends, however it ends
     * (Stop, the status bar chip, the phone locking, the call turning it down).
     */
    private fun shareScreen(intent: Intent) {
        val session = appGraph.callManager.session.value ?: return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val consent = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java) ?: return
        projecting = true
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(lastInfo), foregroundTypes())
            val made = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, consent)
            if (made == null) {
                stopProjecting()
                return
            }
            projection = made
            made.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (projection === made) stopProjecting()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            session.startScreenShare(made)
        } catch (e: RuntimeException) {
            // SecurityException, or the consent was used already.
            android.util.Log.w("EarshotScreen", "Could not start capturing the screen", e)
            stopProjecting()
        }
    }

    private fun stopProjecting() {
        projection = null
        if (!projecting) return
        projecting = false
        // Saying less never needs more permission, but a refusal here mustn't take the call down.
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(lastInfo), foregroundTypes()) }
            .onFailure { android.util.Log.w("EarshotScreen", "Could not update the call's service", it) }
    }

    private fun foregroundTypes(): Int {
        // Android 10 already asks for the screen-capture type; the others came with Android 11.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return if (projecting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        }
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (projecting) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
        /** When it connected (SystemClock.elapsedRealtime), for the timer. */
        val connectedAt: Long? = null,
        val sharingScreen: Boolean = false,
    )

    private fun buildNotification(state: NotificationInfo?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_SHOW_CALL, true),
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
        } else if (state?.sharingScreen == true) {
            getString(R.string.notification_sharing_screen)
        } else {
            buildString {
                append(state?.room ?: "")
                if (state?.audioMode == AudioMode.HIFI) append(" · ").append(getString(R.string.notification_hifi))
            }
        }
        // Android's own ongoing-call look, as calling apps use: the call chip in the status bar,
        // who it's with, a running timer, and Hang up.
        val person = Person.Builder().setName(state?.calling ?: state?.peerName?.takeIf { it.isNotBlank() } ?: title).setImportant(true).build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .apply {
                state?.connectedAt?.let { at ->
                    setUsesChronometer(true)
                    setShowWhen(true)
                    setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - at))
                }
            }
            .apply {
                if (state != null) {
                    val mute = PendingIntent.getService(
                        this@CallService,
                        2,
                        Intent(this@CallService, CallService::class.java).setAction(ACTION_TOGGLE_MUTE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    addAction(0, getString(if (state.micMuted) R.string.unmute else R.string.mute), mute)
                    if (state.sharingScreen) {
                        val stop = PendingIntent.getService(
                            this@CallService,
                            3,
                            Intent(this@CallService, CallService::class.java).setAction(ACTION_STOP_SHARE),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        addAction(0, getString(R.string.stop_sharing), stop)
                    }
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "calls"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_HANG_UP = "io.github.nomskis.earshot.HANG_UP"
        private const val ACTION_TOGGLE_MUTE = "io.github.nomskis.earshot.TOGGLE_MUTE"
        private const val ACTION_REPLY = "io.github.nomskis.earshot.CHAT_REPLY"
        private const val ACTION_SHARE_SCREEN = "io.github.nomskis.earshot.SHARE_SCREEN"
        private const val ACTION_STOP_SHARE = "io.github.nomskis.earshot.STOP_SHARE"
        private const val EXTRA_RESULT_CODE = "io.github.nomskis.earshot.RESULT_CODE"
        private const val EXTRA_RESULT_DATA = "io.github.nomskis.earshot.RESULT_DATA"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
        }

        /** Android's consent to capture the screen ([resultCode], [data]) for the call's share. */
        fun shareScreen(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CallService::class.java)
                .setAction(ACTION_SHARE_SCREEN)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            // The service is already running for the call, and the app is in front: a plain start.
            context.startService(intent)
        }
    }
}
