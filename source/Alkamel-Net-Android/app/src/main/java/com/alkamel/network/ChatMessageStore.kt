package com.alkamel.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable inbox for group-chat packets received while the WebView is paused or absent.
 * MQTT QoS delivery is not a substitute for an application-level message history.
 */
object ChatMessageStore {
    private const val PREFS = "alkamel_chat_inbox"
    private const val KEY_MESSAGES = "group_messages"
    private const val LIMIT = 500

    @Synchronized
    fun saveIncoming(context: Context, raw: String) {
        try {
            val packet = JSONObject(raw)
            if (packet.optString("type") != "message") return
            val messageId = packet.optString("messageId")
            if (messageId.isBlank()) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val rows = JSONArray(prefs.getString(KEY_MESSAGES, "[]") ?: "[]")
            for (i in 0 until rows.length()) {
                if (rows.optJSONObject(i)?.optString("messageId") == messageId) return
            }
            val row = JSONObject()
                .put("messageId", messageId)
                .put("id", packet.optString("id"))
                .put("name", packet.optString("name", "عضو"))
                .put("senderId", packet.optString("id"))
                .put("text", packet.optString("text"))
                .put("time", System.currentTimeMillis())
                .put("mentionId", packet.optString("mentionId"))
                .put("mentionName", packet.optString("mentionName"))
            val next = JSONArray()
            val start = (rows.length() - LIMIT + 1).coerceAtLeast(0)
            for (i in start until rows.length()) rows.optJSONObject(i)?.let(next::put)
            next.put(row)
            prefs.edit().putString(KEY_MESSAGES, next.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.w("AlkamelNet-ChatStore", "Unable to persist incoming chat message", e)
        }
    }

    @Synchronized
    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MESSAGES, "[]") ?: "[]"
    }
}
