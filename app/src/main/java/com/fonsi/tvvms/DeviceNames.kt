package com.fonsi.tvvms

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Nombres que cada cámara o canal del NVR tiene configurados en el propio equipo.
 * Se piden en segundo plano; mientras tanto (o si no hay respuesta) se usa el nombre de la app.
 */
object DeviceNames {
    private val map = ConcurrentHashMap<String, String>()
    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var running = false

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("vms_names", Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) if (v is String && v.isNotBlank()) map[k] = v
    }

    fun get(id: String): String? = map[id]

    /** Pide los nombres (hilo de fondo). Avisa en [onChanged] solo si alguno ha cambiado. */
    fun refresh(cams: List<Camera>, onChanged: () -> Unit) {
        if (running) return
        running = true
        val list = ArrayList(cams)
        Thread {
            var changed = false
            try {
                val groups = list.filter { it.brand == Brand.DAHUA || it.brand == Brand.HIKVISION }
                    .groupBy { it.brand.name + "|" + it.host + "|" + it.onvifPort + "|" + it.user + "|" + it.pass.hashCode() }
                for ((_, g) in groups) {
                    val c0 = g[0]
                    val names = try {
                        if (c0.brand == Brand.DAHUA) dahua(c0) else hikvision(c0)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    if (names.isEmpty()) continue
                    for (c in g) {
                        val n = clean(names[c.channel] ?: continue)
                        if (n.isEmpty()) continue
                        if (map[c.id] != n) {
                            map[c.id] = n
                            prefs?.edit()?.putString(c.id, n)?.apply()
                            changed = true
                        }
                    }
                }
                // cámaras borradas: fuera de la caché
                val ids = list.map { it.id }.toSet()
                for (k in map.keys.toList()) if (k !in ids) {
                    map.remove(k)
                    prefs?.edit()?.remove(k)?.apply()
                }
            } finally {
                running = false
            }
            if (changed) onChanged()
        }.start()
    }

    private fun clean(s: String): String {
        val t = s.replace(Regex("[\\p{Cntrl}]"), "").trim()
        return if (t.length > 40) t.substring(0, 40) else t
    }

    /** Canal (empezando en 1) -> nombre. */
    private fun dahua(c: Camera): Map<Int, String> {
        val url = "http://" + c.host + ":" + c.onvifPort + "/cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle"
        val text = String(Http.getBytes(url, c.user, c.pass, 100_000, 6000), Charsets.UTF_8)
        val out = HashMap<Int, String>()
        val re = Regex("ChannelTitle\\[(\\d+)]\\.Name=(.*)")
        for (line in text.lineSequence()) {
            val m = re.find(line.trim()) ?: continue
            out[m.groupValues[1].toInt() + 1] = m.groupValues[2].trim()
        }
        return out
    }

    private fun hikvision(c: Camera): Map<Int, String> {
        val base = "http://" + c.host + ":" + c.onvifPort
        val paths = listOf("/ISAPI/ContentMgmt/InputProxy/channels", "/ISAPI/System/Video/inputs/channels")
        for (p in paths) {
            val text = try {
                String(Http.getBytes(base + p, c.user, c.pass, 200_000, 6000), Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            val out = HashMap<Int, String>()
            val block = Regex("<(InputProxyChannel|VideoInputChannel)\\b.*?</\\1>", RegexOption.DOT_MATCHES_ALL)
            for (b in block.findAll(text)) {
                val s = b.value
                val id = Regex("<id>(\\d+)</id>").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val name = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1) ?: continue
                out[id] = unescape(name)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyMap()
    }

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
