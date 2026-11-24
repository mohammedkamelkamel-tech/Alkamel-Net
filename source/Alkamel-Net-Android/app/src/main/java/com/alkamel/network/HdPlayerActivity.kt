package com.alkamel.network

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
        private const val BG = 0xFF061326.toInt()
        private const val PANEL = 0xFF0B2342.toInt()
        private const val BLUE = 0xFF08BDEB.toInt()
        private const val MUTED = 0xFF9FB4CC.toInt()
        private const val GREEN = 0xFF00B88A.toInt()
    }

    private var player: ExoPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var volumeValue: TextView
    private lateinit var volumeBar: SeekBar
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildInterface()
        initializePlayer()
    }

    private fun panel(radius: Float, fill: Int, stroke: Int = 0xFF174D78.toInt()): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun actionButton(text: String, fill: Int, stroke: Int = fill, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            background = panel(18f, fill, stroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }

    private fun buildInterface() {
        val scroll = android.widget.ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(22))
            setBackgroundColor(BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = actionButton("‹", PANEL, 0xFF1B5C8B.toInt()) { finish() }
        back.textSize = 28f
        header.addView(back, LinearLayout.LayoutParams(dp(54), dp(48)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        brand.addView(label("شبكة الكامل", 22f, Color.WHITE, true))
        brand.addView(label("Alkamel Network", 11f, MUTED))
        header.addView(brand, LinearLayout.LayoutParams(0, dp(54), 1f))
        header.addView(label("● متصل", 13f, GREEN, true), LinearLayout.LayoutParams(dp(74), dp(48)))
        root.addView(header)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = panel(22f, PANEL)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        nav.addView(label("بث HD", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(label("نسخة رسمية", 13f, MUTED), LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(label("الكامل نت  ›", 14f, MUTED, true), LinearLayout.LayoutParams(0, dp(46), 1f))
        val navParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62))
        navParams.topMargin = dp(16)
        root.addView(nav, navParams)

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = panel(22f, PANEL)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        heading.addView(label("● بث مباشر", 14f, GREEN, true))
        heading.addView(label("بث مباشر الكامل HD", 23f, Color.WHITE, true))
        heading.addView(label("جودة عالية • تشغيل داخل التطبيق", 13f, MUTED))
        val headingParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112))
        headingParams.topMargin = dp(16)
        root.addView(heading, headingParams)

        val videoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(22f, Color.BLACK, 0xFF087DBB.toInt())
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
        }
        videoCard.addView(playerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(250)
        ))
        status = label("جارٍ الاتصال بالبث...", 14f, BLUE, true)
        status.setPadding(dp(8), dp(12), dp(8), dp(12))
        videoCard.addView(status)
        val videoParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        videoParams.topMargin = dp(14)
        root.addView(videoCard, videoParams)

        val volumeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(20f, PANEL)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val volumeHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeHeading.addView(label("مستوى الصوت", 15f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, dp(30), 1f))
        volumeValue = label("100%", 18f, BLUE, true)
        volumeHeading.addView(volumeValue, LinearLayout.LayoutParams(dp(72), dp(30)))
        volumeCard.addView(volumeHeading)
        volumeBar = SeekBar(this).apply {
            max = 200
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(BLUE)
            thumbTintList = android.content.res.ColorStateList.valueOf(BLUE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    applyVolume(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        volumeCard.addView(volumeBar)
        val volumeParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100))
        volumeParams.topMargin = dp(14)
        root.addView(volumeCard, volumeParams)

        val cast = actionButton("▣   إرسال للشاشة", GREEN, 0xFF20E4B5.toInt()) { openScreenCast() }
        cast.textSize = 19f
        val castParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66))
        castParams.topMargin = dp(16)
        root.addView(cast, castParams)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val reconnect = actionButton("إعادة الاتصال", 0xFF075EB8.toInt(), BLUE) { restartStream() }
        val full = actionButton("ملء الشاشة", 0xFF102C50.toInt(), 0xFF1B5C8B.toInt()) { toggleFullscreen() }
        bottom.addView(reconnect, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        bottom.addView(full, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        val bottomParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60))
        bottomParams.topMargin = dp(14)
        root.addView(bottom, bottomParams)

        root.addView(label("استمتع بمشاهدة بث HD من مشغّل شبكة الكامل", 12f, MUTED).apply {
            setPadding(0, dp(16), 0, dp(4))
        })
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun initializePlayer() {
        releasePlayer()
        status.text = "جارٍ تحميل بث HD..."
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
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
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
        player?.volume = (level.coerceAtMost(100) / 100f)
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

    private fun openScreenCast() {
        val intent = Intent("android.settings.CAST_SETTINGS")
        try {
            startActivity(intent)
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
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        } else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun releasePlayer() {
        try { loudness?.release() } catch (_: Exception) {}
        loudness = null
        playerView.player = null
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
