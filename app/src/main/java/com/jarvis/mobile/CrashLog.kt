package com.jarvis.mobile

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so it can be read (Settings > Last crash report)
 * instead of the app just vanishing. Nothing is sent anywhere.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(ctx, error, "thread " + thread.name)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(ctx: Context, error: Throwable, where: String) {
        try {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file(ctx).writeText(("$stamp  ($where)\n" + sw.toString()).take(6000))
        } catch (e: Exception) {
            // nothing more can be done
        }
    }

    fun last(ctx: Context): String? {
        return try {
            val f = file(ctx)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}
