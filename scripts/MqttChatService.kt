package com.alkamel.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class MqttChatService : Service() {
    companion object {
        const val ACTION_CONNECT = "com.alkamel.network.mqtt.CONNECT"
        const val ACTION_PUBLISH = "com.alkamel.network.mqtt.PUBLISH"
        const val ACTION_EVENT = "com.alkamel.network.mqtt.EVENT"
        const val EXTRA_NAME = "name"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TARGET = "target"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_RAW = "raw"
        const val EXTRA_OPEN_CHAT = "open_chat"
        const val EXTRA_CHAT_KIND = "chat_kind"
        private const val CHANNEL_ID = "alkamel_mqtt_service"
        private const val NOTIFICATION_ID = 39128
        private const val GROUP_TOPIC = "alkamel/chat/room"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    @Volatile private var connecting = false
    @Volatile private var requested = false
    private var nickname = ""
    private data class PendingPublish(val type: String, val text: String, val targetId: String?, val requestId: String?)
    private val pendingPublishes = ArrayDeque<PendingPublish>()
    private val deviceId by lazy {
        getSharedPreferences("alkamel_mqtt", MODE_PRIVATE).getString("device_id", null)
            ?: UUID.randomUUID().toString().also {
                getSharedPreferences("alkamel_mqtt", MODE_PRIVATE).edit().putString("device_id", it).apply()
            }
    }
    private val presence = object : Runnable {
        override fun run() {
            if (requested && client?.isConnected == true) {
                publish("presence", "")
                handler.postDelayed(this, 5000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "اتصال دردشة شبكة الكامل", NotificationManager.IMPORTANCE_LOW)
            channel.description = "استمرار اتصال الدردشة عند الانتقال إلى تطبيق آخر"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun foreground() {
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("دردشة شبكة الكامل متصلة")
            .setContentText("الاتصال يعمل في الخلفية")
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUBLISH -> {
                val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
                if (type in setOf("message", "voice_message", "gift", "profile_photo", "private_request", "private_response", "private_message", "voice_seat")) {
                    publish(type, intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                        intent.getStringExtra(EXTRA_TARGET), intent.getStringExtra(EXTRA_REQUEST_ID))
                }
            }
            ACTION_CONNECT -> {
                nickname = intent.getStringExtra(EXTRA_NAME).orEmpty().take(24)
                getSharedPreferences("alkamel_mqtt", MODE_PRIVATE).edit().putString("nickname", nickname).apply()
                requested = true
                foreground()
                // Restore joined state when this service already has a live broker connection.
                if (client?.isConnected == true) subscribeAndAnnounce(client!!)
                else connect()
            }
            else -> {
                val prefs = getSharedPreferences("alkamel_mqtt", MODE_PRIVATE)
                nickname = prefs.getString("nickname", "").orEmpty()
                if (nickname.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                requested = true
                foreground()
                connect()
            }
        }
        return START_STICKY
    }

    private fun subscribeAndAnnounce(mqtt: MqttAsyncClient) {
        if (!requested || !mqtt.isConnected) return
        try {
            mqtt.subscribe(arrayOf(GROUP_TOPIC, "alkamel/chat/private/$deviceId"), intArrayOf(1, 1), null, null)
            publish("presence", "")
            while (pendingPublishes.isNotEmpty() && mqtt.isConnected) {
                val pending = pendingPublishes.removeFirst()
                publishNow(mqtt, pending.type, pending.text, pending.targetId, pending.requestId)
            }
            handler.removeCallbacks(presence)
            handler.postDelayed(presence, 5000L)
            emitState("joined", "")
        } catch (e: Exception) {
            Log.e("AlkamelNet-MQTT", "Subscribe failed", e)
            emitState("error", "اتصل الخادم لكن تعذر الاشتراك في غرفة الدردشة.")
        }
    }

    private fun connect() {
        if (!requested || connecting) return
        if (client?.isConnected == true) {
            subscribeAndAnnounce(client!!)
            return
        }
        val prefs = getSharedPreferences("alkamel_mqtt", MODE_PRIVATE)
        val host = prefs.getString("host", "10.10.10.10").orEmpty()
        val port = prefs.getInt("port", 1883)
        connecting = true
        emitState("reconnecting", "جارٍ الاتصال بخادم الدردشة…")
        worker.execute {
            try {
                val mqtt = MqttAsyncClient("tcp://$host:$port", deviceId, MemoryPersistence())
                client = mqtt
                mqtt.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        connecting = false
                        if (!requested) return
                        subscribeAndAnnounce(mqtt)
                    }
                    override fun connectionLost(cause: Throwable?) {
                        if (requested) emitState("reconnecting", "انقطع اتصال الخادم، جارٍ إعادة الاتصال…")
                    }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val raw = message?.payload?.toString(Charsets.UTF_8) ?: return
                        try {
                            val obj = JSONObject(raw)
                            if (obj.optString("id") == deviceId) return
                            if (topic == "alkamel/chat/private/$deviceId" && obj.optString("to") != deviceId) return
                            val type = obj.optString("type")
                            if (type in setOf("presence", "message", "voice_message", "gift", "profile_photo", "voice_seat", "private_request", "private_response", "private_message")) {
                                if (type == "message" || type == "private_message" || type == "private_request" || type == "gift") notifyIncoming(obj)
                                emitPacket(raw)
                            }
                        } catch (e: Exception) { Log.w("AlkamelNet-MQTT", "Invalid packet", e) }
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val options = MqttConnectOptions().apply {
                    userName = String(android.util.Base64.decode(BuildConfig.MQTT_USERNAME_B64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    password = String(android.util.Base64.decode(BuildConfig.MQTT_PASSWORD_B64, android.util.Base64.DEFAULT), Charsets.UTF_8).toCharArray()
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    setMaxInflight(50)
                }
                mqtt.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) { connecting = false }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        connecting = false
                        Log.e("AlkamelNet-MQTT", "Connection failed", exception)
                        emitState("error", "تعذر الاتصال بخادم الدردشة. تحقق من بيانات الدخول وتشغيل Mosquitto.")
                    }
                })
            } catch (e: Exception) {
                connecting = false
                Log.e("AlkamelNet-MQTT", "Unable to create MQTT client: ${e.javaClass.simpleName}: ${e.message}", e)
                emitState("error", "تعذر بدء اتصال MQTT: ${e.javaClass.simpleName}: ${e.message ?: "لا توجد تفاصيل"}")
            }
        }
    }

    private fun publish(type: String, text: String, targetId: String? = null, requestId: String? = null) {
        val maxPayloadChars = when (type) { "voice_message" -> 1_200_000; "profile_photo" -> 78_000; else -> 1000 }
        if (text.length > maxPayloadChars) {
            Log.w("AlkamelNet-MQTT", "Rejected oversized $type payload")
            return
        }
        val mqtt = client
        if (mqtt == null || !mqtt.isConnected) {
            if (pendingPublishes.size >= 30) pendingPublishes.removeFirst()
            pendingPublishes.addLast(PendingPublish(type, text, targetId, requestId))
            Log.i("AlkamelNet-MQTT", "Queued $type until broker connection is ready")
            if (!requested) {
                nickname = getSharedPreferences("alkamel_mqtt", MODE_PRIVATE).getString("nickname", "").orEmpty()
                if (nickname.isNotBlank()) {
                    requested = true
                    foreground()
                    connect()
                }
            }
            return
        }
        publishNow(mqtt, type, text, targetId, requestId)
    }

    private fun publishNow(mqtt: MqttAsyncClient, type: String, text: String, targetId: String?, requestId: String?) {
        if (!mqtt.isConnected) {
            if (pendingPublishes.size >= 30) pendingPublishes.removeFirst()
            pendingPublishes.addLast(PendingPublish(type, text, targetId, requestId))
            return
        }
        val packet = JSONObject().put("type", type).put("id", deviceId).put("name", nickname).put("text", text)
        if (!targetId.isNullOrBlank()) packet.put("to", targetId)
        if (!requestId.isNullOrBlank()) packet.put("requestId", requestId)
        if (type == "message") packet.put("messageId", UUID.randomUUID().toString())
        val topic = if (type.startsWith("private_") && !targetId.isNullOrBlank()) "alkamel/chat/private/$targetId" else GROUP_TOPIC
        runCatching {
            mqtt.publish(topic, MqttMessage(packet.toString().toByteArray(Charsets.UTF_8)).apply { qos = 1; isRetained = false })
        }.onFailure { Log.w("AlkamelNet-MQTT", "$type publish failed", it) }
    }

    private fun emitState(state: String, detail: String) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_STATE, state).putExtra(EXTRA_DETAIL, detail))
    }

    private fun emitPacket(raw: String) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_RAW, raw))
    }

    private fun notifyIncoming(obj: JSONObject) {
        val type = obj.optString("type")
        val sender = obj.optString("name", "عضو")
        val message = obj.optString("text").trim()
        val private = type == "private_message" && obj.optString("to") == deviceId
        val request = type == "private_request" && obj.optString("to") == deviceId
        val mentioned = nickname.isNotBlank() && message.contains(nickname, ignoreCase = true)
        if (!private && !request && !(type == "message" && mentioned)) return
        val title = when {
            request -> "طلب محادثة خاصة جديد"
            private -> "رسالة خاصة جديدة من $sender"
            else -> "تم ذكرك في دردشة شبكة الكامل"
        }
        val body = if (request) "يريد $sender بدء محادثة خاصة معك." else "$sender: $message"
        val manager = getSystemService(NotificationManager::class.java)
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "alkamel_events")
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.alkamel.network.OPEN_CHAT_NOTIFICATION"
            putExtra(EXTRA_OPEN_CHAT, true)
            putExtra(EXTRA_CHAT_KIND, type)
            putExtra(EXTRA_TARGET, obj.optString("id"))
            putExtra(EXTRA_NAME, sender)
            putExtra(EXTRA_REQUEST_ID, obj.optString("requestId"))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val open = PendingIntent.getActivity(this, (System.currentTimeMillis() % 2000000000L).toInt(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        manager.notify((System.currentTimeMillis() % 2000000000L).toInt(),
            notification.setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title)
                .setContentText(body).setAutoCancel(true).setContentIntent(open).build())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        requested = false
        handler.removeCallbacks(presence)
        runCatching { client?.let { if (it.isConnected) it.disconnect(); it.close() } }
        client = null
        worker.shutdownNow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
