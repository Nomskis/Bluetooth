package io.github.nomskis.earshot.calls

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import io.github.nomskis.earshot.MainActivity
import io.github.nomskis.earshot.appGraph

/** "Call back" from a missed call: who to ring, and whether it was a video call. */
data class CallBackRequest(val contact: Contact, val video: Boolean, val atMillis: Long) {
    /** Only acted on straight away; never hours later when the home screen next appears. */
    fun isFresh(now: Long): Boolean = now - atMillis in 0..FRESH_MS

    companion object {
        const val FRESH_MS = 60_000L
    }
}

/**
 * Where a missed call's "Call back" button lands. Not exported, so only
 * Earshot's own notification can start a call this way; it hands the request
 * to the home screen (which asks for the microphone if needed) and gets out of
 * the way.
 */
class CallBackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        // On another call already: just open it.
        if (address != null && InboxKeys.isAddress(address) && !appGraph.callManager.busy) {
            val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Contact" }
            appGraph.callBack.value = CallBackRequest(Contact(name, address), intent.getBooleanExtra(EXTRA_VIDEO, false), System.currentTimeMillis())
        }
        NotificationManagerCompat.from(this).cancel(CallNotifications.ID_MISSED)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_VIDEO = "video"

        fun intent(context: Context, name: String, address: String, video: Boolean): Intent =
            Intent(context, CallBackActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_VIDEO, video)
    }
}
