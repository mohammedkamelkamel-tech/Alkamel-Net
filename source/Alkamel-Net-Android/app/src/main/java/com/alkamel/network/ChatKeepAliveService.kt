package com.alkamel.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.PackageManager
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ChatKeepAliveService : Service() {
    private val adsChecker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var adsCheckStarted = false
    companion object {
        const val ACTION_START = "com.alkamel.network.action.START_CHAT"
        const val ACTION_STOP = "com.alkamel.network.action.STOP_CHAT"
        private const val CHANNEL_ID = "alkamel_chat_connection"
        private const val NOTIFICATION_ID = 39127
        private const val EVENTS_CHANNEL_ID = "alkamel_events"
        private const val ADS_NOTIFICATION_ID = 48120
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "اتصال شبكة الكامل",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار دائم أثناء استمرار الدردشة وغرفة الصوت في الخلفية"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val events = NotificationChannel(EVENTS_CHANNEL_ID, "تنبيهات شبكة الكامل", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعارات الإعلانات والعروض الجديدة"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(events)
        }
        startAdsUpdateMonitor()
    }

    private fun startAdsUpdateMonitor() {
        if (adsCheckStarted) return
        adsCheckStarted = true
        adsChecker.scheduleWithFixedDelay({
            try { checkForNewAds() } catch (e: Exception) { Log.w("AlkamelNet", "Advertisement update check failed", e) }
        }, 30, 300, TimeUnit.SECONDS)
    }

    private fun checkForNewAds() {
        val connection = (URL("http://k.com/config.js").openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
        }
        val body = try {
            if (connection.responseCode !in 200..299) return
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
        val count = Regex("""imageCount\s*[:=]\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
        val enabled = !Regex("""imageV\s*[:=]\s*false""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        if (!enabled || count <= 0) return
        val prefs = getSharedPreferences("alkamel_ads_monitor", MODE_PRIVATE)
        val previous = prefs.getInt("last_image_count", -1)
        prefs.edit().putInt("last_image_count", count).apply()
        if (previous >= 0 && count > previous) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val openApp = PendingIntent.getActivity(
                this, ADS_NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, EVENTS_CHANNEL_ID) else {
                @Suppress("DEPRECATION") Notification.Builder(this)
            }
            val notification = builder.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("إعلان جديد في شبكة الكامل")
                .setContentText("تمت إضافة ${count - previous} صورة جديدة إلى قسم الإعلانات والعروض.")
                .setAutoCancel(true).setContentIntent(openApp).build()
            getSystemService(NotificationManager::class.java).notify(ADS_NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val openApp = PendingIntent.getActivity(
            this, 39127,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("شبكة الكامل تعمل في الخلفية")
            .setContentText("خدمات الشبكة والدردشة متاحة أثناء استخدام تطبيقات أخرى")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Preserve the foreground service when the app task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        adsChecker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
