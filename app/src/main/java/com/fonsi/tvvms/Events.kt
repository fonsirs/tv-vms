package com.fonsi.tvvms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/** Un aviso de una cámara (movimiento, persona, cruce de línea…). */
class CamEvent(
    val camera: Camera,
    val type: String,
    val kind: Kind,
    val time: Long
) {
    enum class Kind(val color: Int) {
        PERSON(Color.parseColor("#FF6B6B")),
        VEHICLE(Color.parseColor("#F5A524")),
        MOTION(Color.parseColor("#5AA9E6")),
        LINE(Color.parseColor("#FF9F43")),
        ALERT(Color.parseColor("#B084F5"))
    }

    @Volatile
    var thumb: Bitmap? = null

    /** Si no se pudo traer la foto: el motivo (para verlo en el panel). */
    @Volatile
    var thumbError: String? = null

    /** Aviso sobre la foto (por ejemplo: llegó incompleta). */
    @Volatile
    var thumbNote: String? = null
}

/**
 * Escucha los eventos de todos los equipos (un hilo por NVR/cámara, no uno por canal) de Dahua, Hikvision y ONVIF.
 * Los avisos llegan por callbacks desde hilos de fondo: quien los usa debe pasar al hilo de la interfaz.
 */
class EventHub(
    private val onEvent: (CamEvent) -> Unit,
    private val onThumb: (CamEvent) -> Unit,
    private val onStatus: (key: String, label: String, text: String) -> Unit,
    /** Devuelve una captura del vídeo en directo de esa cámara si está en pantalla, o null. */
    private val grab: (Camera) -> Bitmap? = { null }
) {
    private class Group(val key: String, val label: String, val brand: Brand, val cams: List<Camera>)

    private val lock = Any()
    private var running = false
    private var workers = ArrayList<Worker>()
    private var pool: ExecutorService? = null
    private val lastSeen = HashMap<String, Long>()

    @Volatile private var dead = false

    @Synchronized
    fun start(cameras: List<Camera>) {
        if (dead) return
        halt()
        val groups = LinkedHashMap<String, MutableList<Camera>>()
        for (c in cameras) {
            if (c.brand == Brand.RTSP || c.host.isBlank()) continue
            val key = c.brand.name + "|" + c.host + "|" + c.onvifPort + "|" + c.user + "|" + c.pass.hashCode()
            groups.getOrPut(key) { ArrayList() }.add(c)
        }
        synchronized(lock) {
            running = true
            // una foto cada vez: los NVR fallan si se piden varias a la vez
            pool = Executors.newSingleThreadExecutor()
            lastSeen.clear()
        }
        for ((key, cams) in groups) {
            val first = cams[0]
            val g = Group(key, first.brand.label + " " + first.host, first.brand, cams)
            val w = Worker(g)
            synchronized(lock) { workers.add(w) }
            w.start()
        }
    }

    @Synchronized
    fun stop() {
        dead = true
        halt()
    }

    private fun halt() {
        val list: List<Worker>
        val p: ExecutorService?
        synchronized(lock) {
            running = false
            list = ArrayList(workers)
            workers = ArrayList()
            p = pool
            pool = null
        }
        for (w in list) w.shutdown()
        p?.shutdownNow()
    }

    private fun alive(): Boolean = synchronized(lock) { running }

    // ------------------------------------------------------------------ hilo por equipo

    private inner class Worker(val g: Group) : Thread("events-" + g.label) {
        @Volatile private var stopped = false
        @Volatile private var conn: HttpURLConnection? = null
        private val snapUrls = HashMap<String, String>()
        @Volatile private var partialBytes = -1

        init { isDaemon = true }

        fun shutdown() {
            stopped = true
            val c = conn
            // cerrar el socket fuera del hilo principal (Android no permite red ahí)
            Thread { try { c?.disconnect() } catch (e: Exception) { } }.start()
            interrupt()
        }

        override fun run() {
            var delay = 3000L
            while (!stopped && alive()) {
                try {
                    onStatus(g.key, g.label, "conectando…")
                    when (g.brand) {
                        Brand.DAHUA -> runDahua()
                        Brand.HIKVISION -> runHikvision()
                        else -> runOnvif()
                    }
                    delay = 3000L
                } catch (e: InterruptedException) {
                    return
                } catch (e: Exception) {
                    if (stopped) return
                    onStatus(g.key, g.label, "sin eventos: " + (e.message ?: e.javaClass.simpleName).take(60))
                }
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    return
                }
                delay = minOf(delay * 2, 60000L)
            }
        }

        private fun base(): String = "http://" + g.cams[0].host + ":" + g.cams[0].onvifPort

        // ---------------------------------------------------------------- Dahua

        private fun runDahua() {
            val c0 = g.cams[0]
            val url = base() + "/cgi-bin/eventManager.cgi?action=attach&codes=%5BAll%5D&heartbeat=5"
            val cn = Http.connect(url, c0.user, c0.pass, 5000, 30000)
            conn = cn
            onStatus(g.key, g.label, "escuchando")
            try {
                val input = BufferedInputStream(cn.inputStream)
                while (!stopped) {
                    val line = Http.readLine(input) ?: throw IOException("conexión cerrada")
                    if (!line.startsWith("Code=")) continue
                    val f = HashMap<String, String>()
                    for (part in line.split(';')) {
                        val i = part.indexOf('=')
                        if (i > 0) f[part.substring(0, i).trim()] = part.substring(i + 1).trim()
                    }
                    if (f["action"] != "Start") continue
                    val info = dahuaType(f["Code"] ?: continue) ?: continue
                    val idx = f["index"]?.toIntOrNull() ?: continue
                    emit(idx + 1, info.first, info.second)
                }
            } finally {
                cn.disconnect()
            }
        }

        private fun dahuaType(code: String): Pair<String, CamEvent.Kind>? = when (code) {
            "VideoMotion", "MotionDetect" -> "Movimiento" to CamEvent.Kind.MOTION
            "SmartMotionHuman", "HumanDetection" -> "Persona" to CamEvent.Kind.PERSON
            "SmartMotionVehicle", "VehicleDetection" -> "Vehículo" to CamEvent.Kind.VEHICLE
            "CrossLineDetection" -> "Cruce de línea" to CamEvent.Kind.LINE
            "CrossRegionDetection" -> "Intrusión" to CamEvent.Kind.LINE
            "FaceDetection" -> "Rostro" to CamEvent.Kind.PERSON
            "LeftDetection" -> "Objeto abandonado" to CamEvent.Kind.ALERT
            "TakenAwayDetection" -> "Objeto retirado" to CamEvent.Kind.ALERT
            "WanderDetection" -> "Merodeo" to CamEvent.Kind.ALERT
            "ParkingDetection" -> "Vehículo parado" to CamEvent.Kind.VEHICLE
            "VideoBlind" -> "Cámara tapada" to CamEvent.Kind.ALERT
            "VideoLoss" -> "Pérdida de vídeo" to CamEvent.Kind.ALERT
            "VideoAbnormalDetection" -> "Imagen anómala" to CamEvent.Kind.ALERT
            else -> null
        }

        // ------------------------------------------------------------- Hikvision

        private fun runHikvision() {
            val c0 = g.cams[0]
            val cn = Http.connect(base() + "/ISAPI/Event/notification/alertStream", c0.user, c0.pass, 5000, 45000)
            conn = cn
            onStatus(g.key, g.label, "escuchando")
            try {
                val input = BufferedInputStream(cn.inputStream)
                val sb = StringBuilder()
                var inside = false
                while (!stopped) {
                    val line = Http.readLine(input) ?: throw IOException("conexión cerrada")
                    if (line.contains("<EventNotificationAlert")) {
                        sb.setLength(0)
                        inside = true
                    }
                    if (inside) {
                        sb.append(line).append('\n')
                        if (sb.length > 64 * 1024) { sb.setLength(0); inside = false }
                    }
                    if (inside && line.contains("</EventNotificationAlert>")) {
                        inside = false
                        handleHik(sb.toString())
                        sb.setLength(0)
                    }
                }
            } finally {
                cn.disconnect()
            }
        }

        private fun tag(xml: String, name: String): String? =
            Regex("<" + name + "[^>]*>([^<]*)</" + name + ">").find(xml)?.groupValues?.get(1)?.trim()

        private fun handleHik(xml: String) {
            if (!tag(xml, "eventState").equals("active", true)) return
            val type = tag(xml, "eventType") ?: return
            val info = hikType(type) ?: return
            val ch = (tag(xml, "dynChannelID") ?: tag(xml, "channelID"))?.toIntOrNull() ?: return
            emit(ch, info.first, info.second)
        }

        private fun hikType(t: String): Pair<String, CamEvent.Kind>? = when (t.lowercase()) {
            "vmd" -> "Movimiento" to CamEvent.Kind.MOTION
            "linedetection" -> "Cruce de línea" to CamEvent.Kind.LINE
            "fielddetection", "regionentrance", "regionexiting" -> "Intrusión" to CamEvent.Kind.LINE
            "facedetection" -> "Rostro" to CamEvent.Kind.PERSON
            "pir" -> "Sensor PIR" to CamEvent.Kind.MOTION
            "shelteralarm" -> "Cámara tapada" to CamEvent.Kind.ALERT
            "tamperdetection" -> "Manipulación" to CamEvent.Kind.ALERT
            "videoloss" -> "Pérdida de vídeo" to CamEvent.Kind.ALERT
            "unattendedbaggage" -> "Objeto abandonado" to CamEvent.Kind.ALERT
            "attendedbaggage" -> "Objeto retirado" to CamEvent.Kind.ALERT
            "anpr" -> "Matrícula" to CamEvent.Kind.VEHICLE
            "heartbeat", "duration", "io" -> null
            else -> t.take(24) to CamEvent.Kind.ALERT
        }

        // ------------------------------------------------------------------ ONVIF

        private fun runOnvif() {
            val c0 = g.cams[0]
            val deviceUrl = "http://" + c0.host + ":" + c0.onvifPort + "/onvif/device_service"
            val offset = Onvif.timeOffset(deviceUrl)
            val fromCaps: String? = try {
                val xml = Onvif.soap(
                    deviceUrl, Onvif.security(c0.user, c0.pass, offset),
                    "<tds:GetCapabilities><tds:Category>Events</tds:Category></tds:GetCapabilities>"
                )
                Onvif.firstText(Onvif.parse(xml), "XAddr")
            } catch (e: Exception) {
                null
            }
            val eventsUrl = fromCaps ?: ("http://" + c0.host + ":" + c0.onvifPort + "/onvif/event_service")

            val ns = "xmlns:tev=\"http://www.onvif.org/ver10/events/wsdl\""
            while (!stopped) {
                val created = Onvif.soap(
                    eventsUrl, Onvif.security(c0.user, c0.pass, offset),
                    "<tev:CreatePullPointSubscription $ns><tev:InitialTerminationTime>PT120S</tev:InitialTerminationTime></tev:CreatePullPointSubscription>"
                )
                var sub = Onvif.firstText(Onvif.parse(created), "Address")
                    ?: throw IOException("sin suscripción de eventos")
                sub = fixHost(sub, c0)
                onStatus(g.key, g.label, "escuchando")
                val until = System.currentTimeMillis() + 90000L
                while (!stopped && System.currentTimeMillis() < until) {
                    val toHdr = "<wsa:To s:mustUnderstand=\"1\" xmlns:wsa=\"http://www.w3.org/2005/08/addressing\">" +
                        Onvif.esc(sub) + "</wsa:To>"
                    val xml = Onvif.soap(
                        sub, toHdr + Onvif.security(c0.user, c0.pass, offset),
                        "<tev:PullMessages $ns><tev:Timeout>PT5S</tev:Timeout><tev:MessageLimit>20</tev:MessageLimit></tev:PullMessages>"
                    )
                    handleOnvif(xml)
                }
            }
        }

        /** Algunas cámaras devuelven su IP interna en la dirección de suscripción: se usa siempre el host configurado. */
        private fun fixHost(url: String, c: Camera): String {
            return try {
                val u = java.net.URL(url)
                val port = if (u.port > 0) u.port else c.onvifPort
                val path = u.path + (if (u.query != null) "?" + u.query else "")
                "http://" + c.host + ":" + (if (u.host == c.host) port else c.onvifPort) + path
            } catch (e: Exception) {
                url
            }
        }

        private fun handleOnvif(xml: String) {
            val doc = Onvif.parse(xml)
            val msgs = doc.getElementsByTagNameNS("*", "NotificationMessage")
            for (i in 0 until msgs.length) {
                val m = msgs.item(i) as org.w3c.dom.Element
                val topic = m.getElementsByTagNameNS("*", "Topic").item(0)?.textContent?.trim() ?: continue
                val info = onvifType(topic) ?: continue
                var active = false
                var ch = 0
                val items = m.getElementsByTagNameNS("*", "SimpleItem")
                var propertyOk = true
                val msgEls = m.getElementsByTagNameNS("*", "Message")
                for (j in 0 until msgEls.length) {
                    val me = msgEls.item(j) as org.w3c.dom.Element
                    if (me.getAttribute("PropertyOperation").equals("Initialized", true)) propertyOk = false
                }
                for (k in 0 until items.length) {
                    val el = items.item(k) as org.w3c.dom.Element
                    val name = el.getAttribute("Name")
                    val value = el.getAttribute("Value").trim()
                    if (name.equals("IsMotion", true) || name.equals("State", true) ||
                        name.startsWith("Is", true) && name.length > 2 && !name.equals("IsInside", true)
                    ) {
                        if (value.equals("true", true) || value == "1") active = true
                    }
                    if (name.contains("VideoSource", true) || name.contains("Channel", true) || name.equals("Source", true)) {
                        val n = Regex("(\\d+)\\D*$").find(value)?.groupValues?.get(1)?.toIntOrNull()
                        if (n != null && ch == 0) ch = n
                    }
                }
                if (!active || !propertyOk) continue
                emit(ch, info.first, info.second)
            }
        }

        private fun onvifType(topic: String): Pair<String, CamEvent.Kind>? {
            val t = topic.lowercase()
            return when {
                t.contains("human") || t.contains("people") || t.contains("person") -> "Persona" to CamEvent.Kind.PERSON
                t.contains("vehicle") || t.contains("car") -> "Vehículo" to CamEvent.Kind.VEHICLE
                t.contains("face") -> "Rostro" to CamEvent.Kind.PERSON
                t.contains("linedetector") || t.contains("tripwire") || t.contains("crossline") -> "Cruce de línea" to CamEvent.Kind.LINE
                t.contains("fielddetector") || t.contains("intrusion") || t.contains("regiondetector") -> "Intrusión" to CamEvent.Kind.LINE
                t.contains("tamper") || t.contains("globalscenechange") -> "Cámara tapada" to CamEvent.Kind.ALERT
                t.contains("motion") -> "Movimiento" to CamEvent.Kind.MOTION
                else -> null
            }
        }

        // ---------------------------------------------------------- común: aviso + foto

        private fun emit(channel: Int, type: String, kind: CamEvent.Kind) {
            val cam = g.cams.firstOrNull { it.channel == channel }
                ?: (if (g.brand == Brand.ONVIF && g.cams.size == 1) g.cams[0] else null)
                ?: return
            val now = System.currentTimeMillis()
            val key = cam.id + "|" + type
            synchronized(lock) {
                val prev = lastSeen[key]
                if (prev != null && now - prev < 10000L) return
                lastSeen[key] = now
            }
            val ev = CamEvent(cam, type, kind, now)
            onEvent(ev)
            val p = synchronized(lock) { pool } ?: return
            try {
                p.execute {
                    // 1) si la cámara está en pantalla, la miniatura sale del propio vídeo: sin cargar el NVR
                    val live = try { grab(cam) } catch (e: Exception) { null }
                    if (live != null) {
                        if (!stopped) {
                            ev.thumb = trimBands(live)
                            onThumb(ev)
                        }
                        return@execute
                    }
                    var err = "sin imagen"
                    // justo al saltar el evento la cámara está ocupada: se espera un poco
                    try { Thread.sleep(1200L) } catch (e: InterruptedException) { return@execute }
                    var best: Bitmap? = null
                    var bestScore = Int.MAX_VALUE
                    var bestPartial = -1
                    var attempt = 0
                    while (attempt < 3 && !stopped) {
                        var got: Bitmap? = null
                        try {
                            partialBytes = -1
                            got = snapshot(cam, attempt >= 2)
                            if (got == null) err = "imagen no válida"
                        } catch (e: InterruptedException) {
                            return@execute
                        } catch (e: Exception) {
                            err = e.message ?: e.javaClass.simpleName
                        }
                        attempt++
                        if (got != null) {
                            // algunas cámaras (H.265) devuelven fotos con una franja de color corrupta al final: se repite y se queda la mejor
                            val sc = bandScore(got)
                            if (sc < bestScore) {
                                best = got
                                bestScore = sc
                                bestPartial = partialBytes
                            }
                            if (bestScore <= 40 || attempt >= 2) break
                        }
                        if (attempt < 3) {
                            try { Thread.sleep(1500L) } catch (e: InterruptedException) { return@execute }
                        }
                    }
                    val bmp = best
                    if (!stopped) {
                        if (bmp != null) {
                            ev.thumb = bmp
                            if (bestPartial >= 0) ev.thumbNote = "incompleta " + bestPartial + " B"
                        } else {
                            ev.thumbError = err
                        }
                        onThumb(ev)
                    }
                }
            } catch (e: Exception) {
                // se está cerrando
            }
        }

        private fun snapshot(cam: Camera, allowPartial: Boolean): Bitmap? {
            val url = when (cam.brand) {
                Brand.DAHUA -> base() + "/cgi-bin/snapshot.cgi?channel=" + cam.channel
                Brand.HIKVISION -> base() + "/ISAPI/Streaming/channels/" + cam.channel + "01/picture"
                else -> onvifSnapshotUrl(cam)
            } ?: return null
            val bytes = Http.getBytes(url, cam.user, cam.pass, 3 * 1024 * 1024)
            // un JPEG completo acaba en FF D9; si no, la cámara cortó la descarga y la foto saldría con franjas raras
            if (!endsLikeJpeg(bytes)) {
                if (!allowPartial) throw java.io.IOException("foto incompleta (" + bytes.size + " B)")
                partialBytes = bytes.size
            }
            return decode(bytes)
        }

        private fun onvifSnapshotUrl(cam: Camera): String? {
            synchronized(snapUrls) { snapUrls[cam.id]?.let { return it } }
            val deviceUrl = "http://" + cam.host + ":" + cam.onvifPort + "/onvif/device_service"
            val offset = Onvif.timeOffset(deviceUrl)
            val mediaCaps: String? = try {
                val xml = Onvif.soap(
                    deviceUrl, Onvif.security(cam.user, cam.pass, offset),
                    "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>"
                )
                Onvif.firstText(Onvif.parse(xml), "XAddr")
            } catch (e: Exception) {
                null
            }
            val media = mediaCaps ?: ("http://" + cam.host + ":" + cam.onvifPort + "/onvif/media_service")
            val prof = Onvif.parse(Onvif.soap(media, Onvif.security(cam.user, cam.pass, offset), "<trt:GetProfiles/>"))
            val nodes = prof.getElementsByTagNameNS("*", "Profiles")
            var token: String? = null
            for (i in 0 until minOf(nodes.length, 24)) {
                val t = (nodes.item(i) as org.w3c.dom.Element).getAttribute("token")
                if (t.isNullOrBlank()) continue
                if (token == null) token = t
                val uri = try {
                    Onvif.firstText(
                        Onvif.parse(
                            Onvif.soap(
                                media, Onvif.security(cam.user, cam.pass, offset),
                                "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>" +
                                    Onvif.esc(t) + "</trt:ProfileToken></trt:GetStreamUri>"
                            )
                        ), "Uri"
                    )
                } catch (e: Exception) {
                    null
                }
                if (uri != null && (sameStream(uri, cam.mainUrl) || sameStream(uri, cam.subUrl))) {
                    token = t
                    break
                }
            }
            val tk = token ?: return null
            val snap = Onvif.firstText(
                Onvif.parse(
                    Onvif.soap(
                        media, Onvif.security(cam.user, cam.pass, offset),
                        "<trt:GetSnapshotUri><trt:ProfileToken>" + Onvif.esc(tk) + "</trt:ProfileToken></trt:GetSnapshotUri>"
                    )
                ), "Uri"
            ) ?: return null
            val fixed = fixHost(snap, cam)
            synchronized(snapUrls) { snapUrls[cam.id] = fixed }
            return fixed
        }

        private fun sameStream(a: String, b: String): Boolean {
            if (a.isBlank() || b.isBlank()) return false
            fun norm(s: String) = s.substringAfter("://").substringAfter('@').substringBefore('?').trimEnd('/')
            return norm(a) == norm(b)
        }
    }

    /** Cuánto se parece la franja inferior de la foto a lo que tiene justo encima (alto = franja corrupta). */
    private fun bandScore(b: Bitmap): Int {
        val h = b.height
        val w = b.width
        if (h < 40 || w < 40) return 0
        val strip = maxOf(3, h / 25)
        fun mean(from: Int, to: Int): IntArray {
            var r = 0L
            var g = 0L
            var bl = 0L
            var n = 0
            var y = from
            while (y < to) {
                var x = 0
                while (x < w) {
                    val c = b.getPixel(x, y)
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    bl += c and 0xFF
                    n++
                    x += 4
                }
                y += 2
            }
            if (n == 0) return intArrayOf(0, 0, 0)
            return intArrayOf((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }
        val bottom = mean(h - strip, h)
        val above = mean(h - 3 * strip, h - 2 * strip)
        return Math.abs(bottom[0] - above[0]) + Math.abs(bottom[1] - above[1]) + Math.abs(bottom[2] - above[2])
    }

    private fun endsLikeJpeg(b: ByteArray): Boolean {
        var i = b.size - 1
        var skipped = 0
        while (i > 0 && b[i].toInt() == 0 && skipped < 16) { i--; skipped++ }
        return i > 0 && (b[i].toInt() and 0xFF) == 0xD9 && (b[i - 1].toInt() and 0xFF) == 0xFF
    }

    /** Reduce la foto a unos 480 px de ancho para que ocupe poco. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 8000 || o.outHeight > 8000) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= 480) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return null
        return trimBands(bmp)
    }

    /**
     * Quita los bordes de color liso (relleno verde, blanco, etc.) que dejan algunas cámaras/decodificadores
     * cuando la resolución no es múltiplo de 16. Recorta como mucho un 15 % por lado.
     */
    private fun trimBands(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        if (w < 64 || h < 64) return b
        fun px(x: Int, y: Int): Int = b.getPixel(x, y)
        fun dist(a: Int, c: Int): Int =
            Math.abs(((a shr 16) and 0xFF) - ((c shr 16) and 0xFF)) +
                Math.abs(((a shr 8) and 0xFF) - ((c shr 8) and 0xFF)) +
                Math.abs((a and 0xFF) - (c and 0xFF))
        // columna x: ¿de un solo color (casi)? y cuál
        fun colColor(x: Int): Int {
            val first = px(x, h / 8)
            var y = h / 8
            while (y < h - h / 8) {
                if (dist(px(x, y), first) > 40) return -1
                y += 6
            }
            return first
        }
        fun rowColor(y: Int): Int {
            val first = px(w / 8, y)
            var x = w / 8
            while (x < w - w / 8) {
                if (dist(px(x, y), first) > 40) return -1
                x += 6
            }
            return first
        }
        var right = 0
        while (right < w * 15 / 100) {
            val c = colColor(w - 1 - right)
            if (c == -1) break
            if (dist(c, px(maxOf(0, w - 1 - right - w / 10), h / 2)) < 90) break
            right++
        }
        var bottom = 0
        while (bottom < h * 15 / 100) {
            val c = rowColor(h - 1 - bottom)
            if (c == -1) break
            if (dist(c, px(w / 2, maxOf(0, h - 1 - bottom - h / 10))) < 90) break
            bottom++
        }
        if (right == 0 && bottom == 0) return b
        return try {
            Bitmap.createBitmap(b, 0, 0, w - right, h - bottom)
        } catch (e: Exception) {
            b
        }
    }
}
