package com.jarvis.mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The always-on link to the PC (the other end is dashboard/phone_bridge.py).
 * Reconnects with a growing pause, answers the PC's commands, and pushes events
 * (notifications, battery) up. Everything on the wire is AES-GCM encrypted.
 */
object Link {
    @Volatile var status: String = "Not started"
        private set
    @Volatile var connected: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val workers = Executors.newFixedThreadPool(2)
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var socket: WebSocket? = null
    private var thread: Thread? = null

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    private fun set(text: String, isConnected: Boolean) {
        status = text
        connected = isConnected
        for (l in listeners) l()
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        thread = Thread { loop(gen) }.also {
            it.isDaemon = true
            it.name = "jarvis-link"
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        socket?.close(1000, "stopped")
        thread?.interrupt()
        set("Stopped", false)
    }

    private fun loop(gen: Int) {
        var pause = 2_000L
        while (running && gen == generation) {
            if (!Prefs.paired) {
                set("Not paired with a PC yet", false)
                sleepOrStop(3_000)
                continue
            }
            val closed = CountDownLatch(1)
            try {
                set("Connecting to the PC...", false)
                val token = Session.token()
                socket = PcClient.openLink(token, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        pause = 2_000L
                        sendHello(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handle(webSocket, text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (code == 4001) Session.invalidate()
                        closed.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (response?.code == 401) Session.invalidate()
                        closed.countDown()
                    }
                })
                closed.await()
                socket = null
                if (running && gen == generation) set("PC link lost - retrying", false)
            } catch (e: AuthExpired) {
                if (gen == generation) {
                    set("Pairing expired - pair again", false)
                    running = false
                }
                return
            } catch (e: InterruptedException) {
                if (!running || gen != generation) return
            } catch (e: Exception) {
                if (gen == generation) set("PC unreachable - retrying", false)
            }
            sleepOrStop(pause)
            pause = minOf(pause * 2, 60_000L)
        }
    }

    private fun sleepOrStop(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // stop() interrupts the thread; the loop condition decides what happens next
        }
    }

    // -- frames ---------------------------------------------------------------------

    private fun send(ws: WebSocket, obj: JSONObject) {
        val enc = Crypto.encrypt(Prefs.sessionKey, obj.toString())
        ws.send(JSONObject().put("enc", enc).toString())
    }

    private fun sendHello(ws: WebSocket) {
        val ctx = JarvisApp.instance
        val hello = JSONObject()
            .put("t", "hello")
            .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim())
            .put("battery", batteryLevel(ctx))
            .put("caps", JSONArray(CommandExecutor.capabilities(ctx)))
        send(ws, hello)
    }

    fun batteryLevel(ctx: Context): Int {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun handle(ws: WebSocket, text: String) {
        val msg = try {
            val enc = JSONObject(text).optString("enc")
            if (enc.isEmpty()) return
            JSONObject(Crypto.decrypt(Prefs.sessionKey, enc))
        } catch (e: Exception) {
            return
        }
        when (msg.optString("t")) {
            "welcome" -> {
                set("Connected to the PC", true)
                workers.execute {
                    try {
                        MemoryStore.sync()
                    } catch (e: Exception) {
                        // the link stays up; the next sync will try again
                    }
                }
            }
            "cmd" -> workers.execute { runCommand(ws, msg) }
        }
    }

    private fun runCommand(ws: WebSocket, msg: JSONObject) {
        val params = msg.optJSONObject("params") ?: JSONObject()
        val result = try {
            CommandExecutor.run(JarvisApp.instance, msg.optString("action"), params)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        val reply = JSONObject()
            .put("t", "result")
            .put("id", msg.optString("id"))
            .put("ok", result.optBoolean("ok"))
            .put("data", result.optJSONObject("data") ?: JSONObject())
            .put("error", result.optString("error"))
        try {
            send(ws, reply)
        } catch (e: Exception) {
            // the socket closed while the command ran; the PC will see a disconnect
        }
    }

    /** Push an event (notification, battery) to the PC if the link is up. */
    fun sendEvent(event: JSONObject) {
        val ws = socket
        if (ws == null || !connected) return
        try {
            send(ws, event)
        } catch (e: Exception) {
            // dropped on purpose: events are best-effort
        }
    }
}
