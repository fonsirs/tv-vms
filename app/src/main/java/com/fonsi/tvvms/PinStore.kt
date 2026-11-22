package com.fonsi.tvvms

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN de acceso a la app: se guarda solo un hash con sal (PBKDF2), nunca el PIN. Bloqueo creciente tras 5 fallos. */
class PinStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vms_lock", Context.MODE_PRIVATE)

    fun isSet(): Boolean = prefs.contains("hash")

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    fun set(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0)
            .putLong("lockUntil", 0L)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Segundos que faltan para poder volver a intentarlo (0 si no está bloqueado). */
    fun lockedSeconds(): Long {
        val left = prefs.getLong("lockUntil", 0L) - System.currentTimeMillis()
        return if (left > 0) (left + 999) / 1000 else 0L
    }

    fun check(pin: String): Boolean {
        if (lockedSeconds() > 0) return false
        val saltStr = prefs.getString("salt", null) ?: return false
        val hashStr = prefs.getString("hash", null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expected = Base64.decode(hashStr, Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, hash(pin, salt))) {
            prefs.edit().putInt("fails", 0).putLong("lockUntil", 0L).apply()
            return true
        }
        val fails = prefs.getInt("fails", 0) + 1
        var lockUntil = 0L
        if (fails >= 5) {
            val step = minOf(fails - 5, 6)
            lockUntil = System.currentTimeMillis() + (30000L shl step)
        }
        prefs.edit().putInt("fails", fails).putLong("lockUntil", lockUntil).apply()
        return false
    }
}
