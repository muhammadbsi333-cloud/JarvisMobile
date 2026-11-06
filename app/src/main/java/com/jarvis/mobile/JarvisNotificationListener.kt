package com.jarvis.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Forwards the phone's notifications to the PC while the link is up, so JARVIS
 * can read them out or answer "did anyone message me?". The user has to switch
 * this on in Settings > Notification access.
 *
 * Nothing is stored here and nothing is sent when the link is down. Note that
 * notifications can contain private text (one-time codes, banking alerts).
 */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName || sbn.isOngoing) return
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) {
                sbn.packageName
            }
            Link.sendEvent(
                JSONObject().put("t", "event").put("kind", "notification")
                    .put("app", label).put("title", title).put("text", text)
            )
        } catch (e: Exception) {
            // one odd notification must never take the listener down
        }
    }
}
