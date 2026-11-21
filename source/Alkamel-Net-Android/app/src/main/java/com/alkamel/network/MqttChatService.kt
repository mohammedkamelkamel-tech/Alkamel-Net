package com.alkamel.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.graphics.Color
import android.graphics.BitmapFactory
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
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
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
        private const val CHAT_NOTIFICATION_CHANNEL = "alkamel_chat_messages_v3"
        private const val NOTIFICATION_ID = 39128
        private const val GROUP_TOPIC = "alkamel/chat/room"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    @Volatile private var connecting = false
    @Volatile private var requested = false
    private var nickname = ""
    @Volatile private var retryAttempt = 0
    @Volatile private var subscribing = false
    private val retryConnect = object : Runnable {
        override fun run() {
            if (requested && client?.isConnected != true && !connecting) connect()
        }
    }
    private data class PendingPublish(val type: String, val text: String, val targetId: String?, val requestId: String?)
    private val pendingPublishes = ArrayDeque<PendingPublish>()
    private val deviceId by lazy { ChatDeviceIdentity.get(this) }
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
                runCatching { foreground() }
                val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
                if (type in setOf("message", "voice_message", "gift", "profile_photo", "private_request", "private_response", "private_message", "voice_seat", "voice_signal")) {
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
        if (!requested || !mqtt.isConnected || subscribing) return
        subscribing = true
        try {
            mqtt.subscribe(arrayOf(GROUP_TOPIC, "alkamel/chat/private/$deviceId"), intArrayOf(1, 1), null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        subscribing = false
                        if (!requested || client !== mqtt || !mqtt.isConnected) return
                        retryAttempt = 0
                        publish("presence", "")
                        synchronized(pendingPublishes) {
                            while (pendingPublishes.isNotEmpty() && mqtt.isConnected) {
                                val pending = pendingPublishes.removeFirst()
                                publishNow(mqtt, pending.type, pending.text, pending.targetId, pending.requestId)
                            }
                        }
                        handler.removeCallbacks(presence)
                        handler.postDelayed(presence, 5000L)
                        emitState("joined", "")
                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        subscribing = false
                        Log.e("AlkamelNet-MQTT", "Subscribe failed; clientId=$deviceId; reason=${exception?.message}", exception)
                        emitState("reconnecting", "اتصل الخادم لكن تعذر الاشتراك؛ ستتم إعادة المحاولة…")
                        scheduleReconnect()
                    }
                })
        } catch (e: Exception) {
            subscribing = false
            Log.e("AlkamelNet-MQTT", "Subscribe request failed", e)
            emitState("reconnecting", "تعذر الاشتراك في غرفة الدردشة؛ ستتم إعادة المحاولة…")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!requested) return
        handler.removeCallbacks(retryConnect)
        val delay = (3000L shl retryAttempt.coerceAtMost(4)).coerceAtMost(45000L)
        retryAttempt = (retryAttempt + 1).coerceAtMost(5)
        handler.postDelayed(retryConnect, delay)
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
                val persistenceDir = java.io.File(filesDir, "mqtt-persistence").apply { mkdirs() }
                val mqtt = MqttAsyncClient("tcp://$host:$port", deviceId, MqttDefaultFilePersistence(persistenceDir.absolutePath))
                client = mqtt
                mqtt.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        connecting = false
                        if (!requested) return
                        subscribeAndAnnounce(mqtt)
                    }
                    override fun connectionLost(cause: Throwable?) {
                        Log.e("AlkamelNet-MQTT", "MQTT connection lost; clientId=$deviceId; reason=${cause?.javaClass?.simpleName}: ${cause?.message}", cause)
                        if (requested) emitState("reconnecting", "انقطع اتصال الخادم مؤقتاً، جارٍ إعادة الاتصال…")
                    }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val raw = message?.payload?.toString(Charsets.UTF_8) ?: return
                        try {
                            val obj = JSONObject(raw)
                            if (obj.optString("id") == deviceId) return
                            if (topic == "alkamel/chat/private/$deviceId" && obj.optString("to") != deviceId) return
                            val type = obj.optString("type")
                            if (type in setOf("presence", "message", "voice_message", "gift", "profile_photo", "voice_seat", "voice_signal", "private_request", "private_response", "private_message")) {
                                if (type == "message" && topic == GROUP_TOPIC) {
                                    // Persist before broadcasting: the Activity/WebView may be paused or destroyed.
                                    ChatMessageStore.saveIncoming(this@MqttChatService, raw)
                                }
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
                    isCleanSession = false
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    setMaxInflight(50)
                }
                mqtt.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) { connecting = false }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        connecting = false
                        Log.e("AlkamelNet-MQTT", "Connection failed; clientId=$deviceId; reason=${exception?.javaClass?.simpleName}: ${exception?.message}", exception)
                        val reason = exception?.message?.take(100).orEmpty()
                        emitState("reconnecting", if (reason.isBlank()) "تعذر الاتصال بخادم الدردشة؛ ستتم إعادة المحاولة تلقائياً." else "فشل اتصال MQTT مؤقتاً: $reason — جارٍ إعادة المحاولة.")
                        runCatching { mqtt.close() }
                        if (client === mqtt) client = null
                        scheduleReconnect()
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
        val maxPayloadChars = when (type) { "voice_message" -> 1_200_000; "profile_photo" -> 78_000; "voice_signal" -> 24_000; else -> 1000 }
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
        val topic = if ((type.startsWith("private_") || type == "voice_signal") && !targetId.isNullOrBlank()) "alkamel/chat/private/$targetId" else GROUP_TOPIC
        runCatching {
            val message = MqttMessage(packet.toString().toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = false
            }
            mqtt.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d("AlkamelNet-MQTT", "$type publish accepted; clientId=$deviceId")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e("AlkamelNet-MQTT", "$type publish failed; clientId=$deviceId; reason=${exception?.message}", exception)
                    if (requested) emitState("reconnecting", "تعذر إرسال رسالة مؤقتاً؛ جارٍ استعادة الاتصال…")
                }
            })
        }.onFailure {
            Log.e("AlkamelNet-MQTT", "$type publish could not be queued; clientId=$deviceId", it)
            if (requested) emitState("reconnecting", "تعذر إرسال رسالة مؤقتاً؛ جارٍ استعادة الاتصال…")
        }
    }

    private fun emitState(state: String, detail: String) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_STATE, state).putExtra(EXTRA_DETAIL, detail))
    }

    private fun emitPacket(raw: String) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_RAW, raw))
    }

    private fun ensureChatNotificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHAT_NOTIFICATION_CHANNEL) == null) {
                val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audio = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val channel = NotificationChannel(
                    CHAT_NOTIFICATION_CHANNEL,
                    "رسائل شبكة الكامل",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "رسائل الدردشة مع تنبيه صوتي واهتزاز مميز"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 180, 90, 180)
                    enableLights(true)
                    lightColor = Color.rgb(42, 205, 184)
                    setSound(sound, audio)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
        return CHAT_NOTIFICATION_CHANNEL
    }

    private fun notifyIncoming(obj: JSONObject) {
        if (getSharedPreferences("alkamel_app_state", MODE_PRIVATE).getBoolean("foreground", false)) return
        val type = obj.optString("type")
        val senderName = obj.optString("name", "عضو").ifBlank { "عضو" }
        val message = obj.optString("text").trim()
        val privateMessage = type == "private_message" && obj.optString("to") == deviceId
        val privateRequest = type == "private_request" && obj.optString("to") == deviceId
        val groupMessage = type == "message" && obj.optString("id") != deviceId
        if (!groupMessage && !privateMessage && !privateRequest) return
        if (message.isBlank() && !privateRequest) return

        val conversationTitle = when {
            privateRequest -> "طلب محادثة خاصة"
            privateMessage -> "محادثة خاصة"
            else -> "الدردشة الجماعية"
        }
        val displayMessage = if (privateRequest) "يريدك $senderName لبدء محادثة خاصة" else message
        val notificationId = when {
            privateRequest -> 7103
            privateMessage -> 7102
            else -> 7101
        }
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.alkamel.network.OPEN_CHAT_NOTIFICATION"
            putExtra(EXTRA_OPEN_CHAT, true)
            putExtra(EXTRA_CHAT_KIND, type)
            putExtra(EXTRA_TARGET, obj.optString("id"))
            putExtra(EXTRA_NAME, senderName)
            putExtra(EXTRA_REQUEST_ID, obj.optString("requestId"))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val open = PendingIntent.getActivity(this, notificationId, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, ensureChatNotificationChannel())
        else @Suppress("DEPRECATION") Notification.Builder(this)

        val notification = builder
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_launcher))
            .setColor(Color.rgb(27, 117, 230))
            .setContentTitle(senderName)
            .setContentText(displayMessage.take(180))
            .setSubText("شبكة الكامل • $conversationTitle")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(open)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val appPerson = android.app.Person.Builder()
                .setName("شبكة الكامل")
                .setKey("alkamel_network")
                .build()
            val senderPerson = android.app.Person.Builder()
                .setName(senderName)
                .setKey(if (privateMessage) "private_${obj.optString("id")}" else "member_${obj.optString("id")}")
                .build()
            val style = Notification.MessagingStyle(appPerson)
                .setConversationTitle("شبكة الكامل • $conversationTitle")
                .addMessage(displayMessage.take(500), System.currentTimeMillis(), senderPerson)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                style.setGroupConversation(!privateMessage)
            }
            notification.setStyle(style)
        } else {
            notification.setStyle(Notification.BigTextStyle()
                .setBigContentTitle(senderName)
                .bigText(displayMessage.take(500)))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            notification.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            notification.setVibrate(longArrayOf(0, 180, 90, 180))
        }

        if (!privateRequest) {
            val replyIntent = Intent(this, ChatReplyReceiver::class.java).apply {
                action = ChatReplyReceiver.ACTION_REPLY
                putExtra(ChatReplyReceiver.EXTRA_REPLY_KIND, if (privateMessage) "private_message" else "message")
                putExtra(ChatReplyReceiver.EXTRA_REPLY_TARGET, if (privateMessage) obj.optString("id") else "")
            }
            val replyPending = PendingIntent.getBroadcast(this, notificationId, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val remoteInput = android.app.RemoteInput.Builder(ChatReplyReceiver.KEY_TEXT)
                .setLabel("اكتب ردك…").build()
            notification.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_send, "رد", replyPending
            ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build())
        }
        getSystemService(NotificationManager::class.java).notify(notificationId, notification.build())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        requested = false
        handler.removeCallbacks(presence)
        handler.removeCallbacks(retryConnect)
        subscribing = false
        runCatching { client?.let { if (it.isConnected) it.disconnect(); it.close() } }
        client = null
        worker.shutdownNow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
