package com.alkamel.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

class FirewallBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = context.getSharedPreferences(AppFirewallVpnService.PREFS, Context.MODE_PRIVATE)
        val blocked = prefs.getStringSet(AppFirewallVpnService.BLOCKED_PACKAGES, emptySet())?.toSet().orEmpty()
        if (blocked.isEmpty()) return

        // VPN consent is retained by Android. Do not attempt to bypass the system consent screen.
        if (VpnService.prepare(context) != null) {
            Log.w("AlkamelFirewall", "VPN consent is not available after boot; open the app to re-enable firewall")
            return
        }

        try {
            val serviceIntent = Intent(context, AppFirewallVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e("AlkamelFirewall", "Unable to restore firewall after boot", e)
        }
    }
}
