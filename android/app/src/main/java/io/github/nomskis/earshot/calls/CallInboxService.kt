package io.github.nomskis.earshot.calls

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Keeps Earshot running so calls can ring: Android only lets an app keep a
 * connection open in the background inside a foreground service (shown as
 * the quiet "Ready for calls" notification). The connection itself lives in
 * [CallInbox].
 */
class CallInboxService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallNotifications.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, CallNotifications.ID_READY, CallNotifications.ready(this), type)
        // Restarted by Android after being killed: the app's CallInbox picks the connection back up.
        return START_STICKY
    }
}
