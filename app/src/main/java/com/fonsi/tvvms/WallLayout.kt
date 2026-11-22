package com.fonsi.tvvms

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Reparte sus hijos en una cuadrícula de cols x rows ocupando todo el espacio. */
class WallLayout(context: Context) : ViewGroup(context) {

    var cols = 2
    var rows = 2
    private val gap = (5 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cw = width / cols
        val ch = height / rows
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(maxOf(cw - 2 * gap, 0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxOf(ch - 2 * gap, 0), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        for (i in 0 until childCount) {
            val child: View = getChildAt(i)
            val col = i % cols
            val row = i / cols
            val left = col * cw + gap
            val top = row * ch + gap
            child.layout(left, top, left + cw - 2 * gap, top + ch - 2 * gap)
        }
    }
}
