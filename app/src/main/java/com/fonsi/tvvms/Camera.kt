package com.fonsi.tvvms

import org.json.JSONObject

enum class Brand(val label: String) {
    DAHUA("Dahua"),
    HIKVISION("Hikvision"),
    ONVIF("ONVIF"),
    RTSP("URL RTSP")
}

data class Camera(
    val id: String,
    val name: String,
    val brand: Brand,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val channel: Int,
    val onvifPort: Int,
    val mainUrl: String,
    val subUrl: String
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("brand", brand.name)
        o.put("host", host)
        o.put("port", port)
        o.put("user", user)
        o.put("pass", pass)
        o.put("channel", channel)
        o.put("onvifPort", onvifPort)
        o.put("mainUrl", mainUrl)
        o.put("subUrl", subUrl)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Camera {
            val brand = try {
                Brand.valueOf(o.optString("brand", "DAHUA"))
            } catch (e: Exception) {
                Brand.DAHUA
            }
            return Camera(
                id = o.getString("id"),
                name = o.optString("name", "Cámara"),
                brand = brand,
                host = o.optString("host", ""),
                port = o.optInt("port", 554),
                user = o.optString("user", ""),
                pass = o.optString("pass", ""),
                channel = o.optInt("channel", 1),
                onvifPort = o.optInt("onvifPort", 80),
                mainUrl = o.optString("mainUrl", ""),
                subUrl = o.optString("subUrl", "")
            )
        }
    }
}
