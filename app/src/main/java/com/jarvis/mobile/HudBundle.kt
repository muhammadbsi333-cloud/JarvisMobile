package com.jarvis.mobile

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The app's main screen. It is a web page that lives on the PC
 * (dashboard/static/phone_ui/index.html). The phone keeps the last copy it got, so
 * it opens even when the PC is off, and swaps in a newer one whenever it reaches
 * the PC - which is how a change made on the PC shows up on the phone without
 * reinstalling anything. A copy built into the app is the fallback for the very
 * first launch.
 */
object HudBundle {
    private const val MAX_BYTES = 400_000

    private fun htmlFile() = File(JarvisApp.instance.filesDir, "hud.html")
    private fun versionFile() = File(JarvisApp.instance.filesDir, "hud.version")

    private fun looksUsable(text: String): Boolean =
        text.length in 200..MAX_BYTES &&
            text.contains("<html", ignoreCase = true) &&
            text.contains("window.jarvis")          // the contract between the page and the app

    private fun sha16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

    /** Which copy is on screen: a hash from the PC, or "builtin". */
    fun version(): String {
        return try {
            if (htmlFile().exists()) versionFile().readText().trim().ifEmpty { "builtin" } else "builtin"
        } catch (e: Exception) {
            "builtin"
        }
    }

    /** The page to show: the saved copy from the PC if it is intact, else the built-in one. */
    fun html(): String {
        try {
            val f = htmlFile()
            if (f.exists()) {
                val text = f.readText(Charsets.UTF_8)
                if (looksUsable(text)) return text
            }
        } catch (e: Exception) {
            // fall through to the built-in copy
        }
        return JarvisApp.instance.assets.open("hud_default.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Ask the PC for a newer screen. True if one was saved (the caller should reload). */
    fun refresh(): Boolean {
        val reply = PcClient.fetchScreen(version())
        if (!reply.optBoolean("changed")) return false
        val version = reply.optString("version")
        val page = reply.optString("html")
        val bytes = page.toByteArray(Charsets.UTF_8)
        if (!Regex("^[0-9a-f]{16}$").matches(version) || bytes.size > MAX_BYTES || !looksUsable(page)) {
            throw IOException("the PC sent a screen this app does not accept")
        }
        if (sha16(bytes) != version) throw IOException("the screen was damaged on the way - it was not used")
        val dir = JarvisApp.instance.filesDir
        val tmp = File(dir, "hud.html.tmp")
        tmp.writeBytes(bytes)
        val target = htmlFile()
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        versionFile().writeText(version)
        return true
    }
}
