package io.github.nomskis.earshot.audio

import android.content.Context
import android.content.Intent

/**
 * Companion apps where earbud game / low-latency modes live. Only the ones
 * installed on the phone are offered. Package names must also be listed under
 * <queries> in the manifest to be visible on Android 11+.
 */
object EarbudApps {
    data class App(val packageName: String, val name: String)

    val KNOWN = listOf(
        App("com.realme.link", "realme Link"),
        App("com.heytap.headset", "HeyMelody (OPPO / OnePlus)"),
        App("com.samsung.android.app.watchmanager", "Galaxy Wearable"),
        App("com.sony.songpal.mdr", "Sony Sound Connect"),
        App("com.nothing.smartcenter", "Nothing X"),
        App("com.oceanwing.soundcore", "soundcore"),
        App("com.mi.earphone", "Xiaomi Earbuds"),
        App("com.huawei.smarthome", "HUAWEI AI Life"),
        App("com.jabra.moments", "Jabra Sound+"),
        App("com.bose.bosemusic", "Bose"),
        App("com.google.android.apps.wearables.maestro.companion", "Pixel Buds"),
    )

    fun installed(context: Context): List<App> =
        KNOWN.filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }

    fun launch(context: Context, app: App): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
