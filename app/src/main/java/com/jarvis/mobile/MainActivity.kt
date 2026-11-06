package com.jarvis.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * The main screen is a web page (see HudBundle) that this class feeds with what is
 * happening - messages, connection state, "listening" - and that calls back into
 * the small JarvisNative interface below when a button is pressed. The plain
 * Android settings screen is the other face of this activity.
 *
 * Chat goes to JARVIS on the PC when it is reachable; otherwise the phone's own
 * brain (Brain.kt) answers.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var hostField: EditText
    private lateinit var keyField: EditText
    private lateinit var geminiField: EditText
    private lateinit var modelField: EditText
    private lateinit var voiceField: EditText
    private lateinit var repoField: EditText

    private var chatSocket: WebSocket? = null
    @Volatile private var pcChatOpen = false
    @Volatile private var chatOpenedAt = 0L
    @Volatile private var speakAloud = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wasConnected = false
    private val transcript = ArrayList<Pair<String, String>>()
    private val linkListener: () -> Unit = { ui.post { onLinkChanged() } }

    // -- tiny view helpers ---------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun wide(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun field(hint: String, secret: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        return e
    }

    private fun heading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setPadding(0, dp(18), 0, dp(4))
        return t
    }

    private fun note(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        return t
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // -- building the screen --------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#010a0c")
        window.navigationBarColor = Color.parseColor("#010a0c")
        tts = TextToSpeech(this) { st -> ttsReady = (st == TextToSpeech.SUCCESS) }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#010a0c"))
        root.setOnApplyWindowInsetsListener { v, insets ->
            // Keep the content clear of the system bars and the keyboard.
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        buildWeb()
        buildSettings()
        setContentView(root)
        root.requestApplyInsets()
        showSettings(!Prefs.paired && Prefs.geminiKey.isEmpty())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb() {
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#010a0c"))
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.blockNetworkLoads = true            // the screen can only talk to the app, never to the internet
        s.setSupportZoom(false)
        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ui.post { recreate() }
                return true
            }
        }
        web.addJavascriptInterface(HudBridge(), "JarvisNative")
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loadScreen()
    }

    private fun loadScreen() {
        web.loadDataWithBaseURL("https://jarvis.hud/", HudBundle.html(), "text/html", "UTF-8", null)
    }

    private fun buildSettings() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(8), dp(14), dp(24))

        statusView = TextView(this)
        statusView.textSize = 14f
        col.addView(statusView, wide())

        col.addView(heading("Pair with your PC"))
        col.addView(note("On the PC press Remote Control in JARVIS to get a key. Use the PC's Tailscale address (100.x.y.z:8001) so it also works away from home."))
        hostField = field("PC address, e.g. 100.64.0.5:8001")
        hostField.setText(Prefs.host)
        col.addView(hostField, wide())
        keyField = field("One-time key from the PC")
        col.addView(keyField, wide())
        col.addView(button("Pair") { doPair() }, wide())

        col.addView(heading("Phone brain (answers when the PC is off)"))
        geminiField = field(if (Prefs.geminiKey.isEmpty()) "Gemini API key" else "Gemini API key (saved - leave empty to keep)", true)
        col.addView(geminiField, wide())
        modelField = field("Model")
        modelField.setText(Prefs.model)
        col.addView(modelField, wide())
        voiceField = field("Voice input language, e.g. bn-BD")
        voiceField.setText(Prefs.voiceLanguage)
        col.addView(voiceField, wide())
        col.addView(button("Save") { saveSettings() }, wide())

        col.addView(heading("Permissions JARVIS needs on this phone"))
        col.addView(button("1. Calls, contacts, SMS, notifications") { askRuntimePermissions() }, wide())
        col.addView(button("2. Notification access (read notifications)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, wide())
        col.addView(button("3. Display over other apps (open apps from the background)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, wide())
        col.addView(button("4. Don't optimise battery") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }, wide())
        col.addView(button("5. Keep running in the background (Xiaomi, Oppo, Vivo, Samsung...)") {
            toast(Autostart.open(this))
        }, wide())

        col.addView(heading("Link"))
        col.addView(button("Sync memory with the PC now") { syncNow() }, wide())
        col.addView(button("Refresh the main screen from the PC") { refreshScreen(true) }, wide())
        col.addView(button("Stop the link") {
            JarvisService.stop(this)
            Link.stop()
            renderStatus()
        }, wide())
        col.addView(button("Forget this PC") { forgetPc() }, wide())

        col.addView(heading("App updates"))
        col.addView(note("Installed version: ${Updater.installedVersion()}. The main screen updates from the PC by itself; this checks for a new version of the app."))
        repoField = field("GitHub project, e.g. yourname/JarvisMobile")
        repoField.setText(Prefs.updateRepo)
        col.addView(repoField, wide())
        col.addView(button("Check for an app update") { checkUpdate() }, wide())

        col.addView(heading("About"))
        col.addView(note("JARVIS Mobile works with JARVIS by FatihMakes (CC BY-NC 4.0, non-commercial use only). " +
            "This phone app sends nothing anywhere except to your own PC and, when the PC is off, to Google's Gemini API with your own key."))

        val scroll = ScrollView(this)
        scroll.addView(col)

        settingsPanel = LinearLayout(this)
        settingsPanel.orientation = LinearLayout.VERTICAL
        settingsPanel.setBackgroundColor(Color.parseColor("#10161a"))
        settingsPanel.addView(button("Back to JARVIS") { showSettings(false) }, wide())
        settingsPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        settingsPanel.visibility = View.GONE
        root.addView(settingsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showSettings(show: Boolean) {
        settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) renderStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPanel.visibility == View.VISIBLE) showSettings(false) else super.onBackPressed()
    }

    // -- what the page can ask of the app ---------------------------------------------------------

    /** Called from the page (on a WebView thread), so everything hops to the main thread first. */
    inner class HudBridge {
        @JavascriptInterface fun ready() { ui.post { pushAll() } }
        @JavascriptInterface fun send(text: String) { ui.post { submit(text) } }
        @JavascriptInterface fun mic() { ui.post { listen() } }
        @JavascriptInterface fun settings() { ui.post { showSettings(true) } }
        @JavascriptInterface fun sync() { ui.post { syncNow() } }
        @JavascriptInterface fun reset() { ui.post { Brain.reset(); transcript.clear() } }
        @JavascriptInterface fun setSpeak(on: Boolean) { speakAloud = on }
    }

    // -- feeding the page ---------------------------------------------------------------------------

    private fun js(code: String) {
        ui.post {
            try {
                web.evaluateJavascript("(function(){if(window.jarvis){$code}})();", null)
            } catch (e: Exception) {
                // the page is gone (activity closing); nothing to update
            }
        }
    }

    private fun stateJson(): String {
        val link = when {
            !Prefs.paired -> "Not paired - tap the sliders icon to set up"
            pcChatOpen -> "Connected to JARVIS on the PC"
            else -> "PC not reachable - phone brain (" + Link.status + ")"
        }
        return JSONObject().put("link", link).put("online", pcChatOpen).put("speak", speakAloud).toString()
    }

    private fun pushState() = js("jarvis.onState(${stateJson()});")

    private fun pushMessage(who: String, text: String, quiet: Boolean) {
        js("jarvis.onMessage(${JSONObject.quote(who)},${JSONObject.quote(text)},$quiet);")
    }

    private fun pushAll() {
        pushState()
        for ((who, text) in transcript) pushMessage(who, text, true)
    }

    private fun addTranscript(who: String, text: String, quiet: Boolean = false) {
        transcript.add(Pair(who, text))
        while (transcript.size > 100) transcript.removeAt(0)
        pushMessage(who, text, quiet)
    }

    private fun renderStatus() {
        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val overlayOn = Settings.canDrawOverlays(this)
        val pc = if (Prefs.paired) Link.status else "Not paired with a PC"
        statusView.text = "$pc\nMain screen: ${HudBundle.version()}\n" +
            "Notification access: ${if (listenerOn) "on" else "off"}   " +
            "Open apps in background: ${if (overlayOn) "on" else "off"}"
    }

    private fun onLinkChanged() {
        pushState()
        renderStatus()
        if (Link.connected && !wasConnected) refreshScreen(false)
        wasConnected = Link.connected
    }

    /** Fetch a newer main screen from the PC; reload the page if there is one. */
    private fun refreshScreen(loud: Boolean) {
        if (!Prefs.paired) {
            if (loud) toast("Pair with a PC first")
            return
        }
        Thread {
            try {
                val updated = HudBundle.refresh()
                ui.post {
                    if (updated) {
                        loadScreen()
                        if (loud) toast("Main screen updated from the PC")
                    } else if (loud) {
                        toast("Main screen is already up to date")
                    }
                    renderStatus()
                }
            } catch (e: Exception) {
                if (loud) ui.post { toast("Could not refresh: ${e.message}") }
            }
        }.start()
    }

    // -- lifecycle ----------------------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        Link.listen(linkListener)
        if (Prefs.paired) {
            JarvisService.start(this)
            openChat()
            refreshScreen(false)
        }
        pushState()
        renderStatus()
    }

    override fun onStop() {
        Link.unlisten(linkListener)
        closeChat()
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        web.removeJavascriptInterface("JarvisNative")
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // -- settings actions -------------------------------------------------------------------------------

    private fun doPair() {
        val host = hostField.text.toString()
        val key = keyField.text.toString()
        if (host.isBlank() || key.isBlank()) {
            toast("Enter the PC address and the one-time key")
            return
        }
        toast("Pairing...")
        Thread {
            try {
                val p = PcClient.pair(host, key)
                Prefs.host = p.host
                Prefs.certFp = p.certFp
                Prefs.sessionKey = p.key
                Prefs.deviceToken = p.deviceToken
                Session.seed(p.token)
                ui.post {
                    keyField.setText("")
                    toast("Paired. This phone now trusts only that PC's certificate (${p.certFp.take(12)}...).")
                    Link.stop()
                    Link.start()
                    JarvisService.start(this)
                    closeChat()
                    openChat()
                    refreshScreen(false)
                    showSettings(false)
                    pushState()
                }
            } catch (e: Exception) {
                ui.post { toast("Pairing failed: ${e.message}") }
            }
        }.start()
    }

    private fun saveSettings() {
        val k = geminiField.text.toString().trim()
        if (k.isNotEmpty()) {
            Prefs.geminiKey = k
            geminiField.setText("")
            geminiField.hint = "Gemini API key (saved - leave empty to keep)"
        }
        Prefs.model = modelField.text.toString().trim()
        Prefs.voiceLanguage = voiceField.text.toString().trim()
        Brain.reset()
        toast("Saved")
    }

    private fun askRuntimePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun syncNow() {
        toast("Syncing...")
        Thread {
            try {
                val n = MemoryStore.sync()
                ui.post {
                    toast("Memory in step with the PC ($n items)")
                    addTranscript("sys", "Memory synced ($n items).")
                }
            } catch (e: Exception) {
                ui.post { toast("Sync failed: ${e.message}") }
            }
        }.start()
    }

    private fun forgetPc() {
        JarvisService.stop(this)
        Link.stop()
        closeChat()
        Prefs.clearPairing()
        Prefs.host = ""
        renderStatus()
        pushState()
        toast("This phone no longer trusts that PC")
    }

    private fun checkUpdate() {
        Prefs.updateRepo = repoField.text.toString()
        toast("Checking...")
        Thread {
            try {
                val r = Updater.check(Prefs.updateRepo)
                ui.post {
                    if (r.newer) {
                        toast("Version ${r.latest} is available - opening the download page")
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.page)))
                    } else {
                        toast("You have the latest version (${Updater.installedVersion()})")
                    }
                }
            } catch (e: Exception) {
                ui.post { toast("Could not check: ${e.message}") }
            }
        }.start()
    }

    // -- talking ----------------------------------------------------------------------------------------------

    private fun submit(raw: String) {
        val text = raw.trim().take(2000)
        if (text.isEmpty()) return
        addTranscript("you", text)
        if (pcChatOpen) {
            Thread {
                try {
                    PcClient.sendCommand(text)
                } catch (e: Exception) {
                    ui.post { addTranscript("sys", "Could not reach the PC: ${e.message}") }
                }
            }.start()
        } else {
            js("jarvis.onBusy(true);")
            Thread {
                val answer = try {
                    Brain.ask(text)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                ui.post {
                    addTranscript("phone", answer)
                    if (speakAloud && ttsReady) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
                }
            }.start()
        }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.voiceLanguage)
        try {
            js("jarvis.onListening(true);")
            startActivityForResult(i, 7)
        } catch (e: Exception) {
            js("jarvis.onListening(false);")
            toast("No speech recogniser is installed on this phone")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7) return
        js("jarvis.onListening(false);")
        if (resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) submit(heard)
        }
    }

    // -- the PC's conversation ----------------------------------------------------------------------------------

    private fun openChat() {
        if (!Prefs.paired || chatSocket != null) return
        Thread {
            try {
                val token = Session.token()
                chatSocket = PcClient.openChat(token, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        chatOpenedAt = System.currentTimeMillis()
                        pcChatOpen = true
                        ui.post { pushState() }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        showPcMessage(text)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        chatClosed()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        chatClosed()
                    }
                })
            } catch (e: Exception) {
                ui.post { pushState() }
            }
        }.start()
    }

    private fun chatClosed() {
        pcChatOpen = false
        chatSocket = null
        ui.post { pushState() }
    }

    private fun closeChat() {
        chatSocket?.close(1000, "leaving")
        chatSocket = null
        pcChatOpen = false
    }

    private fun showPcMessage(raw: String) {
        val m = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        // The PC replays its recent history the moment we connect; show it, but do not animate for it.
        val replay = System.currentTimeMillis() - chatOpenedAt < 1500
        when (m.optString("type")) {
            "log" -> {
                val who = if (m.optString("speaker") == "user") "pc_user" else "pc"
                ui.post { addTranscript(who, m.optString("text"), replay) }
            }
            "sys" -> ui.post { addTranscript("sys", m.optString("text"), replay) }
            "status" -> js("jarvis.onStatus(${JSONObject.quote(m.optString("state"))});")
        }
    }
}
