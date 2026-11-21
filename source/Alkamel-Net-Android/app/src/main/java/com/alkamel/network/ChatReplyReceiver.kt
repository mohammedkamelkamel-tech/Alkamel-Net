package com.alkamel.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.RemoteInput
import android.os.Build
import android.util.Log

class ChatReplyReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.alkamel.network.chat.INLINE_REPLY"
        const val KEY_TEXT = "chat_reply_text"
        const val EXTRA_REPLY_KIND = "reply_kind"
        const val EXTRA_REPLY_TARGET = "reply_target"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)
            ?.toString()?.trim().orEmpty()
        if (reply.isBlank()) return
        val kind = intent.getStringExtra(EXTRA_REPLY_KIND).orEmpty()
        val target = intent.getStringExtra(EXTRA_REPLY_TARGET).orEmpty()
        if (kind != "message" && kind != "private_message") return
        if (kind == "private_message" && target.isBlank()) return

        val serviceIntent = Intent(context, MqttChatService::class.java).apply {
            action = MqttChatService.ACTION_PUBLISH
            putExtra(MqttChatService.EXTRA_TYPE, kind)
            putExtra(MqttChatService.EXTRA_TEXT, reply.take(1000))
            if (kind == "private_message") putExtra(MqttChatService.EXTRA_TARGET, target)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e("AlkamelNet", "Unable to send inline chat reply", e)
        }
    }
}
