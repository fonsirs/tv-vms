package com.fonsi.tvvms

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN de acceso a la app: se guarda solo un hash con sal (PBKDF2), nunca el PIN. Bloqueo creciente tras 5 fallos. */
class PinStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vms_lock", Context.MODE_PRIVATE)

    fun isSet(): Boolean = prefs.contains("hash")

    /** PBKDF2 con SHA-256 (Android 8+); en equipos antiguos o con un PIN guardado por versiones previas, SHA-1. */
    private fun algo(): String =
        if (android.os.Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun hash(pin: String, salt: ByteArray, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120000, 256)
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun set(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("algo", algo())
            .putInt("iter", 120000)
            .putString("hash", Base64.encodeToString(hash(pin, salt, algo()), Base64.NO_WRAP))
            .putInt("fails", 0)
            .putLong("lockUntil", 0L)
            .putLong("lockMs", 0L)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Segundos que faltan para poder volver a intentarlo (0 si no está bloqueado). */
    fun lockedSeconds(): Long {
        // Se mide con el reloj interno (no cambia si alguien modifica la hora de la TV);
        // solo si la TV se ha reiniciado se usa la hora normal como respaldo.
        val ms = prefs.getLong("lockMs", 0L)
        if (ms <= 0L) return 0L
        val setAt = prefs.getLong("lockElapsed", 0L)
        val nowE = SystemClock.elapsedRealtime()
        val left = if (nowE >= setAt) ms - (nowE - setAt)
        else prefs.getLong("lockUntil", 0L) - System.currentTimeMillis()
        return if (left > 0) (left + 999) / 1000 else 0L
    }

    fun check(pin: String): Boolean {
        if (lockedSeconds() > 0) return false
        val saltStr = prefs.getString("salt", null) ?: return false
        val hashStr = prefs.getString("hash", null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expected = Base64.decode(hashStr, Base64.NO_WRAP)
        // los PIN guardados por versiones anteriores usan SHA-1 con 100000 vueltas
        val stored = prefs.getString("algo", null)
        val ok = if (stored == null) MessageDigest.isEqual(expected, legacyHash(pin, salt))
        else MessageDigest.isEqual(expected, hash(pin, salt, stored))
        if (ok) {
            if (stored == null) set(pin)
            prefs.edit().putInt("fails", 0).putLong("lockUntil", 0L).putLong("lockMs", 0L).apply()
            return true
        }
        val fails = prefs.getInt("fails", 0) + 1
        var lockMs = 0L
        if (fails >= 5) {
            val step = minOf(fails - 5, 6)
            lockMs = 30000L shl step
        }
        prefs.edit()
            .putInt("fails", fails)
            .putLong("lockMs", lockMs)
            .putLong("lockElapsed", SystemClock.elapsedRealtime())
            .putLong("lockUntil", if (lockMs > 0L) System.currentTimeMillis() + lockMs else 0L)
            .apply()
        return false
    }

    private fun legacyHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100000, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
