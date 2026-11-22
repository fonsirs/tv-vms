package com.fonsi.tvvms

import android.content.Context
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
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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
            name.text = camera.name
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
    }

    fun start() {
        active = true
        attempts = 0
        connect()
    }

    private var gotFrame = false
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
        releasePlayer()
    }

    private fun releasePlayer() {
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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 1500, 250, 500)
            .build()
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
        p.volume = if (audio) 1f else 0f
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                gotFrame = true
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
        playerView.player = p

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(10000L)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        player = p
        handler.postDelayed(watchdog, 7000L)
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
