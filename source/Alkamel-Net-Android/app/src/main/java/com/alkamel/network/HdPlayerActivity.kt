package com.alkamel.network

import android.app.Activity
import android.content.Intent
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
        private const val BG = 0xFF050D1B.toInt()
        private const val PANEL = 0xFF0A1B32.toInt()
        private const val PANEL_TOP = 0xFF0D3049.toInt()
        private const val CYAN = 0xFF00C5E8.toInt()
        private const val GOLD = 0xFFFFC84A.toInt()
        private const val GREEN = 0xFF00B88A.toInt()
        private const val MUTED = 0xFF9FB0C5.toInt()
    }

    private var player: ExoPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var volumeValue: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var playPause: TextView
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildInterface()
        initializePlayer()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(radius: Float, fill: Int, stroke: Int = 0xFF164B70.toInt()) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun pill(value: String, fill: Int, stroke: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = shape(22f, fill, stroke)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { onClick() }
        }

    private fun buildInterface() {
        val scroll = android.widget.ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(22))
            setBackgroundColor(BG)
        }

        // App-style top bar, close to the approved website reference.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(24f, PANEL)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        top.addView(pill("‹", PANEL_TOP, 0xFF17618A.toInt()) { finish() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val appTitle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        appTitle.addView(text("الكامل-نت", 21f, Color.WHITE, true))
        appTitle.addView(text("Alkamel Network", 11f, MUTED))
        top.addView(appTitle, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(pill("بث HD  ◉", 0xFF087EBD.toInt(), CYAN) {})
        val topParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70))
        topParams.bottomMargin = dp(18)
        root.addView(top, topParams)

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(24f, PANEL, 0xFF174C70.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        intro.addView(text("✦  أهلاً وسهلاً بكم", 16f, Color.WHITE, true))
        intro.addView(text("بث مباشر الكامل HD", 25f, Color.WHITE, true))
        intro.addView(text("تدفق البث التلفزيوني عالي الدقة", 14f, MUTED))
        val introParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(142))
        introParams.bottomMargin = dp(18)
        root.addView(intro, introParams)

        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(24f, Color.BLACK, 0xFF087DBB.toInt())
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
        }
        videoFrame.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Custom overlay controls are always visible over the lower edge of the video.
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00101820, 0xD9000000.toInt())
            )
        }
        val expand = pill("⛶", 0x88334444.toInt(), 0x886A8799.toInt()) { toggleFullscreen() }
        overlay.addView(expand, LinearLayout.LayoutParams(dp(48), dp(44)))
        val replay = pill("↻", 0x88334444.toInt(), 0x886A8799.toInt()) { restartStream() }
        overlay.addView(replay, LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginStart = dp(8) })
        val quality = text("1080p Full HD", 13f, CYAN, true).apply {
            background = shape(8f, 0xDD06263B.toInt(), 0xFF087DBB.toInt())
            setPadding(dp(9), dp(7), dp(9), dp(7))
        }
        overlay.addView(quality, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            marginStart = dp(6); marginEnd = dp(6)
        })
        val mute = pill("◖))", 0x88334444.toInt(), 0x886A8799.toInt()) {
            val p = player ?: return@pill
            p.volume = if (p.volume > 0f) 0f else 1f
        }
        overlay.addView(mute, LinearLayout.LayoutParams(dp(48), dp(44)))
        playPause = pill("Ⅱ", 0x88334444.toInt(), 0x886A8799.toInt()) { togglePlayback() }
        overlay.addView(playPause, LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginStart = dp(8) })

        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(62), Gravity.BOTTOM
        )
        videoFrame.addView(overlay, overlayParams)
        playerCard.addView(videoFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(285)
        ))
        status = text("جارٍ تحميل البث...", 14f, CYAN, true)
        status.setPadding(dp(8), dp(14), dp(8), dp(14))
        playerCard.addView(status)
        val playerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        playerParams.bottomMargin = dp(16)
        root.addView(playerCard, playerParams)

        val volumeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(22f, PANEL, 0xFF174C70.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        val volumeHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeHead.addView(text("مستوى الصوت", 17f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, dp(34), 1f))
        volumeValue = text("100%", 19f, CYAN, true)
        volumeHead.addView(volumeValue, LinearLayout.LayoutParams(dp(80), dp(34)))
        volumeCard.addView(volumeHead)
        volumeBar = SeekBar(this).apply {
            max = 200
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            thumbTintList = android.content.res.ColorStateList.valueOf(CYAN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { applyVolume(progress) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        volumeCard.addView(volumeBar)
        val volumeParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(94))
        volumeParams.bottomMargin = dp(16)
        root.addView(volumeCard, volumeParams)

        val cast = pill("إرسال للشاشة   ▣", GREEN, 0xFF20E4B5.toInt()) { openScreenCast() }
        cast.textSize = 20f
        root.addView(cast, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply {
            bottomMargin = dp(14)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottom.addView(pill("إعادة الاتصال", 0xFF075EB8.toInt(), CYAN) { restartStream() },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        bottom.addView(pill("ملء الشاشة", 0xFF102C50.toInt(), 0xFF1B5C8B.toInt()) { toggleFullscreen() },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        root.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))

        root.addView(text("بوابة الخدمات الرقمية المتكاملة • شبكة الكامل", 12f, MUTED).apply {
            setPadding(0, dp(20), 0, dp(6))
        })
        scroll.addView(root)
        setContentView(scroll)
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
        player?.volume = level.coerceAtMost(100) / 100f
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
        fullscreen = !fullscreen
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (fullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
