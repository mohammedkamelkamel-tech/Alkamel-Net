package com.alkamel.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class AppFirewallVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "جدار التطبيقات", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("منع بعض التطبيقات من استخدام الإنترنت")
            .setContentText("حظر التطبيقات المحددة يعمل في الخلفية")
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        establishFirewall()
        // Android may recreate this service after reclaiming its process.
        return START_STICKY
    }

    @Synchronized
    private fun establishFirewall() {
        if (running.get() && tunnel != null) return
        closeTunnel()
        val blocked = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        if (blocked.isEmpty()) {
            stopForeground(true)
            stopSelf()
            return
        }

        try {
            val builder = Builder()
                .setSession("شبكة الكامل - جدار التطبيقات")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:111:111::1", 128)
                .addRoute("::", 0)

            blocked.filter { it != packageName }.forEach { builder.addAllowedApplication(it) }
            tunnel = builder.establish() ?: throw IllegalStateException("VPN tunnel unavailable")
            val fd = tunnel!!.fileDescriptor
            running.set(true)
            worker = Thread({
                try {
                    FileInputStream(fd).use { input ->
                        val packet = ByteArray(32767)
                        while (running.get()) {
                            if (input.read(packet) < 0) break
                            // Selected-app packets are intentionally not forwarded.
                        }
                    }
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Firewall packet loop failed", e)
                } finally {
                    if (running.get()) {
                        running.set(false)
                        Log.w(TAG, "Firewall tunnel ended unexpectedly")
                        stopSelf()
                    }
                }
            }, "Alkamel-App-Firewall").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Firewall active for ${blocked.size} selected apps")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start app firewall", e)
            closeTunnel()
            stopForeground(true)
            stopSelf()
        }
    }

    @Synchronized
    private fun closeTunnel() {
        running.set(false)
        runCatching { tunnel?.close() }
        tunnel = null
        worker?.interrupt()
        worker = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground VPN service alive when the user swipes the app task away.
        Log.i(TAG, "App task removed; firewall service remains active")
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by Android or another VPN selected")
        closeTunnel()
        stopForeground(true)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        const val PREFS = "app_firewall"
        const val BLOCKED_PACKAGES = "blocked_packages"
        private const val CHANNEL = "alkamel_app_firewall"
        private const val NOTIFICATION_ID = 7421
        private const val TAG = "AlkamelFirewall"
    }
}
