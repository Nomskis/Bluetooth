package io.github.nomskis.earshot.system

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.nomskis.earshot.calls.CallNotifications

/**
 * What it takes for a call to ring this phone properly: notifications on,
 * the right to fill the lock screen, and on Xiaomi phones two switches of
 * their own. Each step opens the screen that changes it.
 */
object CallReadiness {
    enum class StepId { NOTIFICATIONS, FULL_SCREEN, XIAOMI_LOCK_SCREEN }

    /** [done] is null when Android can't tell us. */
    data class Step(val id: StepId, val title: String, val detail: String, val done: Boolean?)

    fun steps(brand: BackgroundHealth.Brand, sdk: Int, notificationsOn: Boolean, fullScreenOn: Boolean): List<Step> = buildList {
        add(Step(StepId.NOTIFICATIONS, "Allow notifications", "Calls ring through a notification, like the phone app's.", notificationsOn))
        // Android 14 made full-screen calls something you can switch off per app.
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Step(StepId.FULL_SCREEN, "Allow full-screen calls", "Lets a call fill the lock screen with Accept and Decline.", fullScreenOn))
        }
        if (brand == BackgroundHealth.Brand.XIAOMI) {
            add(
                Step(
                    StepId.XIAOMI_LOCK_SCREEN,
                    "Show on lock screen and pop up",
                    "HyperOS keeps these two off. In Other permissions, allow \"Show on Lock screen\" and " +
                        "\"Display pop-up windows while running in the background\".",
                    null,
                ),
            )
        }
    }

    fun current(context: Context): List<Step> =
        steps(BackgroundHealth.brandOf(Build.MANUFACTURER), Build.VERSION.SDK_INT, notificationsOn(context), CallNotifications.canRingFullScreen(context))

    /** Notifications allowed for the app, and the incoming-call channel not blocked. */
    fun notificationsOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CallNotifications.CHANNEL_INCOMING)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Opens the screen for [step], trying each known location in turn. */
    fun open(context: Context, step: StepId): Boolean = intentsFor(context, step).any { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun intentsFor(context: Context, step: StepId): List<Intent> {
        val pkg = context.packageName
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())
        val specific = when (step) {
            StepId.NOTIFICATIONS -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            StepId.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$pkg".toUri()))
            } else {
                emptyList()
            }
            // MIUI / HyperOS "Other permissions" page for one app.
            StepId.XIAOMI_LOCK_SCREEN -> listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
            )
        }
        return specific + details
    }
}
