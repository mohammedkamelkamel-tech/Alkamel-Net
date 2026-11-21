package com.alkamel.network

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable per-device chat identity shared by MainActivity and MqttChatService.
 * The raw Android ID is never transmitted; only a short SHA-256-derived ID is used.
 */
internal object ChatDeviceIdentity {
    fun get(context: Context): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("alkamel_mqtt", Context.MODE_PRIVATE)
        val androidId = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }

        val stableId = if (androidId != null) {
            val source = "${app.packageName}|$androidId"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8))
            "alk-" + digest.take(16).joinToString("") { "%02x".format(it) }
        } else {
            "alk-" + UUID.randomUUID().toString().replace("-", "")
        }

        // Migrate older random IDs. Both the Activity and service call this same helper.
        val saved = prefs.getString("device_id", null)
        if (saved != stableId) {
            prefs.edit().putString("device_id", stableId).apply()
        }
        return stableId
    }
}
