package com.alkamel.network

import android.app.Activity
import android.graphics.Color
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class HdPlayerActivity : Activity() {
    companion object {
        private const val STREAM_URL = "http://10.10.10.10:8888/live/index.m3u8"
        private const val BG = 0xFF071426.toInt()
        private const val CYAN = 0xFF25C8E8.toInt()
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

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(14, 18, 14, 16)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "رجوع"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "بث الكامل HD  •  مباشر"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        header.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = 12; bottomMargin = 12 })

        status = TextView(this).apply {
            text = "جارٍ الاتصال بالبث..."
            textSize = 13f
            setTextColor(CYAN)
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
        }
        root.addView(status)

        val volumeHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeHeading.addView(TextView(this).apply {
            text = "مستوى الصوت"
            textSize = 15f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        volumeValue = TextView(this).apply {
            text = "100%"
            textSize = 15f
            setTextColor(CYAN)
        }
        volumeHeading.addView(volumeValue)
        root.addView(volumeHeading)

        volumeBar = SeekBar(this).apply {
            max = 200
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            thumbTintList = android.content.res.ColorStateList.valueOf(CYAN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    applyVolume(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(volumeBar)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val reconnect = Button(this).apply {
            text = "إعادة الاتصال"
            setOnClickListener { restartStream() }
        }
        val full = Button(this).apply {
            text = "ملء الشاشة"
            setOnClickListener { toggleFullscreen() }
        }
        actions.addView(reconnect, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(full, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        setContentView(root)
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
                    Player.STATE_READY -> if (exo.isPlaying) "البث يعمل الآن" else "تم الاتصال؛ اضغط تشغيل"
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
        val item = MediaItem.Builder()
            .setUri(STREAM_URL)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
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
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
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
