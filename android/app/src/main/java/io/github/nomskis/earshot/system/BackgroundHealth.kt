package io.github.nomskis.earshot.system

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * What it takes for a call to survive the screen turning off. A foreground
 * service is enough on stock Android, but several phone makers add their own
 * battery managers that kill it anyway (see dontkillmyapp.com). This lists
 * the switches for the phone in hand and opens the right screen for each.
 */
object BackgroundHealth {
    enum class Brand { XIAOMI, HUAWEI, HONOR, OPPO_REALME, ONEPLUS, VIVO, SAMSUNG, OTHER }

    enum class StepId { BATTERY_OPTIMIZATION, OEM_BATTERY, AUTOSTART, LOCK_IN_RECENTS }

    /** [done] is null when Android can't tell us. */
    data class Step(val id: StepId, val title: String, val detail: String, val done: Boolean?)

    fun brandOf(manufacturer: String): Brand = when (manufacturer.trim().lowercase()) {
        "xiaomi", "redmi", "poco" -> Brand.XIAOMI
        "huawei" -> Brand.HUAWEI
        "honor" -> Brand.HONOR
        "oppo", "realme" -> Brand.OPPO_REALME
        "oneplus" -> Brand.ONEPLUS
        "vivo", "iqoo" -> Brand.VIVO
        "samsung" -> Brand.SAMSUNG
        else -> Brand.OTHER
    }

    /** Makers whose own battery manager is known to stop calls in the background. */
    fun isAggressive(brand: Brand) = brand != Brand.OTHER

    fun steps(brand: Brand, ignoringBatteryOptimizations: Boolean): List<Step> = buildList {
        add(
            Step(
                StepId.BATTERY_OPTIMIZATION,
                "Let Earshot run unrestricted",
                "Android's battery optimisation can pause the call when the screen is off.",
                ignoringBatteryOptimizations,
            ),
        )
        when (brand) {
            Brand.XIAOMI -> {
                add(Step(StepId.OEM_BATTERY, "Battery saver: No restrictions", "HyperOS / MIUI has its own battery saver per app. Set Earshot to \"No restrictions\".", null))
                add(Step(StepId.AUTOSTART, "Allow autostart", "Lets Earshot's call service restart if HyperOS stops it.", null))
                add(Step(StepId.LOCK_IN_RECENTS, "Lock Earshot in recent apps", "Open recent apps, long-press Earshot (or drag it down) and tap the lock, so clearing recents keeps it.", null))
            }
            Brand.HUAWEI, Brand.HONOR -> {
                add(Step(StepId.AUTOSTART, "App launch: manage manually", "In App launch, turn off \"Manage automatically\" for Earshot and allow all three switches.", null))
                add(Step(StepId.LOCK_IN_RECENTS, "Lock Earshot in recent apps", "Open recent apps and drag Earshot down to lock it.", null))
            }
            Brand.OPPO_REALME, Brand.ONEPLUS -> {
                add(Step(StepId.AUTOSTART, "Allow auto launch", "ColorOS / realme UI / OxygenOS can block background starts. Allow auto launch for Earshot.", null))
                add(Step(StepId.OEM_BATTERY, "Battery: allow background activity", "In Earshot's battery settings choose \"Allow background activity\" (or turn off \"Optimise battery use\").", null))
                add(Step(StepId.LOCK_IN_RECENTS, "Lock Earshot in recent apps", "Open recent apps, tap the menu on Earshot's card and choose Lock.", null))
            }
            Brand.VIVO -> {
                add(Step(StepId.AUTOSTART, "Allow background start", "In i Manager, allow Earshot to start in the background and run with high power use.", null))
                add(Step(StepId.LOCK_IN_RECENTS, "Lock Earshot in recent apps", "Open recent apps and drag Earshot down to lock it.", null))
            }
            Brand.SAMSUNG -> {
                add(Step(StepId.OEM_BATTERY, "Never sleeping apps", "In Battery › Background usage limits, add Earshot to \"Never sleeping apps\".", null))
            }
            Brand.OTHER -> Unit
        }
    }

    fun current(context: Context): List<Step> {
        val power = context.getSystemService(PowerManager::class.java)
        val ignoring = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        return steps(brandOf(android.os.Build.MANUFACTURER), ignoring)
    }

    /** Opens the screen for [step], trying each known location in turn. */
    fun open(context: Context, step: StepId): Boolean {
        val brand = brandOf(android.os.Build.MANUFACTURER)
        return intentsFor(context, brand, step).any { intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    @SuppressLint("BatteryLife") // a call app is the case this exemption exists for
    private fun intentsFor(context: Context, brand: Brand, step: StepId): List<Intent> {
        val pkg = context.packageName
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())
        fun component(p: String, c: String) = Intent().setComponent(ComponentName(p, c))
        val specific: List<Intent> = when (step) {
            StepId.BATTERY_OPTIMIZATION -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$pkg".toUri()),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            StepId.OEM_BATTERY -> when (brand) {
                Brand.XIAOMI -> listOf(
                    component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                        .putExtra("package_name", pkg)
                        .putExtra("package_label", "Earshot"),
                )
                Brand.SAMSUNG -> listOf(component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
                else -> emptyList()
            }
            StepId.AUTOSTART -> when (brand) {
                Brand.XIAOMI -> listOf(component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                Brand.HUAWEI -> listOf(
                    component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                )
                Brand.HONOR -> listOf(component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                Brand.OPPO_REALME -> listOf(
                    component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                )
                Brand.ONEPLUS -> listOf(component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
                Brand.VIVO -> listOf(
                    component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                )
                else -> emptyList()
            }
            StepId.LOCK_IN_RECENTS -> emptyList()
        }
        return specific + details
    }
}
