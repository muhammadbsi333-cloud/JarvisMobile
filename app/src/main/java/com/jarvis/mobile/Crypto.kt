package com.jarvis.mobile

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The same envelope the PC dashboard uses (dashboard/server.py):
 *   key  = PBKDF2-HMAC-SHA256(session key, salt "JARVIS-DASHBOARD-v1", 200000 rounds, 32 bytes)
 *   wire = base64( nonce[12] + ciphertext + 16-byte GCM tag ), no associated data
 */
object Crypto {
    private val SALT = "JARVIS-DASHBOARD-v1".toByteArray(Charsets.UTF_8)
    private val rng = SecureRandom()
    private var cachedFor: String? = null
    private var cachedKey: ByteArray? = null

    @Synchronized
    fun aesKey(sessionKey: String): ByteArray {
        val hit = cachedKey
        if (hit != null && cachedFor == sessionKey) return hit
        val spec = PBEKeySpec(sessionKey.toCharArray(), SALT, 200_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        cachedFor = sessionKey
        cachedKey = key
        return key
    }

    fun encrypt(sessionKey: String, text: String): String {
        val nonce = ByteArray(12)
        rng.nextBytes(nonce)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey(sessionKey), "AES"), GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + c.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(sessionKey: String, b64: String): String {
        val raw = Base64.decode(b64, Base64.DEFAULT)
        require(raw.size > 12) { "frame too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey(sessionKey), "AES"),
            GCMParameterSpec(128, raw.copyOfRange(0, 12))
        )
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
