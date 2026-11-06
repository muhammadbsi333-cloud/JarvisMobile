package com.jarvis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Keeps JARVIS alive on the phone: a foreground service (with the standing
 * notification Android requires) that owns the link to the PC.
 */
class JarvisService : Service() {
    private val statusListener: () -> Unit = { refreshNotification() }
    private var lastBattery = -100
    private var lastCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = Link.batteryLevel(context)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (level < 0) return
            if (Math.abs(level - lastBattery) >= 5 || charging != lastCharging) {
                lastBattery = level
                lastCharging = charging
                Link.sendEvent(
                    JSONObject().put("t", "event").put("kind", "battery")
                        .put("level", level).put("charging", charging)
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        Link.listen(statusListener)
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Link.unlisten(statusListener)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // not registered
        }
        Link.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "JARVIS link", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows that JARVIS is connected to your PC"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("JARVIS")
            .setContentText(Link.status)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_link"
        private const val NOTIFICATION_ID = 41

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, JarvisService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, JarvisService::class.java))
        }
    }
}
