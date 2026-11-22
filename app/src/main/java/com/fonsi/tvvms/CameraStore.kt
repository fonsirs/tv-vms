package com.fonsi.tvvms

import android.content.Context
import org.json.JSONArray

class CameraStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vms", Context.MODE_PRIVATE)

    fun load(): List<Camera> {
        val raw = prefs.getString("cameras", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Camera>()
            for (i in 0 until arr.length()) {
                out.add(Camera.fromJson(arr.getJSONObject(i)))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Camera>) {
        val arr = JSONArray()
        for (c in list) arr.put(c.toJson())
        prefs.edit().putString("cameras", arr.toString()).apply()
    }

    fun add(c: Camera) {
        val l = ArrayList(load())
        l.add(c)
        save(l)
    }

    fun addAll(cams: List<Camera>) {
        val l = ArrayList(load())
        l.addAll(cams)
        save(l)
    }

    fun update(c: Camera) {
        save(load().map { if (it.id == c.id) c else it })
    }

    fun remove(id: String) {
        save(load().filter { it.id != id })
    }

    fun move(id: String, delta: Int) {
        val l = ArrayList(load())
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = i + delta
        if (j < 0 || j >= l.size) return
        val tmp = l[i]
        l[i] = l[j]
        l[j] = tmp
        save(l)
    }

    var gridSize: Int
        get() = prefs.getInt("grid", 4)
        set(v) {
            prefs.edit().putInt("grid", v).apply()
        }

    var hd: Boolean
        get() = prefs.getBoolean("hd", false)
        set(v) {
            prefs.edit().putBoolean("hd", v).apply()
        }

    var auto: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) {
            prefs.edit().putBoolean("auto", v).apply()
        }
}
