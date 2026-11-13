package com.alkamel.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * A selective local VPN used only as a packet sink for apps explicitly selected by the user.
 * Only selected packages are routed into this VPN; all other apps keep their normal network path.
 * Packets are intentionally not forwarded, so selected apps cannot reach the Internet.
 */
class AppBlockVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val channelId = "alkamel_app_block_vpn"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(7415, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(7415, notification)
        }

        val packages = intent?.getStringArrayListExtra("packages")
            ?: getSharedPreferences("alkamel_app_block", MODE_PRIVATE)
                .getStringSet("packages", emptySet()).orEmpty().toCollection(ArrayList())

        if (packages.isEmpty()) {
            stopVpn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val builder = Builder()
                .setSession("شبكة الكامل - حظر التطبيقات")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addAddress("fd00:1111::1", 128)
                builder.addRoute("::", 0)
            }

            var added = 0
            packages.distinct().forEach { packageName ->
                try {
                    builder.addAllowedApplication(packageName)
                    added++
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    Log.w("AlkamelAppBlock", "Skipped missing package $packageName", e)
                }
            }
            if (added == 0) throw IllegalStateException("No valid application selected")

            val next = builder.establish() ?: throw IllegalStateException("VPN permission was revoked")
            val old = vpnInterface
            vpnInterface = next
            runCatching { old?.close() }
            Log.i("AlkamelAppBlock", "Selective app blocking active for $added packages")
        } catch (e: Exception) {
            Log.e("AlkamelAppBlock", "Unable to establish selective VPN", e)
            stopVpn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    private fun stopVpn() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "حظر التطبيقات", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "يعمل عند منع التطبيقات المحددة من الوصول إلى الإنترنت"
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("شبكة الكامل")
            .setContentText("حظر الإنترنت عن التطبيقات التي حددتها")
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopVpn()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
