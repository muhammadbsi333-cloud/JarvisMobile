package com.jarvis.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The saved pairing is no longer accepted by the PC; the user has to pair again. */
class AuthExpired : IOException("The PC no longer recognises this phone - pair again")

/**
 * Everything the app says to the PC's dashboard server.
 *
 * TLS: the dashboard uses a self-signed certificate, so the normal trust store
 * cannot vouch for it. Instead the certificate seen at pairing is pinned (its
 * SHA-256 fingerprint) and every later connection must present exactly that
 * certificate. Host names are not checked - on a Tailscale address they would
 * not match anyway - because the pin is stricter than a name check.
 */
object PcClient {
    private val JSON = "application/json".toMediaType()

    class Paired(
        val host: String, val token: String, val key: String,
        val deviceToken: String, val certFp: String
    )

    private class PinTrust(private val pinned: String, private val capture: Boolean) : X509TrustManager {
        @Volatile
        var seen: String = ""

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates are not used")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("the PC sent no certificate")
            val fp = sha256Hex(leaf.encoded)
            seen = fp
            if (capture) return
            if (!fp.equals(pinned, ignoreCase = true)) {
                throw CertificateException("the PC's certificate is not the one that was paired")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun client(trust: PinTrust, forSocket: Boolean): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        val b = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(10, TimeUnit.SECONDS)
        if (forSocket) {
            b.readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS)
        } else {
            b.readTimeout(30, TimeUnit.SECONDS)
        }
        return b.build()
    }

    private fun pinnedClient(forSocket: Boolean) = client(PinTrust(Prefs.certFp, false), forSocket)

    fun normalizeHost(raw: String): String {
        var h = raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (h.contains('/')) h = h.substringBefore('/')
        if (!h.contains(':')) h += ":8001"
        return h
    }

    private fun base(): String = "https://" + Prefs.host

    // -- pairing -----------------------------------------------------------------

    private fun fetch(http: OkHttpClient, req: Request): Pair<Int, String> =
        http.newCall(req).execute().use { r -> Pair(r.code, r.body?.string().orEmpty()) }

    /** Trade the one-time key shown on the PC for a login. Pins the PC's certificate. */
    fun pair(hostRaw: String, oneTimeKey: String): Paired {
        val host = normalizeHost(hostRaw)
        val trust = PinTrust("", true)
        val url = "https://$host/auto-login?key=" + URLEncoder.encode(oneTimeKey.trim().uppercase(), "UTF-8")
        val (_, html) = fetch(client(trust, false), Request.Builder().url(url).build())
        fun grab(name: String): String? =
            Regex("'$name'\\s*,\\s*'([^']+)'").find(html)?.groupValues?.get(1)
        val token = grab("jarvis_token")
        val key = grab("jarvis_key")
        val dev = grab("jarvis_device_token")
        if (token == null || key == null || dev == null || trust.seen.isEmpty()) {
            throw IOException(
                "The PC refused the key. It works once and expires after 10 minutes - " +
                    "press Remote Control in JARVIS on the PC to get a new one."
            )
        }
        return Paired(host, token, key, dev, trust.seen)
    }

    /** A fresh auth token for the saved pairing. */
    fun login(): String {
        val body = JSONObject().put("device_token", Prefs.deviceToken).toString().toRequestBody(JSON)
        val req = Request.Builder().url(base() + "/api/device-login").post(body).build()
        val (code, text) = fetch(pinnedClient(false), req)
        if (code == 401) throw AuthExpired()
        if (code !in 200..299) throw IOException("login failed ($code)")
        val j = JSONObject(text)
        if (!j.optBoolean("ok")) throw AuthExpired()
        return j.getString("token")
    }

    // -- encrypted requests ---------------------------------------------------------

    /** POST an encrypted body; retries once with a fresh login if the token had expired. */
    private fun post(path: String, plain: String): String {
        val sk = Prefs.sessionKey
        val payload = JSONObject().put("enc", Crypto.encrypt(sk, plain)).toString()
        for (attempt in 0..1) {
            val req = Request.Builder().url(base() + path)
                .header("Authorization", "Bearer " + Session.token())
                .post(payload.toRequestBody(JSON)).build()
            val (code, text) = fetch(pinnedClient(false), req)
            if (code == 401) {
                Session.invalidate()
                continue
            }
            if (code == 409) {
                throw IOException(runCatching { JSONObject(text).optString("error") }.getOrDefault("refused"))
            }
            if (code !in 200..299) throw IOException("the PC answered $code")
            return text
        }
        throw AuthExpired()
    }

    /** GET with the PC's auth token; retries once with a fresh login if the token had expired. */
    private fun get(path: String): String {
        for (attempt in 0..1) {
            val req = Request.Builder().url(base() + path)
                .header("Authorization", "Bearer " + Session.token()).get().build()
            val (code, text) = fetch(pinnedClient(false), req)
            if (code == 401) {
                Session.invalidate()
                continue
            }
            if (code !in 200..299) throw IOException("the PC answered $code")
            return text
        }
        throw AuthExpired()
    }

    /** Ask the PC for the phone's main screen; the reply says whether ours is out of date. */
    fun fetchScreen(knownVersion: String): JSONObject =
        JSONObject(get("/api/phone-ui?v=" + URLEncoder.encode(knownVersion, "UTF-8")))

    /** Send the phone's whole memory; returns the merged memory the phone should keep. */
    fun memorySync(memory: JSONObject): JSONObject {
        val text = post(
            "/api/memory/sync",
            JSONObject().put("device_id", Prefs.deviceId).put("memory", memory).toString()
        )
        val enc = JSONObject(text).optString("enc")
        if (enc.isEmpty()) throw IOException("the PC sent an unreadable reply")
        val reply = JSONObject(Crypto.decrypt(Prefs.sessionKey, enc))
        if (!reply.optBoolean("ok")) throw IOException("the PC did not accept the sync")
        return reply.getJSONObject("memory")
    }

    /** Type a command to JARVIS on the PC, exactly as if it had been said. */
    fun sendCommand(text: String) {
        post("/api/command", text)
    }

    // -- sockets ----------------------------------------------------------------------

    fun openLink(token: String, l: WebSocketListener): WebSocket =
        pinnedClient(true).newWebSocket(
            Request.Builder().url(base() + "/ws/phone-control?token=" + URLEncoder.encode(token, "UTF-8")).build(), l
        )

    fun openChat(token: String, l: WebSocketListener): WebSocket =
        pinnedClient(true).newWebSocket(
            Request.Builder().url(base() + "/ws?token=" + URLEncoder.encode(token, "UTF-8")).build(), l
        )
}

/** Caches the PC's short-lived auth token (it expires after 12 hours). */
object Session {
    private var token: String = ""
    private var obtainedAt = 0L

    @Synchronized
    fun token(): String {
        val age = System.currentTimeMillis() - obtainedAt
        if (token.isEmpty() || age > 11 * 3600 * 1000L) {
            token = PcClient.login()
            obtainedAt = System.currentTimeMillis()
        }
        return token
    }

    @Synchronized
    fun invalidate() {
        token = ""
    }

    @Synchronized
    fun seed(t: String) {
        token = t
        obtainedAt = System.currentTimeMillis()
    }
}
