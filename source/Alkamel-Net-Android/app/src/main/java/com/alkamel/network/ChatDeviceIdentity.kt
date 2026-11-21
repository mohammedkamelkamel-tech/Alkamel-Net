package com.alkamel.network

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Per-installation MQTT identity.
 *
 * The identifier is stored under noBackupFilesDir rather than SharedPreferences:
 * Android backup/restore and device-to-device transfer must not copy an MQTT
 * Client ID to another phone. MainActivity and MqttChatService share this helper.
 */
internal object ChatDeviceIdentity {
    @Synchronized
    fun get(context: Context): String {
        val app = context.applicationContext
        val idFile = File(app.noBackupFilesDir, "alkamel_chat_installation_id")

        val existing = runCatching {
            if (idFile.isFile) idFile.readText(Charsets.UTF_8).trim() else ""
        }.getOrDefault("")

        if (existing.matches(Regex("alk-[0-9a-f]{32}"))) return existing

        val freshId = "alk-" + UUID.randomUUID().toString().replace("-", "")
        runCatching {
            idFile.parentFile?.mkdirs()
            idFile.writeText(freshId, Charsets.UTF_8)
        }.getOrElse {
            // Last-resort stable-in-process ID if storage is unexpectedly unavailable.
            return freshId
        }

        // Keep a compatibility copy for diagnostics only. It is never read as
        // the authoritative MQTT Client ID, so restored preferences cannot collide.
        runCatching {
            app.getSharedPreferences("alkamel_mqtt", Context.MODE_PRIVATE)
                .edit().putString("device_id", freshId).apply()
        }
        return freshId
    }
}
