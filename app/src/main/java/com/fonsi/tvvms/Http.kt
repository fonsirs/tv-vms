package com.fonsi.tvvms

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/** Cliente HTTP mínimo con autenticación Digest o Basic (las cámaras Dahua e Hikvision usan Digest). Fuera del hilo principal. */
object Http {

    /** Abre la URL y devuelve la conexión con respuesta 200 lista para leer. Quien la llama debe cerrarla. */
    fun connect(
        url: String, user: String, pass: String, connectMs: Int, readMs: Int,
        method: String = "GET", body: String? = null, contentType: String? = null
    ): HttpURLConnection {
        try {
            return connectOnce(url, user, pass, connectMs, readMs, method, body, contentType)
        } catch (e: IOException) {
            // un corte al empezar es habitual en estos equipos: un segundo intento casi siempre entra
            if ((e.message ?: "").startsWith("HTTP")) throw e
            Thread.sleep(400L)
            return connectOnce(url, user, pass, connectMs, readMs, method, body, contentType)
        }
    }

    /** Envía una orden (PUT/POST/GET) y descarta la respuesta; lanza IOException si no es 2xx. */
    fun send(url: String, user: String, pass: String, method: String, body: String? = null, contentType: String? = null) {
        val conn = connect(url, user, pass, 4000, 6000, method, body, contentType)
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(2048)
                while (input.read(buf) >= 0) { /* descartar */ }
            }
        } catch (e: IOException) {
            // la orden ya se aceptó (2xx); el cierre brusco de la respuesta no importa
        } finally {
            conn.disconnect()
        }
    }

    private fun connectOnce(
        url: String, user: String, pass: String, connectMs: Int, readMs: Int,
        method: String, body: String?, contentType: String?
    ): HttpURLConnection {
        var conn = open(url, null, connectMs, readMs, method, body, contentType)
        var code = conn.responseCode
        if (code == 401 && user.isNotBlank()) {
            val challenge = pickChallenge(conn.headerFields["WWW-Authenticate"])
            conn.disconnect()
            val auth = challenge?.let { answer(it, method, url, user, pass) }
                ?: throw IOException("HTTP 401 (la cámara no admite el tipo de login)")
            conn = open(url, auth, connectMs, readMs, method, body, contentType)
            code = conn.responseCode
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException(if (code == 401) "HTTP 401 (usuario o contraseña)" else "HTTP $code")
        }
        return conn
    }

    /**
     * Descarga como mucho maxBytes (por ejemplo una foto). Si el equipo corta la conexión antes de tiempo
     * pero ya envió datos, se devuelve lo recibido (una foto casi completa sirve de miniatura).
     */
    fun getBytes(url: String, user: String, pass: String, maxBytes: Int): ByteArray {
        val conn = connect(url, user, pass, 4000, 8000)
        val out = ByteArrayOutputStream()
        try {
            val input = conn.inputStream
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw IOException("Respuesta demasiado grande")
                out.write(buf, 0, n)
            }
        } catch (e: IOException) {
            if (out.size() < 2048) {
                throw IOException((e.message ?: "error") + " (" + out.size() + " B)")
            }
            // parcial pero útil
        } finally {
            conn.disconnect()
        }
        return out.toByteArray()
    }

    private fun open(
        url: String, auth: String?, connectMs: Int, readMs: Int,
        method: String = "GET", body: String? = null, contentType: String? = null
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        // Los Dahua/Hikvision cierran las conexiones: sin esto Android reutiliza una ya cerrada ("unexpected end of stream")
        conn.setRequestProperty("Connection", "close")
        if (auth != null) conn.setRequestProperty("Authorization", auth)
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    private fun pickChallenge(list: List<String>?): String? {
        if (list == null) return null
        return list.firstOrNull { it.trim().startsWith("Digest", true) }
            ?: list.firstOrNull { it.trim().startsWith("Basic", true) }
    }

    private fun answer(challenge: String, method: String, url: String, user: String, pass: String): String? {
        val c = challenge.trim()
        if (c.startsWith("Basic", true)) {
            return "Basic " + Base64.encodeToString((user + ":" + pass).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        if (!c.startsWith("Digest", true)) return null
        val p = HashMap<String, String>()
        Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").findAll(c.substring(6)).forEach {
            p[it.groupValues[1].lowercase()] = if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[3]
        }
        val realm = p["realm"] ?: return null
        val nonce = p["nonce"] ?: return null
        val algo = (p["algorithm"] ?: "MD5").uppercase()
        if (algo != "MD5") return null
        val u = URL(url)
        val uri = u.path + (if (u.query != null) "?" + u.query else "")
        val qopList = (p["qop"] ?: "").split(",").map { it.trim() }
        val useQop = qopList.contains("auth")
        val cn = ByteArray(8)
        SecureRandom().nextBytes(cn)
        val cnonce = cn.joinToString("") { "%02x".format(it) }
        val nc = "00000001"
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val resp = if (useQop) md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"").append(q(user)).append("\", realm=\"").append(q(realm))
            .append("\", nonce=\"").append(q(nonce)).append("\", uri=\"").append(q(uri)).append("\", response=\"").append(resp).append("\"")
        if (useQop) sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"")
        p["opaque"]?.let { sb.append(", opaque=\"").append(q(it)).append("\"") }
        return sb.toString()
    }

    private fun q(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Lee una línea de como mucho 16 KB; null al cerrarse el flujo. Evita memoria sin límite con un equipo extraño. */
    fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 16 * 1024) throw IOException("Línea demasiado larga")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
