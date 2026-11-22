package com.fonsi.tvvms

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/** Todos los diálogos de gestión de cámaras (lista, alta, edición, NVR). */
class CameraDialogs(
    private val act: Activity,
    private val store: CameraStore,
    private val onChanged: () -> Unit
) {

    private class Row(val box: LinearLayout, val edit: EditText) {
        fun text(): String = edit.text.toString().trim()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
    }

    private fun addRow(parent: LinearLayout, label: String, value: String, type: Int): Row {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val tv = TextView(act)
        tv.text = label
        tv.textSize = 12f
        val et = EditText(act)
        et.setText(value)
        et.setSingleLine(true)
        et.inputType = type
        box.addView(tv)
        box.addView(et)
        parent.addView(box)
        return Row(box, et)
    }

    private fun formRoot(): LinearLayout {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(8), dp(24), dp(8))
        return root
    }

    private fun brandSpinner(options: List<Brand>, selected: Brand): Spinner {
        val sp = Spinner(act)
        sp.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
        val idx = options.indexOf(selected)
        sp.setSelection(if (idx >= 0) idx else 0)
        return sp
    }

    fun showList() {
        val cams = store.load()
        val items = ArrayList<String>()
        items.add("➕  Añadir cámara")
        items.add("➕  Añadir NVR / DVR (varios canales)")
        for (c in cams) {
            items.add(c.name + "   ·   " + c.brand.label + "   " + c.host)
        }
        AlertDialog.Builder(act)
            .setTitle("Cámaras (" + cams.size + ")")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showForm(null)
                    1 -> showNvrForm()
                    else -> showActions(cams[which - 2])
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showActions(c: Camera) {
        val opts = arrayOf("Editar", "Mover antes", "Mover después", "Eliminar")
        AlertDialog.Builder(act)
            .setTitle(c.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showForm(c)
                    1 -> {
                        store.move(c.id, -1)
                        onChanged()
                        showList()
                    }
                    2 -> {
                        store.move(c.id, 1)
                        onChanged()
                        showList()
                    }
                    else -> confirmDelete(c)
                }
            }
            .setNegativeButton("Atrás") { _, _ -> showList() }
            .show()
    }

    private fun confirmDelete(c: Camera) {
        AlertDialog.Builder(act)
            .setMessage("¿Eliminar " + c.name + "?")
            .setPositiveButton("Eliminar") { _, _ ->
                store.remove(c.id)
                onChanged()
                showList()
            }
            .setNegativeButton("Cancelar") { _, _ -> showList() }
            .show()
    }

    private fun showForm(existing: Camera?) {
        val root = formRoot()
        val brands = Brand.values().toList()

        val brandLabel = TextView(act)
        brandLabel.text = "Marca / tipo"
        brandLabel.textSize = 12f
        root.addView(brandLabel)
        val spinner = brandSpinner(brands, existing?.brand ?: Brand.DAHUA)
        root.addView(spinner)

        val text = InputType.TYPE_CLASS_TEXT
        val number = InputType.TYPE_CLASS_NUMBER
        val visiblePass = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        val name = addRow(root, "Nombre", existing?.name ?: "", text)
        val host = addRow(root, "IP o dirección", existing?.host ?: "", uri)
        val rtspPort = addRow(root, "Puerto RTSP", (existing?.port ?: 554).toString(), number)
        val onvifPort = addRow(root, "Puerto ONVIF (HTTP)", (existing?.onvifPort ?: 80).toString(), number)
        val user = addRow(root, "Usuario", existing?.user ?: "admin", text)
        val pass = addRow(root, "Contraseña", existing?.pass ?: "", visiblePass)
        val channel = addRow(root, "Canal (1, 2, 3…)", (existing?.channel ?: 1).toString(), number)
        val mainUrl = addRow(root, "URL RTSP principal", existing?.mainUrl ?: "", uri)
        val subUrl = addRow(root, "URL RTSP secundaria (opcional)", existing?.subUrl ?: "", uri)

        fun applyVisibility() {
            val b = brands[spinner.selectedItemPosition]
            val nativeBrand = b == Brand.DAHUA || b == Brand.HIKVISION
            host.box.visibility = if (b != Brand.RTSP) View.VISIBLE else View.GONE
            rtspPort.box.visibility = if (nativeBrand) View.VISIBLE else View.GONE
            channel.box.visibility = if (nativeBrand) View.VISIBLE else View.GONE
            onvifPort.box.visibility = if (b == Brand.ONVIF) View.VISIBLE else View.GONE
            mainUrl.box.visibility = if (b == Brand.RTSP) View.VISIBLE else View.GONE
            subUrl.box.visibility = if (b == Brand.RTSP) View.VISIBLE else View.GONE
        }
        applyVisibility()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyVisibility()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val scroll = ScrollView(act)
        scroll.addView(root)

        val dlg = AlertDialog.Builder(act)
            .setTitle(if (existing == null) "Añadir cámara" else "Editar cámara")
            .setView(scroll)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar") { _, _ -> showList() }
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val brand = brands[spinner.selectedItemPosition]
                if (brand != Brand.RTSP && host.text().isEmpty()) {
                    toast("Falta la IP")
                    return@setOnClickListener
                }
                if (brand == Brand.RTSP && mainUrl.text().isEmpty()) {
                    toast("Falta la URL RTSP principal")
                    return@setOnClickListener
                }
                val cam = Camera(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.text().ifEmpty { "Cámara" },
                    brand = brand,
                    host = host.text(),
                    port = rtspPort.text().toIntOrNull() ?: 554,
                    user = user.text(),
                    pass = pass.edit.text.toString(),
                    channel = channel.text().toIntOrNull() ?: 1,
                    onvifPort = onvifPort.text().toIntOrNull() ?: 80,
                    mainUrl = mainUrl.text(),
                    subUrl = subUrl.text()
                )
                if (brand == Brand.ONVIF) {
                    resolveOnvifAndSave(cam, existing != null, dlg)
                } else {
                    saveCamera(cam, existing != null)
                    dlg.dismiss()
                    showList()
                }
            }
        }
        dlg.show()
    }

    private fun saveCamera(cam: Camera, isEdit: Boolean) {
        if (isEdit) store.update(cam) else store.add(cam)
        onChanged()
    }

    private fun resolveOnvifAndSave(cam: Camera, isEdit: Boolean, form: AlertDialog) {
        val progress = AlertDialog.Builder(act)
            .setMessage("Consultando la cámara por ONVIF…")
            .setCancelable(false)
            .create()
        progress.show()
        Thread {
            val result = runCatching { Onvif.resolve(cam.host, cam.onvifPort, cam.user, cam.pass) }
            act.runOnUiThread {
                progress.dismiss()
                val streams = result.getOrNull()
                if (streams != null) {
                    saveCamera(cam.copy(mainUrl = streams.main, subUrl = streams.sub), isEdit)
                    form.dismiss()
                    showList()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "error desconocido"
                    toast("No se pudo leer la cámara por ONVIF: $msg")
                }
            }
        }.start()
    }

    private fun showNvrForm() {
        val root = formRoot()
        val brands = listOf(Brand.DAHUA, Brand.HIKVISION)

        val brandLabel = TextView(act)
        brandLabel.text = "Marca del NVR / DVR"
        brandLabel.textSize = 12f
        root.addView(brandLabel)
        val spinner = brandSpinner(brands, Brand.DAHUA)
        root.addView(spinner)

        val text = InputType.TYPE_CLASS_TEXT
        val number = InputType.TYPE_CLASS_NUMBER
        val visiblePass = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        val baseName = addRow(root, "Nombre base (se añade el nº de canal)", "Cámara", text)
        val host = addRow(root, "IP del NVR / DVR", "", uri)
        val rtspPort = addRow(root, "Puerto RTSP", "554", number)
        val user = addRow(root, "Usuario", "admin", text)
        val pass = addRow(root, "Contraseña", "", visiblePass)
        val from = addRow(root, "Primer canal", "1", number)
        val to = addRow(root, "Último canal", "16", number)

        val scroll = ScrollView(act)
        scroll.addView(root)

        val dlg = AlertDialog.Builder(act)
            .setTitle("Añadir NVR / DVR")
            .setView(scroll)
            .setPositiveButton("Crear cámaras", null)
            .setNegativeButton("Cancelar") { _, _ -> showList() }
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (host.text().isEmpty()) {
                    toast("Falta la IP")
                    return@setOnClickListener
                }
                val first = from.text().toIntOrNull() ?: 1
                val last = to.text().toIntOrNull() ?: first
                if (first < 1 || last < first || last - first >= 64) {
                    toast("Revisa los canales (máximo 64 de una vez)")
                    return@setOnClickListener
                }
                val brand = brands[spinner.selectedItemPosition]
                val list = ArrayList<Camera>()
                for (ch in first..last) {
                    list.add(
                        Camera(
                            id = UUID.randomUUID().toString(),
                            name = baseName.text().ifEmpty { "Cámara" } + " " + ch,
                            brand = brand,
                            host = host.text(),
                            port = rtspPort.text().toIntOrNull() ?: 554,
                            user = user.text(),
                            pass = pass.edit.text.toString(),
                            channel = ch,
                            onvifPort = 80,
                            mainUrl = "",
                            subUrl = ""
                        )
                    )
                }
                store.addAll(list)
                onChanged()
                dlg.dismiss()
                showList()
            }
        }
        dlg.show()
    }
}
