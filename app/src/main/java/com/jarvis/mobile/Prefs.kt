package com.jarvis.mobile

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (the session key, the device token, the Gemini key) are encrypted with
 * a key that lives in the Android Keystore and never leaves it, so a copy of the
 * app's data folder is useless on its own.
 */
private object SecretBox {
    private const val ALIAS = "jarvis_mobile_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? {
        return try {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        }
    }

    private fun secret(k: String): String = sp.getString(k, null)?.let { SecretBox.open(it) } ?: ""

    private fun putSecret(k: String, v: String) {
        val e = sp.edit()
        if (v.isEmpty()) e.remove(k) else e.putString(k, SecretBox.seal(v))
        e.apply()
    }

    /** "host:port" of the PC, normally its Tailscale address. */
    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) { sp.edit().putString("host", v).apply() }

    /** SHA-256 of the PC's certificate, pinned when pairing. */
    var certFp: String
        get() = sp.getString("cert_fp", "") ?: ""
        set(v) { sp.edit().putString("cert_fp", v).apply() }

    var sessionKey: String
        get() = secret("session_key")
        set(v) { putSecret("session_key", v) }

    var deviceToken: String
        get() = secret("device_token")
        set(v) { putSecret("device_token", v) }

    var geminiKey: String
        get() = secret("gemini_key")
        set(v) { putSecret("gemini_key", v) }

    var model: String
        get() = sp.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) { sp.edit().putString("model", v.ifBlank { "gemini-2.5-flash" }).apply() }

    /** GitHub "owner/name" that publishes new versions of this app (optional). */
    var updateRepo: String
        get() = sp.getString("update_repo", "") ?: ""
        set(v) { sp.edit().putString("update_repo", v.trim()).apply() }

    var voiceLanguage: String
        get() = sp.getString("voice_lang", "bn-BD") ?: "bn-BD"
        set(v) { sp.edit().putString("voice_lang", v.ifBlank { "bn-BD" }).apply() }

    /** Stable id the PC uses to remember what this phone last synced. */
    val deviceId: String
        get() {
            var id = sp.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sp.edit().putString("device_id", id).apply()
            }
            return id
        }

    fun resetDeviceId() {
        sp.edit().putString("device_id", UUID.randomUUID().toString()).apply()
    }

    val paired: Boolean
        get() = host.isNotEmpty() && deviceToken.isNotEmpty() && sessionKey.isNotEmpty() && certFp.isNotEmpty()

    fun clearPairing() {
        putSecret("device_token", "")
        putSecret("session_key", "")
        sp.edit().remove("cert_fp").apply()
    }
}
