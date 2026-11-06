package com.jarvis.mobile

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's copy of JARVIS's long-term memory, as {category: {key: {value, updated}}} -
 * the same shape the PC keeps, so the two can be merged entry by entry.
 */
object MemoryStore {
    private val lock = Any()
    private const val MAX_VALUE = 380

    private fun file(): File = File(JarvisApp.instance.filesDir, "memory.json")

    /** Missing file = first run (new device id). Unreadable file = refuse, never guess. */
    private fun load(): JSONObject {
        val f = file()
        if (!f.exists()) {
            Prefs.resetDeviceId()
            return JSONObject()
        }
        try {
            return JSONObject(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("the phone's memory file cannot be read; it was left untouched")
        }
    }

    private fun save(mem: JSONObject) {
        val f = file()
        val tmp = File(f.parentFile, "memory.json.tmp")
        tmp.writeText(mem.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun remember(key: String, value: String, category: String = "notes"): String {
        val k = key.trim().take(200)
        val v = value.trim().take(MAX_VALUE)
        if (k.isEmpty() || v.isEmpty()) return "nothing to remember"
        val cat = category.trim().ifEmpty { "notes" }.take(50)
        synchronized(lock) {
            val mem = load()
            val bucket = mem.optJSONObject(cat) ?: JSONObject().also { mem.put(cat, it) }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            bucket.put(k, JSONObject().put("value", v).put("updated", today))
            save(mem)
        }
        return "remembered"
    }

    /** Merge with the PC. The PC's answer replaces the local copy. */
    fun sync(): Int {
        synchronized(lock) {
            val merged = PcClient.memorySync(load())
            save(merged)
            var n = 0
            for (cat in merged.keys()) n += merged.optJSONObject(cat)?.length() ?: 0
            return n
        }
    }

    /** The memory as text for the model's instructions. */
    fun asPrompt(maxChars: Int = 6000): String {
        val mem = try {
            synchronized(lock) { load() }
        } catch (e: Exception) {
            return ""
        }
        val sb = StringBuilder()
        for (cat in mem.keys()) {
            val bucket = mem.optJSONObject(cat) ?: continue
            for (key in bucket.keys()) {
                val entry = bucket.opt(key)
                val value = if (entry is JSONObject) entry.optString("value") else entry.toString()
                if (value.isBlank()) continue
                val line = "- [$cat] ${key.replace('_', ' ')}: $value\n"
                if (sb.length + line.length > maxChars) return sb.toString()
                sb.append(line)
            }
        }
        return sb.toString()
    }
}
