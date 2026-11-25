package com.fonsi.tvvms

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Panel lateral con los últimos eventos de las cámaras: miniatura de la detección, tipo, cámara y hora. */
class EventPanel(context: Context) : LinearLayout(context) {

    var onOpen: ((Camera) -> Unit)? = null

    private val gold = Color.parseColor("#C9A04B")
    private val muted = Color.parseColor("#8A93A0")
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val list = LinearLayout(context)
    private val empty = TextView(context)
    private val footer = TextView(context)
    private class Thumb(val img: ImageView, val label: TextView)

    private val rows = HashMap<CamEvent, Thumb>()
    private val order = ArrayList<CamEvent>()
    private val statuses = LinkedHashMap<String, String>()
    private val maxEvents = 50

    /** 0 = todo, 1 = solo personas, 2 = solo vehículos. */
    var filter = 0
        private set
    var onFilterChange: ((Int) -> Unit)? = null
    private val filterChip = Chip(context, "Todo")
    private val rowViews = HashMap<CamEvent, View>()


    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0F1217"))
        setPadding(dp(10), dp(8), dp(10), dp(6))

        val title = TextView(context)
        title.text = "EVENTOS"
        title.setTextColor(gold)
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setPadding(dp(4), 0, 0, 0)
        filterChip.textSize = 12f
        filterChip.setPadding(dp(12), dp(4), dp(12), dp(4))
        filterChip.setOnClickListener {
            val next = (filter + 1) % 3
            applyFilter(next)
            onFilterChange?.invoke(next)
        }
        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.clipChildren = false
        head.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(filterChip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val hlp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.setMargins(0, 0, 0, dp(8))
        addView(head, hlp)
        clipChildren = false

        list.orientation = VERTICAL
        empty.text = "Esperando eventos…\nMovimiento, personas, vehículos o cruce de línea aparecerán aquí."
        empty.setTextColor(muted)
        empty.textSize = 13f
        empty.setPadding(dp(4), dp(8), dp(4), dp(8))
        list.addView(empty)

        val scroll = ScrollView(context)
        scroll.isFillViewport = false
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer.setTextColor(muted)
        footer.textSize = 11f
        footer.setPadding(dp(4), dp(6), dp(4), 0)
        addView(footer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun colorOf(kind: CamEvent.Kind): Int = kind.color

    fun matches(ev: CamEvent): Boolean = when (filter) {
        1 -> ev.kind == CamEvent.Kind.PERSON
        2 -> ev.kind == CamEvent.Kind.VEHICLE
        else -> true
    }

    fun applyFilter(f: Int) {
        filter = f
        filterChip.text = when (f) { 1 -> "Personas"; 2 -> "Vehículos"; else -> "Todo" }
        for ((ev, v) in rowViews) v.visibility = if (matches(ev)) View.VISIBLE else View.GONE
    }

    fun add(ev: CamEvent) {
        if (empty.parent != null) list.removeView(empty)
        val img = ImageView(context)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        val label = TextView(context)
        label.setTextColor(muted)
        label.textSize = 9f
        label.gravity = Gravity.CENTER
        label.maxLines = 4
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(dp(3), 0, dp(3), 0)
        label.text = "…"
        val thumb = FrameLayout(context)
        thumb.setBackgroundColor(Color.parseColor("#1B2027"))
        thumb.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        thumb.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val t = Thumb(img, label)
        rows[ev] = t
        refreshThumb(ev)

        val type = TextView(context)
        type.text = ev.type
        type.setTextColor(colorOf(ev.kind))
        type.textSize = 16f
        type.typeface = Typeface.DEFAULT_BOLD
        val cam = TextView(context)
        cam.text = ev.camera.name
        cam.setTextColor(Color.WHITE)
        cam.textSize = 13f
        cam.maxLines = 1
        cam.ellipsize = TextUtils.TruncateAt.END
        val time = TextView(context)
        time.text = timeFmt.format(Date(ev.time))
        time.setTextColor(muted)
        time.textSize = 12f

        val texts = LinearLayout(context)
        texts.orientation = VERTICAL
        texts.gravity = Gravity.CENTER_VERTICAL
        texts.addView(type)
        texts.addView(cam)
        texts.addView(time)

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(dp(6), dp(6), dp(6), dp(6))
        paint(row, false)
        row.setOnFocusChangeListener { _, has -> paint(row, has) }
        row.setOnClickListener { onOpen?.invoke(ev.camera) }
        row.addView(thumb, LayoutParams(dp(96), dp(54)))
        val tlp = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.setMargins(dp(8), 0, 0, 0)
        row.addView(texts, tlp)

        val rlp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.setMargins(0, 0, 0, dp(6))
        list.addView(row, 0, rlp)
        rowViews[ev] = row
        row.visibility = if (matches(ev)) View.VISIBLE else View.GONE

        order.add(0, ev)
        while (order.size > maxEvents) {
            val old = order.removeAt(order.size - 1)
            rows.remove(old)
            rowViews.remove(old)
            list.removeViewAt(list.childCount - 1)
        }
    }

    fun refreshThumb(ev: CamEvent) {
        val t = rows[ev] ?: return
        val b = ev.thumb
        if (b != null) {
            t.img.setImageBitmap(b)
            val note = ev.thumbNote
            if (note == null) {
                t.label.visibility = View.GONE
            } else {
                t.label.text = note
                t.label.setTextColor(Color.parseColor("#FFB020"))
                t.label.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        } else {
            val err = ev.thumbError
            t.label.text = if (err == null) "…" else "Sin foto\n" + err.take(40)
        }
    }

    fun setStatus(key: String, label: String, text: String) {
        statuses[key] = label + ": " + text
        footer.text = statuses.values.joinToString("\n")
    }

    /** El estado de conexión de abajo solo se ve con el botón "i" activado. */
    fun setInfoVisible(on: Boolean) {
        footer.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun clearStatus() {
        statuses.clear()
        footer.text = ""
    }

    private fun paint(row: LinearLayout, focused: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        bg.setColor(if (focused) Color.parseColor("#2A2216") else Color.parseColor("#171B21"))
        if (focused) bg.setStroke(dp(2), gold)
        row.background = bg
    }
}
