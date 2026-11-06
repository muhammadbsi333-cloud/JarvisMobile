package com.jarvis.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the link back after the phone restarts (once the app has been paired). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Prefs.init(context)
            if (Prefs.paired) JarvisService.start(context)
        }
    }
}
