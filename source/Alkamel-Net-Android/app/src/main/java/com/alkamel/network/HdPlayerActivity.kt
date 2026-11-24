package com.alkamel.network

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class HdPlayerActivity : Activity() {
    companion object {
        private const val STREAM_URL = "http://10.10.10.10:8888/live/index.m3u8"
        private const val BG = 0xFF030B1B.toInt()
        private const val PANEL = 0xFF081B35.toInt()
        private const val PANEL_LIGHT = 0xFF102B50.toInt()
        private const val BLUE = 0xFF087DFF.toInt()
        private const val CYAN = 0xFF10C8F2.toInt()
        private const val GOLD = 0xFFFFC84A.toInt()
        private const val GREEN = 0xFF00B98B.toInt()
        private const val MUTED = 0xFF9FB2CC.toInt()
    }

    private var player: ExoPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    private lateinit var playerView: PlayerView
    private lateinit var pageScroll: android.widget.ScrollView
    private lateinit var videoFrame: FrameLayout
    private lateinit var videoCard: LinearLayout
    private lateinit var status: TextView
    private lateinit var volumeValue: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var playPause: TextView
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var fullscreen = false
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildInterface()
        initializePlayer()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(radius: Float, fill: Int, stroke: Int = 0xFF164B70.toInt(), width: Int = 1) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            setStroke(dp(width).coerceAtLeast(1), stroke)
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(value: String, fill: Int, stroke: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = value
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = shape(18f, fill, stroke)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }

    private fun buildInterface() {
        pageScroll = android.widget.ScrollView(this).apply {
            setFillViewport(true)
            isFillViewport = true
            setBackgroundColor(BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(BG)
        }

        // Compact brand header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(24f, PANEL, 0xFF174F8A.toInt(), 2)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val logo = text("K", 28f, GOLD, true).apply {
            background = shape(16f, PANEL_LIGHT, GOLD, 2)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        brand.addView(text("شبكة الكامل", 21f, Color.WHITE, true))
        brand.addView(text("Alkamel Network", 12f, CYAN, true))
        header.addView(brand, LinearLayout.LayoutParams(0, dp(58), 1f))
        header.addView(text("● متصل", 13f, GREEN, true).apply {
            background = shape(18f, PANEL_LIGHT, 0xFF176C83.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        val headerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(78))
        headerParams.bottomMargin = dp(16)
        root.addView(header, headerParams)

        // HD title strip
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(20f, PANEL, 0xFF1763A2.toInt(), 2)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        titleRow.addView(text("HD", 22f, GOLD, true).apply {
            background = shape(10f, 0xFF172A48.toInt(), GOLD, 2)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        })
        titleRow.addView(text("بث مباشر HD", 20f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(12) })
        titleRow.addView(text("◇ جودة عالية", 12f, CYAN, true).apply {
            background = shape(16f, PANEL_LIGHT, 0xFF1763A2.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        val titleParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
        titleParams.bottomMargin = dp(12)
        root.addView(titleRow, titleParams)

        // Video frame with live badge and custom control rail
        videoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(24f, Color.BLACK, 0xFF087DCE.toInt(), 2)
            setPadding(dp(5), dp(5), dp(5), dp(8))
        }
        videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
        }
        videoFrame.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val liveBadge = text("● مباشر الآن", 12f, Color.WHITE, true).apply {
            background = shape(18f, 0xDD9D1020.toInt(), 0xFFFF4256.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        videoFrame.addView(liveBadge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(34), Gravity.TOP or Gravity.START
        ).apply { setMargins(dp(12), dp(12), 0, 0) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00101820, 0xE8000000.toInt())
            )
        }
        playPause = button("Ⅱ", 0xAA152638.toInt(), 0x885C819B.toInt()) { togglePlayback() }
        controls.addView(playPause, LinearLayout.LayoutParams(dp(46), dp(44)))
        val muteButton = button("◖))", 0xAA152638.toInt(), 0x885C819B.toInt()) {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
        }
        controls.addView(muteButton, LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginStart = dp(6) })
        volumeBar = SeekBar(this).apply {
            max = 200
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { applyVolume(progress) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val volumeColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text("100%", 11f, CYAN, true).also { volumeValue = it })
            addView(volumeBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
        }
        controls.addView(volumeColumn, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(5); marginEnd = dp(5)
        })
        controls.addView(button("▣", 0xAA102D4A.toInt(), CYAN) { openScreenCast() },
            LinearLayout.LayoutParams(dp(46), dp(44)))
        controls.addView(button("⛶", 0xAA152638.toInt(), 0x885C819B.toInt()) { toggleFullscreen() },
            LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginStart = dp(6) })
        controls.addView(button("↻", 0xAA152638.toInt(), 0x885C819B.toInt()) { restartStream() },
            LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginStart = dp(6) })
        videoFrame.addView(controls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM
        ))

        videoCard.addView(videoFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
        ))
        status = text("جارٍ الاتصال بالبث...", 14f, CYAN, true).apply {
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        videoCard.addView(status)
        val videoParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        videoParams.bottomMargin = dp(14)
        root.addView(videoCard, videoParams)

        // Cast action is retained; third-party VLC/MX launch buttons are intentionally omitted.
        val cast = button("▣   إرسال للشاشة   Cast", 0xFF075EAD.toInt(), CYAN) { openScreenCast() }
        cast.textSize = 19f
        root.addView(cast, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
            bottomMargin = dp(14)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(22f, PANEL, 0xFF174F8A.toInt(), 2)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val infoText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        infoText.addView(text("استمتع بمشاهدة البث بجودة عالية", 16f, Color.WHITE, true))
        infoText.addView(text("على جميع أجهزتك", 15f, MUTED))
        info.addView(infoText, LinearLayout.LayoutParams(0, dp(70), 1f))
        val benefits = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(16f, 0xFF0C2746.toInt(), 0xFF1C5B91.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        benefits.addView(text("✓ جودة HD", 13f, GREEN, true))
        benefits.addView(text("✓ صوت حتى 200%", 13f, GREEN, true))
        benefits.addView(text("✓ إرسال للشاشة", 13f, GREEN, true))
        info.addView(benefits, LinearLayout.LayoutParams(dp(142), dp(92)))
        root.addView(info)

        root.addView(text("شبكة الكامل • Alkamel Network", 12f, MUTED).apply {
            setPadding(0, dp(20), 0, dp(6))
        })
        pageScroll.addView(root)
        setContentView(pageScroll)
    }

    private fun initializePlayer() {
        releasePlayer()
        status.text = "جارٍ تحميل البث..."
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                status.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "جارٍ تحميل البث..."
                    Player.STATE_READY -> {
                        applyVolume(volumeBar.progress)
                        if (exo.isPlaying) "البث يعمل الآن" else "تم الاتصال؛ اضغط تشغيل"
                    }
                    Player.STATE_ENDED -> "انتهى البث"
                    else -> "جارٍ الاتصال بالبث..."
                }
                playPause.text = if (exo.isPlaying) "Ⅱ" else "▶"
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPause.text = if (isPlaying) "Ⅱ" else "▶"
                if (isPlaying) status.text = "البث يعمل الآن"
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status.text = "تعذر تشغيل البث. تحقق من اتصالك بسيرفر الشبكة."
            }
        })
        val item = MediaItem.Builder().setUri(STREAM_URL).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
        applyVolume(volumeBar.progress)
    }

    private fun applyVolume(value: Int) {
        val level = value.coerceIn(0, 200)
        volumeValue.text = "$level%"
        if (!muted) player?.volume = level.coerceAtMost(100) / 100f
        val extraGain = ((level - 100).coerceAtLeast(0) * 60).toInt()
        try {
            val sessionId = player?.audioSessionId ?: 0
            if (sessionId != 0) {
                if (loudness == null) loudness = LoudnessEnhancer(sessionId)
                loudness?.setTargetGain(extraGain)
                loudness?.enabled = extraGain > 0
            }
        } catch (_: Exception) {
            status.text = "التضخيم الإضافي غير مدعوم على هذا الجهاز"
        }
    }

    private fun togglePlayback() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun openScreenCast() {
        try {
            startActivity(Intent("android.settings.CAST_SETTINGS"))
        } catch (_: Exception) {
            Toast.makeText(this, "إعدادات إرسال الشاشة غير متاحة على هذا الجهاز", Toast.LENGTH_LONG).show()
        }
    }

    private fun restartStream() {
        player?.seekToDefaultPosition()
        player?.prepare()
        player?.play()
        status.text = "جارٍ إعادة الاتصال..."
    }

    private fun toggleFullscreen() {
        if (!fullscreen) {
            previousOrientation = requestedOrientation
            fullscreen = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            status.visibility = View.GONE
            (videoFrame.parent as? android.view.ViewGroup)?.removeView(videoFrame)
            videoFrame.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setContentView(videoFrame)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        } else {
            fullscreen = false
            requestedOrientation = previousOrientation
            status.visibility = View.VISIBLE
            setContentView(pageScroll)
            videoCard.addView(videoFrame, 0, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
            ))
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (fullscreen) {
            videoFrame.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun releasePlayer() {
        try { loudness?.release() } catch (_: Exception) {}
        loudness = null
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initializePlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
