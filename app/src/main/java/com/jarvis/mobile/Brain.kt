package com.jarvis.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * JARVIS's brain on the phone, for when the PC is off or out of reach.
 *
 * It talks straight to Google's Gemini API (free tier, the user's own key) with
 * the phone's copy of the long-term memory in its instructions, and can act on
 * the phone through the same CommandExecutor the PC uses. Whatever it learns
 * (the `remember` tool) is written to the local memory and merged into the PC's
 * the next time the two meet.
 */
object Brain {
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val history = JSONArray()
    private const val MAX_STEPS = 6
    private const val KEEP_ITEMS = 30

    @Synchronized
    fun reset() {
        while (history.length() > 0) history.remove(0)
    }

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val memory = MemoryStore.asPrompt()
        return buildString {
            append("You are JARVIS, the user's personal assistant, now running on the user's phone ")
            append("because the PC is off or unreachable. Be brief and natural - the user may be speaking aloud. ")
            append("Reply in the language the user writes in (often Bangla). ")
            append("You can act on this phone with the phone_action tool. When its result says verified=false, ")
            append("tell the user you asked but cannot confirm it - never say it is done. ")
            append("Things that need the PC (its files, apps, screen) are not possible right now; say so plainly. ")
            append("Use the remember tool when the user tells you something worth keeping.\n")
            append("Now: ").append(now).append("\n")
            if (memory.isNotBlank()) append("\nWhat you remember about the user:\n").append(memory)
        }
    }

    private fun tools(): JSONArray {
        fun str(d: String) = JSONObject().put("type", "STRING").put("description", d)
        fun int(d: String) = JSONObject().put("type", "INTEGER").put("description", d)
        val phoneAction = JSONObject()
            .put("name", "phone_action")
            .put(
                "description",
                "Do something on this phone. action is one of: call (target = contact name or number), " +
                    "send_sms (target, message), open_app (app), open_url (url), battery, find_phone (seconds), " +
                    "volume (level 0-100), flashlight (state on/off), clipboard_set (text)."
            )
            .put(
                "parameters", JSONObject().put("type", "OBJECT")
                    .put(
                        "properties", JSONObject()
                            .put("action", str("which action"))
                            .put("target", str("contact name or phone number"))
                            .put("message", str("SMS text"))
                            .put("app", str("app name"))
                            .put("url", str("http(s) link"))
                            .put("level", int("volume 0-100"))
                            .put("state", str("on or off"))
                            .put("seconds", int("ring duration, 5-60"))
                            .put("text", str("text for the clipboard"))
                    )
                    .put("required", JSONArray().put("action"))
            )
        val remember = JSONObject()
            .put("name", "remember")
            .put("description", "Save a short fact about the user to long-term memory.")
            .put(
                "parameters", JSONObject().put("type", "OBJECT")
                    .put(
                        "properties", JSONObject()
                            .put("key", str("short name for the fact, e.g. favorite_food"))
                            .put("value", str("the fact itself, one sentence"))
                            .put("category", str("identity, preferences, relationships, notes"))
                    )
                    .put("required", JSONArray().put("key").put("value"))
            )
        return JSONArray().put(JSONObject().put("function_declarations", JSONArray().put(phoneAction).put(remember)))
    }

    private fun callGemini(): JSONObject {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
            .put("contents", history)
            .put("tools", tools())
            .toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${Prefs.model}:generateContent")
            .header("x-goog-api-key", Prefs.geminiKey)
            .post(body.toRequestBody(JSON))
            .build()
        val (code, text) = http.newCall(req).execute().use { r -> Pair(r.code, r.body?.string().orEmpty()) }
        if (code == 429) throw IOException("Gemini's free limit is used up for now - try again in a minute")
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                .getOrDefault("Gemini answered $code")
            throw IOException(msg)
        }
        return JSONObject(text)
    }

    private fun runTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "phone_action" -> {
                    val result = CommandExecutor.run(JarvisApp.instance, args.optString("action"), args)
                    if (result.optBoolean("ok")) {
                        val data = result.optJSONObject("data") ?: JSONObject()
                        if (!data.has("verified")) data.put("verified", true)
                        data.toString()
                    } else {
                        "FAILED: " + result.optString("error")
                    }
                }
                "remember" -> MemoryStore.remember(
                    args.optString("key"), args.optString("value"), args.optString("category", "notes")
                )
                else -> "unknown tool"
            }
        } catch (e: Exception) {
            "FAILED: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** History must not start in the middle of a tool exchange. */
    private fun trim() {
        while (history.length() > KEEP_ITEMS) history.remove(0)
        while (history.length() > 0) {
            val first = history.getJSONObject(0)
            val part = first.optJSONArray("parts")?.optJSONObject(0)
            if (first.optString("role") == "user" && part != null && part.has("text")) break
            history.remove(0)
        }
    }

    @Synchronized
    fun ask(userText: String): String {
        if (Prefs.geminiKey.isEmpty()) return "Add your Gemini API key in Settings first (free at aistudio.google.com)."
        history.put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))
        )
        trim()
        for (step in 0 until MAX_STEPS) {
            val response = callGemini()
            val content = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            if (content == null) {
                val why = response.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                return if (why.isNotEmpty()) "Gemini declined to answer ($why)." else "(no answer)"
            }
            history.put(content)
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val replies = JSONArray()
            val said = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val call = part.optJSONObject("functionCall")
                if (call != null) {
                    val name = call.optString("name")
                    val result = runTool(name, call.optJSONObject("args") ?: JSONObject())
                    replies.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", name).put("response", JSONObject().put("result", result))
                        )
                    )
                } else if (part.has("text") && !part.optBoolean("thought")) {
                    said.append(part.optString("text"))
                }
            }
            if (replies.length() == 0) return said.toString().trim().ifEmpty { "(no answer)" }
            history.put(JSONObject().put("role", "user").put("parts", replies))
        }
        return "I got stuck going round in circles on that - please try again."
    }
}
