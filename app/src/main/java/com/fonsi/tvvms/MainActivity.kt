package com.fonsi.tvvms

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val gold = Color.parseColor("#D4AF37")
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var store: CameraStore
    private lateinit var dialogs: CameraDialogs
    private lateinit var bar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var pageLabel: TextView
    private lateinit var btn4: Button
    private lateinit var btn8: Button
    private lateinit var btn16: Button
    private lateinit var btnAuto: Button
    private lateinit var btnHd: Button

    private var cameras: List<Camera> = emptyList()
    private var wall: WallLayout? = null
    private val tiles = ArrayList<TileView>()
    private var page = 0
    private var fullIndex = -1

    private val hideBarRunnable = Runnable { hideBarNow() }
    private val autoRunnable = object : Runnable {
        override fun run() {
            if (fullIndex < 0 && pageCount() > 1) changePage(1)
            handler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = CameraStore(this)
        dialogs = CameraDialogs(this, store) { onCamerasChanged() }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        cameras = store.load()
        fullIndex = -1
        renderGrid()
        showBar()
        restartAuto()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        stopTiles()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun barButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addToBar(v: View) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), dp(2), dp(4), dp(2))
        bar.addView(v, lp)
    }

    private fun buildUi() {
        val rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setBackgroundColor(Color.BLACK)

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#FF14181F"))
        bar.setPadding(dp(8), dp(2), dp(8), dp(2))

        btn4 = barButton("4") { setGrid(4) }
        btn8 = barButton("8") { setGrid(8) }
        btn16 = barButton("16") { setGrid(16) }
        val prev = barButton("◀") { changePage(-1) }
        pageLabel = TextView(this)
        pageLabel.setTextColor(Color.WHITE)
        pageLabel.textSize = 14f
        pageLabel.setPadding(dp(8), 0, dp(8), 0)
        val next = barButton("▶") { changePage(1) }
        btnAuto = barButton("") {
            store.auto = !store.auto
            updateBar()
            restartAuto()
        }
        btnHd = barButton("") {
            store.hd = !store.hd
            updateBar()
            renderGrid()
        }
        val camsBtn = barButton("Cámaras") { dialogs.showList() }

        addToBar(btn4)
        addToBar(btn8)
        addToBar(btn16)
        addToBar(prev)
        addToBar(pageLabel)
        addToBar(next)
        addToBar(btnAuto)
        addToBar(btnHd)
        addToBar(camsBtn)

        content = FrameLayout(this)

        rootLayout.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootLayout.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootLayout)
        updateBar()
    }

    private fun updateBar() {
        val g = store.gridSize
        btn4.setTextColor(if (g == 4) gold else Color.WHITE)
        btn8.setTextColor(if (g == 8) gold else Color.WHITE)
        btn16.setTextColor(if (g == 16) gold else Color.WHITE)
        btnAuto.text = if (store.auto) "Auto: SÍ" else "Auto: NO"
        btnHd.text = if (store.hd) "Flujo: HD" else "Flujo: SD"
    }

    // ------------------------------------------------------------- Rejilla

    private fun pageCount(): Int {
        val size = store.gridSize
        return maxOf(1, (cameras.size + size - 1) / size)
    }

    private fun setGrid(n: Int) {
        store.gridSize = n
        page = 0
        updateBar()
        renderGrid()
    }

    private fun changePage(delta: Int) {
        val pages = pageCount()
        if (pages <= 1) return
        page = (page + delta + pages) % pages
        renderGrid()
    }

    private fun onCamerasChanged() {
        cameras = store.load()
        fullIndex = -1
        renderGrid()
    }

    private fun stopTiles() {
        for (t in tiles) t.stop()
        tiles.clear()
    }

    private fun renderGrid() {
        stopTiles()
        content.removeAllViews()
        wall = null

        val size = store.gridSize
        val cols = if (size == 4) 2 else 4
        val rows = if (size == 16) 4 else 2
        val pages = pageCount()
        if (page >= pages) page = 0

        if (cameras.isEmpty()) {
            val hint = TextView(this)
            hint.text = "No hay cámaras todavía.\n\nPulsa ARRIBA en el mando y elige «Cámaras» → «Añadir cámara» o «Añadir NVR / DVR»."
            hint.setTextColor(Color.WHITE)
            hint.textSize = 20f
            hint.gravity = Gravity.CENTER
            content.addView(
                hint,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            pageLabel.text = "0 cámaras"
            return
        }

        val w = WallLayout(this)
        w.cols = cols
        w.rows = rows
        val from = page * size
        val to = minOf(cameras.size, from + size)
        for (i in from until to) {
            val cam = cameras[i]
            val tile = TileView(this, cam, store.hd)
            tile.setOnClickListener {
                showFull(cameras.indexOfFirst { it.id == cam.id })
            }
            w.addView(tile)
            tiles.add(tile)
        }
        wall = w
        content.addView(
            w,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        for (t in tiles) t.start()
        pageLabel.text = "Pág " + (page + 1) + "/" + pages + " · " + cameras.size + " cám."
        if (bar.visibility != View.VISIBLE) tiles.firstOrNull()?.requestFocus()
    }

    // -------------------------------------------------------- Pantalla completa

    private fun showFull(index: Int) {
        if (cameras.isEmpty() || index < 0) return
        val n = cameras.size
        fullIndex = ((index % n) + n) % n
        stopTiles()
        content.removeAllViews()
        wall = null
        val tile = TileView(this, cameras[fullIndex], true)
        tile.isFocusable = false
        tile.isClickable = false
        tile.setAudio(true)
        content.addView(
            tile,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        tiles.add(tile)
        tile.start()
        handler.removeCallbacks(hideBarRunnable)
        bar.visibility = View.GONE
    }

    private fun exitFull() {
        val idx = fullIndex
        fullIndex = -1
        if (idx >= 0) page = idx / store.gridSize
        renderGrid()
        if (idx >= 0) {
            val k = idx % store.gridSize
            if (k < tiles.size) tiles[k].requestFocus()
        }
    }

    // ------------------------------------------------------------ Barra superior

    private fun showBar() {
        bar.visibility = View.VISIBLE
        btn4.requestFocus()
        kickBar()
    }

    private fun kickBar() {
        handler.removeCallbacks(hideBarRunnable)
        if (bar.visibility == View.VISIBLE && fullIndex < 0) {
            handler.postDelayed(hideBarRunnable, 10000L)
        }
    }

    private fun hideBarNow() {
        if (fullIndex >= 0 || cameras.isEmpty()) return
        bar.visibility = View.GONE
        tiles.firstOrNull()?.requestFocus()
    }

    // --------------------------------------------------------------- Auto

    private fun restartAuto() {
        handler.removeCallbacks(autoRunnable)
        if (store.auto) handler.postDelayed(autoRunnable, 15000L)
    }

    // --------------------------------------------------------------- Mando

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (fullIndex >= 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        showFull(fullIndex - 1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        showFull(fullIndex + 1)
                        return true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE -> {
                        exitFull()
                        return true
                    }
                }
            } else {
                if (bar.visibility != View.VISIBLE) {
                    val focus = currentFocus
                    val idx = tiles.indexOfFirst { it === focus }
                    val columns = wall?.cols ?: 2
                    val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    if (event.keyCode == KeyEvent.KEYCODE_MENU || (up && (idx < 0 || idx < columns))) {
                        showBar()
                        return true
                    }
                } else {
                    kickBar()
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
