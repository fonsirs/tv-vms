package com.fonsi.tvvms

import android.util.Base64
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** Cliente ONVIF mínimo: obtiene las URL RTSP (principal y secundaria) de una cámara. Debe llamarse fuera del hilo principal. */
object Onvif {

    class Streams(val main: String, val sub: String)

    private class Prof(val token: String, val pixels: Int)

    fun resolve(host: String, port: Int, user: String, pass: String): Streams {
        val deviceUrl = "http://$host:$port/onvif/device_service"
        val offset = timeOffset(deviceUrl)

        var firstError: Exception? = null
        var capsMedia: String? = null
        try {
            val xml = soap(
                deviceUrl,
                security(user, pass, offset),
                "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>"
            )
            capsMedia = firstText(parse(xml), "XAddr")
        } catch (e: Exception) {
            firstError = e
        }

        val candidates = ArrayList<String>()
        val fromCaps = capsMedia
        if (fromCaps != null) candidates.add(fromCaps)
        candidates.add("http://$host:$port/onvif/media_service")
        candidates.add("http://$host:$port/onvif/Media")
        candidates.add("http://$host:$port/onvif/media")

        var profilesXml: String? = null
        var mediaUrl = ""
        for (url in candidates) {
            try {
                profilesXml = soap(url, security(user, pass, offset), "<trt:GetProfiles/>")
                mediaUrl = url
                break
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        val xmlProfiles = profilesXml ?: throw (firstError ?: IOException("Sin respuesta ONVIF"))

        val doc = parse(xmlProfiles)
        val nodes = doc.getElementsByTagNameNS("*", "Profiles")
        val all = ArrayList<Prof>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val token = e.getAttribute("token")
            if (token.isNullOrBlank()) continue
            val w = e.getElementsByTagNameNS("*", "Width").item(0)?.textContent?.trim()?.toIntOrNull() ?: 0
            val h = e.getElementsByTagNameNS("*", "Height").item(0)?.textContent?.trim()?.toIntOrNull() ?: 0
            all.add(Prof(token, w * h))
        }
        val withVideo = all.filter { it.pixels > 0 }
        val sorted = (if (withVideo.isEmpty()) all else withVideo).sortedByDescending { it.pixels }
        if (sorted.isEmpty()) throw IOException("La cámara no devolvió perfiles de vídeo")

        val best = sorted.first()
        val smallest = sorted.last()
        val mainUri = streamUri(mediaUrl, best.token, user, pass, offset)
        val subUri =
            if (sorted.size > 1 && smallest.pixels < best.pixels)
                streamUri(mediaUrl, smallest.token, user, pass, offset)
            else ""
        return Streams(mainUri, subUri)
    }

    private fun streamUri(mediaUrl: String, token: String, user: String, pass: String, offset: Long): String {
        val body = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
            "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
            "<trt:ProfileToken>" + esc(token) + "</trt:ProfileToken></trt:GetStreamUri>"
        val xml = soap(mediaUrl, security(user, pass, offset), body)
        return firstText(parse(xml), "Uri") ?: throw IOException("La cámara no devolvió URL de vídeo")
    }

    /** Diferencia de reloj cámara - móvil, porque muchas cámaras rechazan el login WS-Security si la hora no cuadra. */
    internal fun timeOffset(deviceUrl: String): Long {
        return try {
            val doc = parse(soap(deviceUrl, "", "<tds:GetSystemDateAndTime/>"))
            val utc = doc.getElementsByTagNameNS("*", "UTCDateTime").item(0) as Element
            fun v(n: String): Int = utc.getElementsByTagNameNS("*", n).item(0).textContent.trim().toInt()
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(v("Year"), v("Month") - 1, v("Day"), v("Hour"), v("Minute"), v("Second"))
            cal.timeInMillis - System.currentTimeMillis()
        } catch (e: Exception) {
            0L
        }
    }

    internal fun security(user: String, pass: String, offset: Long): String {
        if (user.isBlank()) return ""
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val created = fmt.format(Date(System.currentTimeMillis() + offset))
        val md = MessageDigest.getInstance("SHA-1")
        md.update(nonce)
        md.update(created.toByteArray(Charsets.UTF_8))
        md.update(pass.toByteArray(Charsets.UTF_8))
        val digest = Base64.encodeToString(md.digest(), Base64.NO_WRAP)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        return "<wsse:Security s:mustUnderstand=\"1\"" +
            " xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\"" +
            " xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
            "<wsse:UsernameToken><wsse:Username>" + esc(user) + "</wsse:Username>" +
            "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" +
            digest + "</wsse:Password>" +
            "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" +
            nonceB64 + "</wsse:Nonce><wsu:Created>" + created + "</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    internal fun soap(url: String, header: String, body: String): String {
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"" +
            " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"" +
            " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"" +
            " xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
            "<s:Header>" + header + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readLimited(it) } ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code" + faultReason(text))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Lee como mucho 1 MB: una respuesta mayor no es de una cámara normal. */
    private fun readLimited(input: java.io.InputStream): String {
        val max = 1024 * 1024
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("Respuesta demasiado grande")
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun faultReason(text: String): String {
        val m = Regex("<[^>]*Text[^>]*>([^<]*)<").find(text) ?: return ""
        val reason = m.groupValues[1].trim()
        return if (reason.isEmpty()) "" else " ($reason)"
    }

    internal fun parse(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // Endurecimiento contra XML malicioso (DTD / entidades externas); si el sistema no lo admite, se ignora.
        for ((feat, v) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        )) {
            try {
                f.setFeature(feat, v)
            } catch (e: Exception) {
                // no soportado en este Android
            }
        }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    internal fun firstText(doc: Document, localName: String): String? {
        val text = doc.getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim()
        return if (text.isNullOrEmpty()) null else text
    }

    internal fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
