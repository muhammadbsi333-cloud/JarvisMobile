package com.jarvis.mobile

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Looks for a newer build of the app itself on GitHub (the project's Releases page).
 * The main screen does not need this - it updates from the PC - but a change to the
 * app's own code (a new phone action, say) does.
 */
object Updater {
    class Result(val newer: Boolean, val latest: String, val page: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun installedVersion(): String {
        return try {
            val ctx = JarvisApp.instance
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    private fun numbers(text: String): List<Int> =
        Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun isNewer(latest: String, installed: String): Boolean {
        val a = numbers(latest)
        val b = numbers(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun check(repo: String): Result {
        val r = repo.trim()
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(r)) { "write the GitHub project as owner/name" }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$r/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val (code, body) = http.newCall(req).execute().use { Pair(it.code, it.body?.string().orEmpty()) }
        if (code == 404) throw IOException("no release has been published in $r yet")
        if (code !in 200..299) throw IOException("GitHub answered $code")
        val j = JSONObject(body)
        val tag = j.optString("tag_name")
        val page = j.optString("html_url")
        return Result(isNewer(tag, installedVersion()), tag, page)
    }
}
