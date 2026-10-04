package io.github.nomskis.earshot.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.nomskis.earshot.appGraph

/**
 * After a restart or an app update, start waiting for calls again without
 * anyone having to open the app. Starting the app is enough ([CallInbox]
 * follows the settings); this makes sure the service holds it while Android
 * still allows starting one.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> context.appGraph.callInbox.ensureService()
        }
    }
}
