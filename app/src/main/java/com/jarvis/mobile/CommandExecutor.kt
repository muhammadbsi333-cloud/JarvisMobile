package com.jarvis.mobile

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the phone can do when JARVIS asks (from the PC, or from the phone's own
 * assistant). Every action answers {"ok": bool, "data": {...}, "error": "..."}.
 *
 * HONESTY RULE: data.verified is true only when the phone actually observed the
 * result (the radio reported the SMS sent, the call state left idle, the torch
 * callback fired). When Android gives no way to check - an app was asked to
 * open, a link was handed to the browser - verified is false and data.note says
 * why, so the assistant tells the user "I asked" rather than "done".
 */
object CommandExecutor {

    fun capabilities(ctx: Context): List<String> {
        val caps = mutableListOf(
            "call", "send_sms", "open_app", "open_url", "battery",
            "find_phone", "volume", "clipboard_set"
        )
        if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) caps.add("flashlight")
        return caps
    }

    private fun ok(data: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("ok", true).put("data", data)

    private fun fail(reason: String): JSONObject =
        JSONObject().put("ok", false).put("error", reason)

    private fun has(ctx: Context, permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun run(ctx: Context, action: String, p: JSONObject): JSONObject = when (action) {
        "call" -> call(ctx, p.optString("target"))
        "send_sms" -> sendSms(ctx, p.optString("target"), p.optString("message"))
        "open_app" -> openApp(ctx, p.optString("app"))
        "open_url" -> openUrl(ctx, p.optString("url"))
        "battery" -> battery(ctx)
        "find_phone" -> findPhone(ctx, p.optInt("seconds", 20).coerceIn(5, 60))
        "volume" -> volume(ctx, p.optInt("level", 50).coerceIn(0, 100))
        "flashlight" -> flashlight(ctx, p.optString("state").lowercase() == "on")
        "clipboard_set" -> clipboardSet(ctx, p.optString("text"))
        else -> fail("this phone app does not know the action '$action'")
    }

    // -- who is "Mom"? ----------------------------------------------------------------

    private class Person(val name: String, val number: String)

    private fun cleanNumber(raw: String): String = raw.replace(Regex("[^+0-9]"), "")

    /** A number as typed, or a contact name looked up in the address book. */
    private fun resolve(ctx: Context, target: String): Pair<Person?, String?> {
        val t = target.trim()
        if (t.isEmpty()) return Pair(null, "say who or which number")
        if (Regex("^[+0-9 ()\\-]{5,}$").matches(t)) return Pair(Person(t, cleanNumber(t)), null)
        if (!has(ctx, android.Manifest.permission.READ_CONTACTS)) {
            return Pair(null, "READ_CONTACTS is not granted, so '$t' cannot be looked up in the contacts")
        }
        val found = LinkedHashMap<String, String>()
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        ctx.contentResolver.query(
            phone,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$t%"),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                if (!found.containsKey(name)) found[name] = number
            }
        }
        if (found.isEmpty()) return Pair(null, "no contact matches '$t'")
        val exact = found.keys.firstOrNull { it.equals(t, ignoreCase = true) }
        if (exact != null) return Pair(Person(exact, cleanNumber(found.getValue(exact))), null)
        if (found.size == 1) {
            val only = found.entries.first()
            return Pair(Person(only.key, cleanNumber(only.value)), null)
        }
        return Pair(null, "several contacts match '$t' (${found.keys.take(5).joinToString(", ")}) - ask which one")
    }

    // -- call ---------------------------------------------------------------------------

    private fun call(ctx: Context, target: String): JSONObject {
        if (!has(ctx, android.Manifest.permission.CALL_PHONE)) return fail("CALL_PHONE permission is not granted")
        val (who, err) = resolve(ctx, target)
        if (who == null) return fail(err ?: "could not work out who to call")
        try {
            val telecom = ctx.getSystemService(TelecomManager::class.java)
            telecom.placeCall(Uri.fromParts("tel", who.number, null), Bundle())
        } catch (e: SecurityException) {
            return fail("Android refused to place the call: ${e.message}")
        }
        val data = JSONObject().put("to", who.name)
        if (!has(ctx, android.Manifest.permission.READ_PHONE_STATE)) {
            return ok(data.put("verified", false).put("note", "the call was placed but READ_PHONE_STATE is not granted, so it cannot be confirmed"))
        }
        val dialling = waitForCall(ctx)
        return if (dialling) {
            ok(data.put("verified", true).put("state", "dialling"))
        } else {
            ok(data.put("verified", false).put("note", "the call was requested but the phone never started dialling"))
        }
    }

    @Suppress("DEPRECATION")
    private fun waitForCall(ctx: Context): Boolean {
        return try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val until = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < until) {
                if (tm.callState != TelephonyManager.CALL_STATE_IDLE) return true
                Thread.sleep(400)
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    // -- SMS ----------------------------------------------------------------------------

    private fun sendSms(ctx: Context, target: String, text: String): JSONObject {
        if (text.isBlank()) return fail("the message is empty")
        if (!has(ctx, android.Manifest.permission.SEND_SMS)) return fail("SEND_SMS permission is not granted")
        val (who, err) = resolve(ctx, target)
        if (who == null) return fail(err ?: "could not work out who to message")
        val sms = ctx.getSystemService(SmsManager::class.java) ?: return fail("this phone has no SMS service")

        val parts = sms.divideMessage(text)
        val action = "com.jarvis.mobile.SMS_SENT." + UUID.randomUUID()
        val latch = CountDownLatch(parts.size)
        val bad = AtomicInteger(0)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode != Activity.RESULT_OK) bad.incrementAndGet()
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent.add(
                    PendingIntent.getBroadcast(
                        ctx, i, Intent(action).setPackage(ctx.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    )
                )
            }
            if (parts.size == 1) {
                sms.sendTextMessage(who.number, null, text, sent[0], null)
            } else {
                sms.sendMultipartTextMessage(who.number, null, parts, sent, null)
            }
            val answered = latch.await(20, TimeUnit.SECONDS)
            val data = JSONObject().put("to", who.name)
            return when {
                !answered -> ok(data.put("verified", false).put("note", "no answer from the phone's radio within 20 seconds; the message may or may not have gone"))
                bad.get() > 0 -> fail("the phone could not send the message (the network or SIM refused it)")
                else -> ok(data.put("verified", true).put("note", "sent from the phone; delivery to ${who.name} is not confirmed"))
            }
        } catch (e: Exception) {
            return fail("sending failed: ${e.message}")
        } finally {
            try {
                ctx.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // already gone
            }
        }
    }

    // -- apps and links -----------------------------------------------------------------

    private fun overlayNote(ctx: Context): String =
        if (Settings.canDrawOverlays(ctx)) "" else
            " Android may block this while the app is in the background; allow 'Display over other apps' for JARVIS."

    private fun openApp(ctx: Context, name: String): JSONObject {
        val query = name.trim()
        if (query.isEmpty()) return fail("say which app to open")
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val labelled = apps.map { Pair(it, it.loadLabel(pm).toString()) }
        val q = query.lowercase()
        var pick = labelled.filter { it.second.lowercase() == q }
        if (pick.isEmpty()) pick = labelled.filter { it.second.lowercase().startsWith(q) }
        if (pick.isEmpty()) pick = labelled.filter { it.first.activityInfo.packageName.lowercase() == q }
        if (pick.isEmpty()) pick = labelled.filter { it.second.lowercase().contains(q) }
        if (pick.isEmpty()) return fail("no app called '$query' is installed")
        if (pick.size > 1 && pick.map { it.second.lowercase() }.distinct().size > 1) {
            return fail("several apps match '$query' (${pick.take(5).joinToString(", ") { it.second }}) - ask which one")
        }
        val chosen = pick[0].first.activityInfo
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(chosen.packageName, chosen.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            ok(
                JSONObject().put("verified", false).put("app", pick[0].second)
                    .put("note", "the launch was requested; Android does not let this app confirm it opened." + overlayNote(ctx))
            )
        } catch (e: Exception) {
            fail("could not open ${pick[0].second}: ${e.message}")
        }
    }

    private fun openUrl(ctx: Context, url: String): JSONObject {
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            return fail("the link must start with http:// or https://")
        }
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ok(
                JSONObject().put("verified", false)
                    .put("note", "the link was handed to the browser; it cannot be confirmed to have loaded." + overlayNote(ctx))
            )
        } catch (e: Exception) {
            fail("no app could open that link: ${e.message}")
        }
    }

    // -- device state -------------------------------------------------------------------

    private fun battery(ctx: Context): JSONObject {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return fail("the battery state is not available")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return ok(JSONObject().put("level", if (scale > 0) level * 100 / scale else level).put("charging", charging))
    }

    private fun volume(ctx: Context, level: Int): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * level / 100f), 0)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(max, 1)
        return ok(JSONObject().put("verified", true).put("level", now))
    }

    private fun flashlight(ctx: Context, on: Boolean): JSONObject {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return fail("this phone has no flashlight")
        val latch = CountDownLatch(1)
        var seen: Boolean? = null
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) {
                    seen = enabled
                    if (enabled == on) latch.countDown()
                }
            }
        }
        cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        try {
            cm.setTorchMode(id, on)
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return fail("could not switch the flashlight: ${e.message}")
        } finally {
            cm.unregisterTorchCallback(callback)
        }
        return ok(JSONObject().put("verified", seen == on).put("state", if (on) "on" else "off"))
    }

    private fun clipboardSet(ctx: Context, text: String): JSONObject {
        if (text.isEmpty()) return fail("there is no text to copy")
        Handler(Looper.getMainLooper()).post {
            val cb = ctx.getSystemService(ClipboardManager::class.java)
            cb.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
        }
        return ok(JSONObject().put("verified", false).put("note", "copied to the clipboard; Android does not let a background app read it back to check"))
    }

    // -- find my phone ------------------------------------------------------------------

    private var ringer: MediaPlayer? = null
    private var savedAlarmVolume = -1

    @Synchronized
    private fun stopRinging() {
        try {
            ringer?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        ringer?.release()
        ringer = null
        if (savedAlarmVolume >= 0) {
            val am = JarvisApp.instance.getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            savedAlarmVolume = -1
        }
    }

    @Synchronized
    private fun findPhone(ctx: Context, seconds: Int): JSONObject {
        stopRinging()
        val am = ctx.getSystemService(AudioManager::class.java)
        savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(ctx, uri)
            player.isLooping = true
            player.prepare()
            player.start()
        } catch (e: Exception) {
            player.release()
            stopRinging()
            return fail("the phone could not play its alarm sound: ${e.message}")
        }
        ringer = player
        Handler(Looper.getMainLooper()).postDelayed({ stopRinging() }, seconds * 1000L)
        return ok(
            JSONObject().put("verified", player.isPlaying)
                .put("note", "ringing at full volume for $seconds seconds (silent if Do Not Disturb blocks alarms)")
        )
    }
}
