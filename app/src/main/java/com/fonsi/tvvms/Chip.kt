package com.fonsi.tvvms

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/** Botón redondeado para mando: dorado al tener el foco, borde dorado si está activo. */
class Chip(context: Context, label: String) : TextView(context) {

    private val gold = Color.parseColor("#C9A04B")
    var filled: Boolean = false
        set(value) {
            field = value
            refresh()
        }
    var active: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    init {
        isFocusable = true
        isClickable = true
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(42)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(22).toFloat()
        if (isFocused) {
            bg.setColor(gold)
            setTextColor(Color.parseColor("#14181F"))
        } else if (active) {
            bg.setColor(Color.parseColor("#262B33"))
            bg.setStroke(dp(2), gold)
            setTextColor(gold)
        } else if (filled) {
            bg.setColor(Color.parseColor("#2A2216"))
            bg.setStroke(dp(1), gold)
            setTextColor(gold)
        } else {
            bg.setColor(Color.parseColor("#1B2027"))
            setTextColor(Color.parseColor("#D5DAE1"))
        }
        background = bg
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refresh()
        val s = if (gainFocus) 1.08f else 1f
        scaleX = s
        scaleY = s
    }
}
