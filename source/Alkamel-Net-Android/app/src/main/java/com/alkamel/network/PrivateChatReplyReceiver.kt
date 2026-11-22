package com.alkamel.network

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class PrivateChatReplyReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.alkamel.network.action.PRIVATE_CHAT_REPLY"
        const val KEY_TEXT = "private_chat_reply_text"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PEER_HOST = "peer_host"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)
            ?.toString()?.trim()?.take(1000).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val host = intent.getStringExtra(EXTRA_PEER_HOST).orEmpty()
        if (reply.isBlank() || peerId.isBlank() || requestId.isBlank() || host.isBlank()) return

        val pending = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences("alkamel_private_chat", Context.MODE_PRIVATE)
                val senderId = prefs.getString("chat_id", null) ?: return@Thread
                val nickname = prefs.getString("chat_nickname", "عضو") ?: "عضو"
                val payload = JSONObject()
                    .put("type", "private_message")
                    .put("id", senderId)
                    .put("name", nickname)
                    .put("to", peerId)
                    .put("requestId", requestId)
                    .put("text", reply)
                    .toString().toByteArray(Charsets.UTF_8)
                DatagramSocket(null).use { socket ->
                    socket.soTimeout = 3000
                    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val wifi = connectivity.allNetworks.firstOrNull { network ->
                        connectivity.getNetworkCapabilities(network)
                            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && wifi != null) {
                        runCatching { wifi.bindSocket(socket) }
                            .onFailure { Log.w("AlkamelNet", "Could not bind notification reply to Wi-Fi", it) }
                    }
                    socket.bind(java.net.InetSocketAddress(0))
                    val address = InetAddress.getByName(host)
                    socket.send(DatagramPacket(payload, payload.size, address, 39127))
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(notificationId)
                Log.i("AlkamelNet", "Private chat notification reply sent to $peerId")
            } catch (e: Exception) {
                Log.e("AlkamelNet", "Private chat notification reply failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
