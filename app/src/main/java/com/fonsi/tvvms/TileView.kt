package com.fonsi.tvvms

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Un recuadro con una cámara: reproduce su flujo RTSP, muestra el nombre y reconecta solo si se cae. */
class TileView(
    context: Context,
    val camera: Camera,
    private val useMainStream: Boolean
) : FrameLayout(context) {

    private val playerView = PlayerView(context)
    private val nameLabel = TextView(context)
    private val status = TextView(context)
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var active = false
    private var audio = false
    private val retryRunnable = Runnable {
        if (active) connect()
    }

    init {
        setBackgroundColor(Color.BLACK)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isFocusable = true
        isClickable = true

        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.isFocusable = false
        addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        nameLabel.text = camera.name
        nameLabel.setTextColor(Color.WHITE)
        nameLabel.textSize = 12f
        nameLabel.setBackgroundColor(Color.parseColor("#99000000"))
        nameLabel.setPadding(dp(6), dp(2), dp(6), dp(2))
        addView(
            nameLabel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        )

        status.setTextColor(Color.parseColor("#D4AF37"))
        status.textSize = 14f
        status.setBackgroundColor(Color.parseColor("#99000000"))
        status.setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        foreground = focusBorder()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun focusBorder(): StateListDrawable {
        val focused = GradientDrawable()
        focused.setStroke(dp(3), Color.parseColor("#D4AF37"))
        focused.setColor(Color.TRANSPARENT)
        val normal = GradientDrawable()
        normal.setStroke(dp(1), Color.parseColor("#2A2F38"))
        normal.setColor(Color.TRANSPARENT)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), focused)
        sl.addState(intArrayOf(), normal)
        return sl
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

    fun stop() {
        active = false
        handler.removeCallbacks(retryRunnable)
        releasePlayer()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun connect() {
        releasePlayer()
        val url = StreamUrls.url(camera, useMainStream)
        if (url.isBlank()) {
            showStatus("Sin URL de vídeo")
            return
        }
        showStatus(if (attempts == 0) "Conectando…" else "Reconectando…")

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
                attempts = 0
                status.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = (error.cause?.message ?: error.message ?: "").replace("\n", " ").take(70)
                showStatus("Sin señal\n" + error.errorCodeName + (if (detail.isEmpty()) "" else "\n" + detail))
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
    }

    private fun scheduleRetry() {
        if (!active) return
        attempts++
        val delay = minOf(3000L * attempts, 15000L)
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }
}
