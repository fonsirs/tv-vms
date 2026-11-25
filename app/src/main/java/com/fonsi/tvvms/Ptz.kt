package com.fonsi.tvvms

import java.io.IOException
import java.util.concurrent.Executors

/** Control PTZ (mover y zoom) para Dahua, Hikvision y ONVIF. Todo va por un hilo propio para que "parar" nunca adelante a "mover". */
object Ptz {

    enum class Dir { UP, DOWN, LEFT, RIGHT, ZOOM_IN, ZOOM_OUT }

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ptz").also { it.isDaemon = true } }

    private class OnvifInfo(val ptzUrl: String, val token: String, val offset: Long)

    private val onvifCache = HashMap<String, OnvifInfo>()
    private val supportCache = HashMap<String, Boolean?>()

    /** Comprueba (en segundo plano) si la cámara tiene PTZ. true = sí, false = no, null = no se pudo saber. */
    fun checkSupport(cam: Camera, done: (Boolean?) -> Unit) {
        exec.execute {
            val r = synchronized(supportCache) { if (supportCache.containsKey(cam.id)) supportCache[cam.id] else Unit }
            if (r != Unit) {
                @Suppress("UNCHECKED_CAST")
                done(r as Boolean?)
                return@execute
            }
            val res: Boolean? = try {
                supported(cam)
            } catch (e: Exception) {
                null
            }
            if (res != null) synchronized(supportCache) { supportCache[cam.id] = res }
            done(res)
        }
    }

    /** Empieza a mover. Si la cámara lo rechaza, onError recibe el motivo (se muestra en pantalla). */
    fun start(cam: Camera, dir: Dir, onError: ((String) -> Unit)? = null) {
        exec.execute {
            try {
                send(cam, dir, true)
            } catch (e: Exception) {
                onError?.invoke((e.message ?: e.javaClass.simpleName).take(80))
            }
        }
    }

    fun stop(cam: Camera, dir: Dir) {
        exec.execute { try { send(cam, dir, false) } catch (e: Exception) { } }
    }

    // ---------------------------------------------------------------- común

    private fun base(cam: Camera) = "http://" + cam.host + ":" + cam.onvifPort

    private fun supported(cam: Camera): Boolean? {
        return when (cam.brand) {
            Brand.DAHUA -> {
                try {
                    val url = base(cam) + "/cgi-bin/ptz.cgi?action=getCurrentProtocolCaps&channel=" + (cam.channel - 1)
                    val bytes = Http.getBytes(url, cam.user, cam.pass, 64 * 1024)
                    val text = String(bytes, Charsets.UTF_8)
                    if (text.contains("Error", true) && !text.contains("caps.")) false else true
                } catch (e: IOException) {
                    verdict(e)
                }
            }
            Brand.HIKVISION -> {
                try {
                    Http.getBytes(base(cam) + "/ISAPI/PTZCtrl/channels/" + cam.channel + "/capabilities", cam.user, cam.pass, 64 * 1024)
                    true
                } catch (e: IOException) {
                    verdict(e)
                }
            }
            Brand.ONVIF -> onvifInfo(cam) != null
            else -> false
        }
    }

    /** 400/403/404/501 = la cámara dice que no tiene PTZ; cualquier otro fallo = no se sabe. */
    private fun verdict(e: IOException): Boolean? {
        val m = e.message ?: ""
        return if (Regex("^HTTP (400|403|404|405|501)").containsMatchIn(m)) false else null
    }

    private fun send(cam: Camera, dir: Dir, go: Boolean) {
        when (cam.brand) {
            Brand.DAHUA -> {
                val code = when (dir) {
                    Dir.UP -> "Up"; Dir.DOWN -> "Down"; Dir.LEFT -> "Left"; Dir.RIGHT -> "Right"
                    Dir.ZOOM_IN -> "ZoomTele"; Dir.ZOOM_OUT -> "ZoomWide"
                }
                val url = base(cam) + "/cgi-bin/ptz.cgi?action=" + (if (go) "start" else "stop") +
                    "&channel=" + (cam.channel - 1) + "&code=" + code + "&arg1=0&arg2=5&arg3=0"
                val r = Http.send(url, cam.user, cam.pass, "GET")
                if (r.startsWith("Error", true)) throw IOException("la cámara responde: " + r.replace(Regex("\\s+"), " "))
            }
            Brand.HIKVISION -> {
                var pan = 0
                var tilt = 0
                var zoom = 0
                if (go) when (dir) {
                    Dir.UP -> tilt = 50; Dir.DOWN -> tilt = -50; Dir.LEFT -> pan = -50; Dir.RIGHT -> pan = 50
                    Dir.ZOOM_IN -> zoom = 50; Dir.ZOOM_OUT -> zoom = -50
                }
                val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><PTZData><pan>$pan</pan><tilt>$tilt</tilt><zoom>$zoom</zoom></PTZData>"
                Http.send(
                    base(cam) + "/ISAPI/PTZCtrl/channels/" + cam.channel + "/continuous",
                    cam.user, cam.pass, "PUT", xml, "application/xml"
                )
            }
            Brand.ONVIF -> {
                val info = onvifInfo(cam) ?: throw IOException("Sin PTZ ONVIF")
                val body = if (go) {
                    var x = 0.0
                    var y = 0.0
                    var z = 0.0
                    when (dir) {
                        Dir.UP -> y = 0.5; Dir.DOWN -> y = -0.5; Dir.LEFT -> x = -0.5; Dir.RIGHT -> x = 0.5
                        Dir.ZOOM_IN -> z = 0.5; Dir.ZOOM_OUT -> z = -0.5
                    }
                    "<tptz:ContinuousMove xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\"><tptz:ProfileToken>" +
                        Onvif.esc(info.token) + "</tptz:ProfileToken><tptz:Velocity>" +
                        "<tt:PanTilt x=\"$x\" y=\"$y\"/><tt:Zoom x=\"$z\"/></tptz:Velocity></tptz:ContinuousMove>"
                } else {
                    "<tptz:Stop xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\"><tptz:ProfileToken>" +
                        Onvif.esc(info.token) + "</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>"
                }
                Onvif.soap(info.ptzUrl, Onvif.security(cam.user, cam.pass, info.offset), body)
            }
            else -> throw IOException("Sin PTZ")
        }
    }

    /** Dirección del servicio PTZ y perfil con PTZ de una cámara ONVIF (se guarda para no repetirlo). */
    private fun onvifInfo(cam: Camera): OnvifInfo? {
        synchronized(onvifCache) { onvifCache[cam.id]?.let { return it } }
        val deviceUrl = base(cam) + "/onvif/device_service"
        val offset = Onvif.timeOffset(deviceUrl)
        fun xaddr(category: String): String? = try {
            Onvif.firstText(
                Onvif.parse(
                    Onvif.soap(
                        deviceUrl, Onvif.security(cam.user, cam.pass, offset),
                        "<tds:GetCapabilities><tds:Category>$category</tds:Category></tds:GetCapabilities>"
                    )
                ), "XAddr"
            )
        } catch (e: Exception) {
            null
        }
        val ptzUrl = xaddr("PTZ") ?: return null
        val media = xaddr("Media") ?: (base(cam) + "/onvif/media_service")
        val prof = Onvif.parse(Onvif.soap(media, Onvif.security(cam.user, cam.pass, offset), "<trt:GetProfiles/>"))
        val nodes = prof.getElementsByTagNameNS("*", "Profiles")
        var token: String? = null
        for (i in 0 until minOf(nodes.length, 24)) {
            val e = nodes.item(i) as org.w3c.dom.Element
            val t = e.getAttribute("token")
            if (t.isNullOrBlank()) continue
            if (e.getElementsByTagNameNS("*", "PTZConfiguration").length > 0) {
                token = t
                break
            }
        }
        val tk = token ?: return null
        val info = OnvifInfo(fixHost(ptzUrl, cam), tk, offset)
        synchronized(onvifCache) { onvifCache[cam.id] = info }
        return info
    }

    /** Algunas cámaras anuncian en ONVIF una IP interna distinta de la que usamos: se cambia por la nuestra. */
    private fun fixHost(url: String, cam: Camera): String {
        return try {
            val u = java.net.URL(url)
            if (u.host == cam.host) url else java.net.URL(u.protocol, cam.host, if (u.port > 0) u.port else cam.onvifPort, u.file).toString()
        } catch (e: Exception) {
            url
        }
    }
}
