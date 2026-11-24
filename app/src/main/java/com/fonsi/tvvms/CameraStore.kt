package com.fonsi.tvvms

import android.content.Context
import org.json.JSONArray

/** Lista de cámaras (con usuarios y contraseñas) guardada CIFRADA con una clave del Android Keystore. */
class CameraStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vms", Context.MODE_PRIVATE)

    /** Se avisa aquí de fallos de cifrado/lectura para mostrarlos al usuario. */
    var onError: ((String) -> Unit)? = null

    private fun parse(raw: String): List<Camera> {
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

    /** Si quedara una lista antigua sin cifrar, la cifra y borra el original. */
    private fun migrateLegacy() {
        val legacy = prefs.getString("cameras", null) ?: return
        if (save(parse(legacy))) {
            prefs.edit().remove("cameras").apply()
        }
    }

    fun load(): List<Camera> {
        migrateLegacy()
        val enc = prefs.getString("cameras_enc", null) ?: return emptyList()
        val raw = Vault.decrypt(enc)
        if (raw == null) {
            onError?.invoke("No se pudieron leer las cámaras guardadas (clave de cifrado no válida). Vuelve a añadirlas.")
            return emptyList()
        }
        return parse(raw)
    }

    fun save(list: List<Camera>): Boolean {
        return try {
            val arr = JSONArray()
            for (c in list) arr.put(c.toJson())
            prefs.edit().putString("cameras_enc", Vault.encrypt(arr.toString())).apply()
            true
        } catch (e: Exception) {
            onError?.invoke("Este dispositivo no pudo cifrar los datos. No se ha guardado nada.")
            false
        }
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

    fun removeMany(ids: Set<String>) {
        save(load().filter { it.id !in ids })
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

    /** Filtro del panel de eventos: 0 = todo, 1 = personas, 2 = vehículos. */
    var eventFilter: Int
        get() = prefs.getInt("eventFilter", 0)
        set(v) {
            prefs.edit().putInt("eventFilter", v).apply()
        }

    var eventsOpen: Boolean
        get() = prefs.getBoolean("eventsOpen", false)
        set(v) {
            prefs.edit().putBoolean("eventsOpen", v).apply()
        }
}
