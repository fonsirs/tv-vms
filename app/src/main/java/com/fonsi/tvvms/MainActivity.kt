package com.fonsi.tvvms

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {


    private val gold = Color.parseColor("#C9A04B")
    private val muted = Color.parseColor("#8A93A0")
    private val bgColor = Color.parseColor("#0B0D10")
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var store: CameraStore
    private lateinit var dialogs: CameraDialogs
    private lateinit var pinStore: PinStore
    private lateinit var bar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var pageLabel: TextView
    private lateinit var clock: TextView
    private lateinit var chip4: Chip
    private lateinit var chip8: Chip
    private lateinit var chip16: Chip
    private lateinit var chipCustom: Chip
    private lateinit var chipPrev: Chip
    private lateinit var chipNext: Chip
    private lateinit var chipAuto: Chip
    private lateinit var chipHd: Chip
    private lateinit var chipEvents: Chip
    private lateinit var chipInfo: Chip
    private lateinit var panel: EventPanel
    private var hub: EventHub? = null
    private var unseen = 0

    // PTZ en pantalla completa
    private var ptzMode = false
    private var ptzZoom = false
    private var ptzDir: Ptz.Dir? = null
    private var ptzPill: TextView? = null
    private val ptzWatchdog = Runnable { ptzStopNow() }

    private var cameras: List<Camera> = emptyList()
    private var wall: WallLayout? = null
    private val tiles = ArrayList<TileView>()
    private var page = 0
    private var fullIndex = -1
    private var unlocked = false
    private var pausedAt = 0L
    private var pinDialog: AlertDialog? = null

    private val hideBarRunnable = Runnable { hideBarNow() }
    private val clockRunnable = object : Runnable {
        override fun run() {
            clock.text = timeFmt.format(Date())
            handler.postDelayed(this, 15000L)
        }
    }
    private val autoRunnable = object : Runnable {
        override fun run() {
            if (fullIndex < 0 && pageCount() > 1) changePage(1)
            handler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        store = CameraStore(this)
        store.onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
        pinStore = PinStore(this)
        dialogs = CameraDialogs(this, store, pinStore) { onCamerasChanged() }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (unlocked && pinStore.isSet() && System.currentTimeMillis() - pausedAt > 60000L) unlocked = false
        if (pinStore.isSet() && !unlocked) {
            showLock()
            return
        }
        startUi()
    }

    override fun onPause() {
        super.onPause()
        pausedAt = System.currentTimeMillis()
        ptzStopNow()
        handler.removeCallbacksAndMessages(null)
        stopTiles()
        pinDialog?.dismiss()
        pinDialog = null
        stopEvents()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        stopTiles()
        stopEvents()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopTiles()
        stopEvents()
        super.onDestroy()
    }

    private fun startUi() {
        cameras = store.load()
        fullIndex = -1
        startEvents()
        renderGrid()
        showBar()
        restartAuto()
        handler.post(clockRunnable)
    }

    // ------------------------------------------------------------ Bloqueo con PIN

    private fun showLock() {
        stopTiles()
        stopEvents()
        content.removeAllViews()
        bar.visibility = View.GONE

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), dp(8))
        val et = EditText(this)
        et.hint = "PIN"
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        box.addView(et)

        val dlg = AlertDialog.Builder(this)
            .setTitle("TV VMS bloqueada")
            .setMessage("Introduce el PIN para ver las cámaras")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Entrar", null)
            .setNegativeButton("Salir") { _, _ -> finish() }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val wait = pinStore.lockedSeconds()
                if (wait > 0) {
                    Toast.makeText(this, "Demasiados intentos. Espera " + wait + " s", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pinStore.check(et.text.toString())) {
                    unlocked = true
                    dlg.dismiss()
                    pinDialog = null
                    startUi()
                } else {
                    et.setText("")
                    val w2 = pinStore.lockedSeconds()
                    val msg = if (w2 > 0) "PIN incorrecto. Bloqueado " + w2 + " s" else "PIN incorrecto"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dlg.show()
        pinDialog = dlg
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun chip(label: String, onClick: () -> Unit): Chip {
        val c = Chip(this, label)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun addToBar(v: View, leftMargin: Int = 6) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(leftMargin), 0, 0, 0)
        bar.addView(v, lp)
    }

    private fun buildUi() {
        val rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setBackgroundColor(bgColor)
        rootLayout.clipChildren = false
        rootLayout.clipToPadding = false

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(bgColor)
        bar.setPadding(dp(22), dp(12), dp(22), dp(10))
        // el botón enfocado crece un poco: que no se recorte arriba ni abajo
        bar.clipToPadding = false
        bar.clipChildren = false

        val brand = ImageView(this)
        brand.setImageResource(R.drawable.logo_bar)
        brand.adjustViewBounds = true
        brand.scaleType = ImageView.ScaleType.FIT_CENTER

        chip4 = chip("4") { setGrid(4) }
        chip8 = chip("8") { setGrid(8) }
        chip16 = chip("16") { setGrid(16) }
        chipCustom = chip("★") { onCustomChip() }
        chipPrev = chip("‹") { changePage(-1) }
        chipNext = chip("›") { changePage(1) }
        pageLabel = TextView(this)
        pageLabel.setTextColor(muted)
        pageLabel.textSize = 14f
        pageLabel.setPadding(dp(12), 0, dp(12), 0)

        val spacer = View(this)

        chipAuto = chip("Auto") {
            store.auto = !store.auto
            updateBar()
            restartAuto()
        }
        chipHd = chip("SD") {
            store.hd = !store.hd
            updateBar()
            renderGrid()
        }
        chipEvents = chip("Eventos") { toggleEvents() }
        chipInfo = chip("i") {
            store.showInfo = !store.showInfo
            for (t in tiles) t.setInfo(store.showInfo)
            panel.setInfoVisible(store.showInfo)
            updateBar()
        }
        val camsChip = chip("Cámaras") { dialogs.showList() }
        camsChip.filled = true

        clock = TextView(this)
        clock.setTextColor(muted)
        clock.textSize = 16f
        clock.typeface = Typeface.DEFAULT_BOLD
        clock.text = timeFmt.format(Date())

        bar.addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        addToBar(chip4, 28)
        addToBar(chip8)
        addToBar(chip16)
        addToBar(chipCustom)
        // las flechas de página solo aparecen cuando hay más de una página
        addToBar(chipPrev, 22)
        addToBar(chipNext)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        addToBar(chipAuto)
        addToBar(chipInfo)
        addToBar(chipEvents)
        addToBar(camsChip)
        addToBar(clock, 20)

        content = FrameLayout(this)

        rootLayout.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        panel = EventPanel(this)
        panel.onOpen = { cam ->
            val i = cameras.indexOfFirst { it.id == cam.id }
            if (i >= 0) showFull(i)
        }
        panel.applyFilter(store.eventFilter)
        panel.setInfoVisible(store.showInfo)
        panel.onFilterChange = { f -> store.eventFilter = f }
        panel.visibility = if (store.eventsOpen) View.VISIBLE else View.GONE
        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        body.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val panelW = (resources.displayMetrics.widthPixels * 0.22f).toInt().coerceIn(dp(230), dp(290))
        body.addView(panel, LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootLayout)
        updateBar()
    }

    private fun updateBar() {
        val g = store.gridSize
        chip4.active = g == 4
        chip8.active = g == 8
        chip16.active = g == 16
        chipCustom.active = g == 0
        chipAuto.active = store.auto
        chipAuto.text = if (store.auto) "Auto ●" else "Auto"
        chipHd.active = store.hd
        chipHd.text = if (store.hd) "HD" else "SD"
        chipEvents.active = store.eventsOpen
        chipInfo.active = store.showInfo
        chipEvents.text = if (unseen > 0 && !store.eventsOpen) "Eventos ($unseen)" else "Eventos"
    }

    // ------------------------------------------------------------- Rejilla

    /** Cámaras que se ven en la cuadrícula: todas, o solo las elegidas en la vista personalizada. */
    private fun shownCameras(): List<Camera> {
        if (store.gridSize != 0) return cameras
        val ids = store.customIds
        return cameras.filter { it.id in ids }
    }

    private fun onCustomChip() {
        val already = store.gridSize == 0
        if (!already && shownCameras().let { store.customIds.isNotEmpty() && it.isNotEmpty() }) {
            setGrid(0)
        } else {
            showCustomPicker()
        }
    }

    private fun showCustomPicker() {
        if (cameras.isEmpty()) {
            Toast.makeText(this, "Primero añade cámaras", Toast.LENGTH_SHORT).show()
            return
        }
        val names = cameras.map { it.name }.toTypedArray()
        val current = store.customIds
        val checked = BooleanArray(cameras.size) { cameras[it].id in current }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Elige las cámaras de tu vista (máx. 16)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Guardar", null)
            .setNeutralButton("Marcar todas", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val all = checked.any { !it }
                for (i in checked.indices) {
                    checked[i] = all
                    dlg.listView.setItemChecked(i, all)
                }
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ids = ArrayList<String>()
                for (i in cameras.indices) if (checked[i]) ids.add(cameras[i].id)
                if (ids.isEmpty()) {
                    Toast.makeText(this, "No has marcado ninguna cámara", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (ids.size > 16) {
                    Toast.makeText(this, "Máximo 16 cámaras (has marcado " + ids.size + ")", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                store.customIds = ids
                dlg.dismiss()
                setGrid(0)
            }
        }
        dlg.show()
    }

    private fun pageCount(): Int {
        if (store.gridSize == 0) return 1
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
        startEvents()
        renderGrid()
    }

    // ------------------------------------------------------------- Eventos

    private fun ui(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun startEvents() {
        stopEvents()
        panel.clearStatus()
        val h = EventHub(
            onEvent = { ev ->
                ui {
                    panel.add(ev)
                    if (panel.matches(ev)) {
                        tiles.firstOrNull { it.camera.id == ev.camera.id }?.flash(ev.kind.color, ev.type)
                        if (!store.eventsOpen) {
                            unseen++
                            updateBar()
                        }
                    }
                }
            },
            onThumb = { ev -> ui { panel.refreshThumb(ev) } },
            onStatus = { key, label, text -> ui { panel.setStatus(key, label, text) } },
            grab = { cam -> grabLive(cam) }
        )
        hub = h
        // la red no se toca desde el hilo principal
        val list = cameras
        Thread { h.start(list) }.start()
    }

    /** Se llama desde un hilo de fondo: pide al hilo principal una captura del recuadro de esa cámara. */
    private fun grabLive(cam: Camera): android.graphics.Bitmap? {
        if (isFinishing || isDestroyed) return null
        var result: android.graphics.Bitmap? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            try {
                result = tiles.firstOrNull { it.camera.id == cam.id }?.grabFrame()
            } catch (e: Exception) {
                result = null
            } finally {
                latch.countDown()
            }
        }
        latch.await(4000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    private fun stopEvents() {
        val h = hub ?: return
        hub = null
        Thread { h.stop() }.start()
    }

    private fun toggleEvents() {
        store.eventsOpen = !store.eventsOpen
        panel.visibility = if (store.eventsOpen) View.VISIBLE else View.GONE
        if (store.eventsOpen) unseen = 0
        updateBar()
        kickBar()
    }

    private fun stopTiles() {
        for (t in tiles) t.stop()
        tiles.clear()
    }

    private fun renderGrid() {
        stopTiles()
        content.removeAllViews()
        wall = null
        content.setPadding(dp(8), dp(2), dp(8), dp(8))

        val shown = shownCameras()
        val custom = store.gridSize == 0
        val size = if (custom) maxOf(1, shown.size) else store.gridSize
        // 4 → 2x2, 8 → 3x3 (8 cámaras y un hueco), 16 → 4x4: así cada cuadro es 16:9 y la imagen lo llena
        var cols = when (size) { 4 -> 2; 8 -> 3; else -> 4 }
        var rows = when (size) { 4 -> 2; 8 -> 3; else -> 4 }
        if (custom) {
            // vista propia: tantas columnas y filas como hagan falta para las cámaras elegidas
            val n = size
            cols = when { n <= 1 -> 1; n <= 2 -> 2; n <= 4 -> 2; n <= 6 -> 3; n <= 9 -> 3; n <= 12 -> 4; else -> 4 }
            rows = (n + cols - 1) / cols
        }
        val pages = pageCount()
        if (page >= pages) page = 0
        chipPrev.visibility = if (pages > 1) View.VISIBLE else View.GONE
        chipNext.visibility = if (pages > 1) View.VISIBLE else View.GONE

        if (cameras.isEmpty()) {
            showEmptyState()
            pageLabel.text = "0 cámaras"
            return
        }
        if (shown.isEmpty()) {
            // la vista propia se quedó sin cámaras (se borraron): vuelve a la cuadrícula normal
            store.gridSize = 4
            updateBar()
            renderGrid()
            return
        }

        val w = WallLayout(this)
        w.cols = cols
        w.rows = rows
        val from = page * size
        val to = minOf(shown.size, from + size)
        for (i in from until to) {
            val cam = shown[i]
            val tile = TileView(this, cam, false, false)
            tile.setInfo(store.showInfo)
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
        val starting = ArrayList<TileView>(tiles)
        for ((n, t) in starting.withIndex()) {
            handler.postDelayed({
                if (tiles.contains(t)) t.start()
            }, n * 250L)
        }
        pageLabel.text = (if (custom) "Mi vista" else (page + 1).toString() + " / " + pages) + "   ·   " + shown.size + " cámaras"
        if (bar.visibility != View.VISIBLE) tiles.firstOrNull()?.requestFocus()
    }

    private fun showEmptyState() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "Aún no hay cámaras"
        title.setTextColor(Color.WHITE)
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER

        val sub = TextView(this)
        sub.text = "Añade tu NVR / DVR con todos sus canales, o una cámara suelta\n(Dahua, Hikvision, ONVIF o URL RTSP)."
        sub.setTextColor(muted)
        sub.textSize = 16f
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, dp(10), 0, dp(24))

        val add = chip("Añadir cámaras") { dialogs.showList() }
        add.filled = true

        box.addView(title)
        box.addView(sub)
        box.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(
            box,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        add.requestFocus()
    }

    // -------------------------------------------------------- Pantalla completa

    private fun showFull(index: Int) {
        if (cameras.isEmpty() || index < 0) return
        val first = fullIndex < 0
        val n = cameras.size
        ptzReset()
        fullIndex = ((index % n) + n) % n
        stopTiles()
        content.removeAllViews()
        wall = null
        content.setPadding(0, 0, 0, 0)
        val tile = TileView(this, cameras[fullIndex], true, true)
        tile.setInfo(store.showInfo)
        tile.isFocusable = false
        tile.isClickable = false
        tile.setAudio(true)
        content.addView(
            tile,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        tiles.add(tile)
        tile.start()
        addFullOverlay(fullIndex, first)
        handler.removeCallbacks(hideBarRunnable)
        bar.visibility = View.GONE
    }

    private fun pill(text: String, size: Float, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(color)
        tv.textSize = size
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#B3000000"))
        bg.cornerRadius = dp(22).toFloat()
        tv.background = bg
        tv.setPadding(dp(18), dp(8), dp(18), dp(8))
        return tv
    }

    private fun fade(v: View, delay: Long) {
        v.animate().cancel()
        v.alpha = 1f
        v.animate().alpha(0f).setStartDelay(delay).setDuration(500L).start()
    }

    private fun addFullOverlay(index: Int, first: Boolean) {
        val info = pill("●  " + cameras[index].name + "     " + (index + 1) + " / " + cameras.size, 16f, Color.WHITE)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        lp.setMargins(dp(24), dp(20), 0, 0)
        content.addView(info, lp)
        fade(info, 3500L)

        if (first) {
            val hint = pill("‹ ›  cambiar de cámara     ▲  PTZ     OK  volver a la cuadrícula", 14f, Color.parseColor("#C9CED6"))
            val hlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            hlp.setMargins(0, 0, 0, dp(24))
            content.addView(hint, hlp)
            fade(hint, 5000L)
        }
    }

    private fun exitFull() {
        ptzReset()
        val idx = fullIndex
        fullIndex = -1
        if (idx >= 0 && store.gridSize > 0) page = idx / store.gridSize
        renderGrid()
        if (idx >= 0) {
            val id = cameras.getOrNull(idx)?.id
            val t = tiles.firstOrNull { it.camera.id == id } ?: tiles.firstOrNull()
            t?.requestFocus()
        }
    }

    // ------------------------------------------------------------ Barra superior

    private fun showBar() {
        bar.visibility = View.VISIBLE
        chip4.requestFocus()
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

    // ----------------------------------------------------------------- PTZ

    private fun ptzCam(): Camera? = if (fullIndex in cameras.indices) cameras[fullIndex] else null

    private fun ptzSetText(text: String) {
        val pill = ptzPill
        if (pill != null) {
            pill.text = text
            pill.animate().cancel()
            pill.alpha = 1f
            return
        }
        val p = pill(text, 15f, Color.WHITE)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.setMargins(0, 0, 0, dp(24))
        content.addView(p, lp)
        ptzPill = p
    }

    private fun ptzHelp(): String =
        if (ptzZoom) "PTZ · ZOOM     ▲ acercar    ▼ alejar    OK  volver a mover    Atrás  salir"
        else "PTZ     ◀ ▲ ▼ ▶  mover    OK  zoom    Atrás  salir"

    private fun ptzEnter() {
        if (ptzCam() == null) return
        ptzMode = true
        ptzZoom = false
        // no se bloquea por la comprobación: algunos equipos (NVR) responden mal a ella aunque la cámara sí tenga PTZ
        ptzSetText(ptzHelp())
    }

    private fun ptzClearPill() {
        ptzPill?.let { content.removeView(it) }
        ptzPill = null
    }

    private fun ptzStopNow() {
        handler.removeCallbacks(ptzWatchdog)
        val d = ptzDir ?: return
        ptzDir = null
        ptzCam()?.let { Ptz.stop(it, d) }
    }

    private fun ptzReset() {
        ptzStopNow()
        ptzMode = false
        ptzZoom = false
        ptzClearPill()
    }

    /** Teclas en modo PTZ. Devuelve true si la tecla se ha usado. */
    private fun ptzKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val key = event.keyCode
        when (key) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (down && event.repeatCount == 0) ptzReset()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down && event.repeatCount == 0) {
                    ptzStopNow()
                    ptzZoom = !ptzZoom
                    ptzSetText(ptzHelp())
                }
                return true
            }
        }
        val dir: Ptz.Dir? = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> if (ptzZoom) Ptz.Dir.ZOOM_IN else Ptz.Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> if (ptzZoom) Ptz.Dir.ZOOM_OUT else Ptz.Dir.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> if (ptzZoom) null else Ptz.Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (ptzZoom) null else Ptz.Dir.RIGHT
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Ptz.Dir.ZOOM_IN
            KeyEvent.KEYCODE_MEDIA_REWIND -> Ptz.Dir.ZOOM_OUT
            else -> return false
        }
        if (dir == null) return true
        val cam = ptzCam() ?: return true
        if (down) {
            if (ptzDir != dir) {
                ptzStopNow()
                ptzDir = dir
                val idx = fullIndex
                Ptz.start(cam, dir) { msg ->
                    runOnUiThread { if (ptzMode && fullIndex == idx) ptzSetText("PTZ " + dir.name + ": " + msg) }
                }
            }
            // por si se pierde el aviso de "tecla soltada", se para solo
            handler.removeCallbacks(ptzWatchdog)
            handler.postDelayed(ptzWatchdog, 1200L)
        } else if (ptzDir == dir) {
            ptzStopNow()
        }
        return true
    }

    // --------------------------------------------------------------- Mando

    private fun isInPanel(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === panel) return true
            p = p.parent
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pinStore.isSet() && !unlocked) return super.dispatchKeyEvent(event)
        if (fullIndex >= 0 && ptzMode && ptzKey(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (fullIndex >= 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> {
                        if (event.repeatCount == 0) ptzEnter()
                        return true
                    }
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
                val inPanel = currentFocus?.let { f -> isInPanel(f) } ?: false
                if (inPanel && event.keyCode == KeyEvent.KEYCODE_DPAD_UP && currentFocus?.focusSearch(View.FOCUS_UP) == null) {
                    showBar()
                    return true
                }
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
