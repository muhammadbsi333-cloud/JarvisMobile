package com.jarvis.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Many phone makers (Xiaomi, Oppo/Realme, Vivo, Huawei, Samsung, Infinix/Tecno ...) add
 * their own "autostart" or "background" switch on top of Android and quietly stop apps
 * that are not on it. There is no standard way to flip it, so this opens the maker's
 * own page if the phone has one, and the app's normal settings page otherwise.
 */
object Autostart {
    private val pages = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutostartActivity")
    )

    /** Returns a short message saying what was opened. */
    fun open(ctx: Context): String {
        for (c in pages) {
            try {
                ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Opened your phone's autostart page - switch JARVIS on"
            } catch (e: Exception) {
                // this maker's page does not exist on this phone; try the next
            }
        }
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Opened JARVIS's app page - look for Battery or Autostart and allow background activity"
    }
}
