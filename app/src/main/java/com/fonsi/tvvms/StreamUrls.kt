package com.fonsi.tvvms

import android.net.Uri

/** Construye la URL RTSP de cada marca. main = flujo principal (HD), si no = flujo secundario (ligero). */
object StreamUrls {

    fun url(c: Camera, main: Boolean): String {
        return when (c.brand) {
            Brand.DAHUA ->
                "rtsp://" + auth(c) + c.host + ":" + c.port +
                    "/cam/realmonitor?channel=" + c.channel + "&subtype=" + (if (main) 0 else 1)

            Brand.HIKVISION ->
                "rtsp://" + auth(c) + c.host + ":" + c.port +
                    "/Streaming/Channels/" + c.channel + "0" + (if (main) 1 else 2)

            Brand.ONVIF, Brand.RTSP -> {
                val chosen = if (main || c.subUrl.isBlank()) c.mainUrl else c.subUrl
                withAuth(chosen, c)
            }
        }
    }

    private fun auth(c: Camera): String {
        if (c.user.isBlank()) return ""
        return Uri.encode(c.user) + ":" + Uri.encode(c.pass) + "@"
    }

    private fun withAuth(url: String, c: Camera): String {
        if (url.isBlank() || c.user.isBlank()) return url
        val scheme = "rtsp://"
        if (!url.startsWith(scheme, ignoreCase = true)) return url
        val rest = url.substring(scheme.length)
        val hostPart = rest.substringBefore('/')
        if (hostPart.contains('@')) return url
        return scheme + auth(c) + rest
    }
}
