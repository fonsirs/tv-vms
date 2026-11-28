package com.fonsi.tvvms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/** Un recuadro con una cámara: reproduce su flujo RTSP, muestra nombre y estado, y reconecta solo si se cae. */
class TileView(
    context: Context,
    val camera: Camera,
    private val useMainStream: Boolean,
    private val fullscreen: Boolean
) : FrameLayout(context) {

    private val amber = Color.parseColor("#F5A524")
    private val green = Color.parseColor("#3DD68C")
    private val red = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#C9A04B")

    private val playerView: PlayerView =
        LayoutInflater.from(context).inflate(R.layout.player_view, this, false) as PlayerView
    private val dotShape = GradientDrawable()
    private val status = TextView(context)
    private val diag = TextView(context)
    private var decoderName = ""
    private var infoOn = false
    private var lastRendered = 0
    private var lastRenderedAt = 0L
    private var fps = -1f
    private var dropped = 0
    private val infoTick = object : Runnable {
        override fun run() {
            measureFps()
            refreshInfo()
            if (infoOn && active) handler.postDelayed(this, 2000L)
        }
    }

    /** Muestra u oculta el rótulo con los datos técnicos del vídeo (botón "i" de la barra). */
    private var nameView: TextView? = null

    fun refreshName() {
        nameView?.text = camera.label
    }

    fun setInfo(on: Boolean) {
        infoOn = on
        handler.removeCallbacks(infoTick)
        if (on && active) handler.post(infoTick)
        refreshInfo()
    }

    private fun measureFps() {
        val c = player?.videoDecoderCounters ?: return
        c.ensureUpdated()
        val now = android.os.SystemClock.elapsedRealtime()
        val rendered = c.renderedOutputBufferCount
        if (lastRenderedAt != 0L && now > lastRenderedAt && rendered >= lastRendered) {
            fps = (rendered - lastRendered) * 1000f / (now - lastRenderedAt)
        }
        lastRendered = rendered
        lastRenderedAt = now
        dropped = c.droppedBufferCount
    }

    private fun refreshInfo() {
        if (!infoOn) {
            diag.visibility = View.GONE
            return
        }
        val vf = player?.videoFormat
        if (vf == null || !gotFrame) {
            diag.visibility = View.GONE
            return
        }
        val codec = (vf.sampleMimeType ?: "?").removePrefix("video/").uppercase()
        val sb = StringBuilder()
        sb.append(codec).append(" · ").append(vf.width).append("x").append(vf.height)
            .append(" · ").append(if (useMainStream) "HD" else "SD").append('\n')
        sb.append("Decodificador: ").append(decoderName.ifEmpty { "-" }).append('\n')
        if (fps >= 0f) {
            sb.append("Imágenes/s: ").append(String.format(java.util.Locale.US, "%.1f", fps))
            if (dropped > 0) sb.append(" · perdidas: ").append(dropped)
            sb.append('\n')
        }
        if (vf.frameRate > 0f) sb.append("Cámara anuncia: ").append(Math.round(vf.frameRate)).append(" fps\n")
        if (vf.bitrate > 0) sb.append("Bitrate: ").append(vf.bitrate / 1000).append(" kbps\n")
        sb.append(camera.brand.label).append(" · ").append(camera.host).append(":").append(camera.port)
        if (camera.brand == Brand.DAHUA || camera.brand == Brand.HIKVISION) sb.append(" · canal ").append(camera.channel)
        sb.append("\nParámetros SPS/PPS: ").append(when (vf.id) { "sdp" -> "del SDP"; "ib" -> "del flujo"; "fix" -> "corregidos"; else -> vf.id ?: "-" })
        diag.text = sb.toString()
        diag.visibility = View.VISIBLE
    }
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var active = false
    private var audio = false
    private val credRegex = Regex("(?i)rtsps?://[^@/\\s]*@")
    private val retryRunnable = Runnable {
        if (active) connect()
    }

    init {
        val radius = if (fullscreen) 0f else dp(14).toFloat()
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#0F1319"))
        bg.cornerRadius = radius
        background = bg
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND

        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isFocusable = true
        isClickable = true

        addView(playerView)

        if (!fullscreen) {
            val scrim = View(context)
            scrim.background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#D9000000"), Color.TRANSPARENT)
            )
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM))

            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), 0, dp(12), dp(10))
            dotShape.shape = GradientDrawable.OVAL
            dotShape.setColor(amber)
            val dot = View(context)
            dot.background = dotShape
            val dotLp = LinearLayout.LayoutParams(dp(8), dp(8))
            dotLp.rightMargin = dp(8)
            row.addView(dot, dotLp)
            val name = TextView(context)
            name.text = camera.label
            nameView = name
            name.setTextColor(Color.parseColor("#F2F4F7"))
            name.textSize = 13f
            name.typeface = Typeface.DEFAULT_BOLD
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            name.setShadowLayer(4f, 0f, 1f, Color.BLACK)
            row.addView(name)
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

            val badge = TextView(context)
            badge.text = if (useMainStream) "HD" else "SD"
            badge.textSize = 10f
            badge.typeface = Typeface.DEFAULT_BOLD
            badge.setTextColor(Color.parseColor("#C9CED6"))
            val badgeBg = GradientDrawable()
            badgeBg.setColor(Color.parseColor("#80000000"))
            badgeBg.cornerRadius = dp(8).toFloat()
            badge.background = badgeBg
            badge.setPadding(dp(7), dp(2), dp(7), dp(2))
            val badgeLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            badgeLp.setMargins(0, dp(8), dp(8), 0)
            addView(badge, badgeLp)
            // SD/HD solo se ve unos segundos al poner la cuadrícula y se desvanece
            badge.animate().alpha(0f).setStartDelay(4000L).setDuration(800L).start()
        }

        status.setTextColor(Color.parseColor("#D5DAE1"))
        status.textSize = if (fullscreen) 18f else 13f
        status.gravity = Gravity.CENTER
        val statusBg = GradientDrawable()
        statusBg.setColor(Color.parseColor("#B3000000"))
        statusBg.cornerRadius = dp(12).toFloat()
        status.background = statusBg
        status.setPadding(dp(14), dp(8), dp(14), dp(8))
        addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        diag.setTextColor(Color.parseColor("#C9CED6"))
        diag.textSize = 11f
        diag.setPadding(dp(6), dp(2), dp(6), dp(2))
        diag.setBackgroundColor(Color.parseColor("#99000000"))
        diag.visibility = View.GONE
        addView(diag, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        foreground = focusBorder(radius)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun focusBorder(radius: Float): StateListDrawable {
        val focused = GradientDrawable()
        focused.setStroke(dp(3), gold)
        focused.setColor(Color.TRANSPARENT)
        focused.cornerRadius = radius
        val normal = GradientDrawable()
        normal.setStroke(dp(1), Color.parseColor("#1F2630"))
        normal.setColor(Color.TRANSPARENT)
        normal.cornerRadius = radius
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), focused)
        sl.addState(intArrayOf(), normal)
        return sl
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (fullscreen) return
        val s = if (gainFocus) 1.03f else 1f
        val z = if (gainFocus) dp(12).toFloat() else 0f
        animate().scaleX(s).scaleY(s).translationZ(z).setDuration(130L).start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun setAudio(on: Boolean) {
        audio = on
        player?.volume = if (on) 1f else 0f
        player?.let { applyAudioSelection(it) }
    }

    // Sin audio activado no se pide ni se decodifica la pista de audio: menos carga y menos red,
    // y evita que el audio frene la imagen en equipos modestos como un Fire Stick.
    private fun applyAudioSelection(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio)
            .build()
    }

    fun start() {
        active = true
        attempts = 0
        connect()
    }

    private var gotFrame = false
    private var flashBorder: View? = null
    private var flashLabel: TextView? = null
    private val flashOff = Runnable {
        flashBorder?.animate()?.alpha(0f)?.setDuration(400L)?.start()
        flashLabel?.animate()?.alpha(0f)?.setDuration(400L)?.start()
    }

    /** Resalta el recuadro con un borde de color y el tipo de evento unos segundos (no en pantalla completa). */
    fun flash(color: Int, text: String) {
        if (fullscreen) return
        val border = flashBorder ?: View(context).also {
            flashBorder = it
            addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val d = GradientDrawable()
        d.setColor(Color.TRANSPARENT)
        d.cornerRadius = dp(14).toFloat()
        d.setStroke(dp(4), color)
        border.background = d
        val label = flashLabel ?: TextView(context).also {
            flashLabel = it
            it.textSize = 12f
            it.typeface = Typeface.DEFAULT_BOLD
            it.setTextColor(Color.parseColor("#14181F"))
            it.setPadding(dp(10), dp(3), dp(10), dp(3))
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            lp.setMargins(dp(10), dp(10), 0, 0)
            addView(it, lp)
        }
        val pill = GradientDrawable()
        pill.setColor(color)
        pill.cornerRadius = dp(10).toFloat()
        label.background = pill
        label.text = text
        border.animate().cancel()
        label.animate().cancel()
        border.alpha = 1f
        label.alpha = 1f
        handler.removeCallbacks(flashOff)
        handler.postDelayed(flashOff, 4000L)
    }

    /** Foto del vídeo que se está viendo ahora (para las miniaturas de eventos); null si aún no hay imagen. */
    fun grabFrame(): Bitmap? {
        if (!gotFrame) return null
        val tv = playerView.videoSurfaceView as? TextureView ?: return null
        if (!tv.isAvailable || tv.width <= 0 || tv.height <= 0) return null
        val w = 720
        val h = maxOf(2, w * tv.height / tv.width)
        return try { tv.getBitmap(w, h) } catch (e: Exception) { null }
    }
    private val watchdog = Runnable {
        if (active && !gotFrame) {
            attempts++
            connect()
        }
    }

    fun stop() {
        active = false
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(flashOff)
        releasePlayer()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(infoTick)
        diag.visibility = View.GONE
        playerView.player = null
        player?.release()
        player = null
    }

    private fun connect() {
        releasePlayer()
        gotFrame = false
        handler.removeCallbacks(watchdog)
        val url = StreamUrls.url(camera, useMainStream)
        if (url.isBlank()) {
            showStatus("Sin URL de vídeo", true)
            return
        }
        showStatus(if (attempts == 0) "Conectando…" else "Reconectando…", false)

        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (!fullscreen) {
            // En el cuadrante (flujo ligero) se prefiere el decodificador por software de H.264: los de
            // hardware de algunos Fire Stick/TV son pocos y en ocasiones dan imagen verde.
            renderers.setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
                val list = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                if (mime == MimeTypes.VIDEO_H264) list.sortedBy { if (it.softwareOnly) 0 else 1 } else list
            })
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 1500, 250, 500)
            .build()
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
        p.volume = if (audio) 1f else 0f
        applyAudioSelection(p)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                gotFrame = true
                lastRenderedAt = 0L
                fps = -1f
                handler.removeCallbacks(infoTick)
                if (infoOn) handler.postDelayed(infoTick, 1500L)
                refreshInfo()
                handler.removeCallbacks(watchdog)
                playerView.post {
                    playerView.requestLayout()
                    playerView.videoSurfaceView?.invalidate()
                }
                attempts = 0
                status.visibility = View.GONE
                dotShape.setColor(green)
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = (error.cause?.message ?: error.message ?: "").replace("\n", " ").replace(credRegex, "rtsp://***@").take(70)
                showStatus(
                    "Sin señal\n" + error.errorCodeName + (if (detail.isEmpty()) "" else "\n" + detail),
                    true
                )
                scheduleRetry()
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                this@TileView.decoderName = decoderName
            }
        })
        playerView.player = p

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(10000L)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        player = p
        handler.postDelayed(watchdog, 12000L)
    }

    private fun scheduleRetry() {
        if (!active) return
        attempts++
        val delay = minOf(3000L * attempts, 15000L)
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun showStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(if (error) gold else Color.parseColor("#D5DAE1"))
        status.visibility = View.VISIBLE
        dotShape.setColor(if (error) red else amber)
    }
}
