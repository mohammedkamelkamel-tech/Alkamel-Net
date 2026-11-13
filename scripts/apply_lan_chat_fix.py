#!/usr/bin/env python3
"""Apply the focused Wi-Fi interface repair to the uploaded 1.9.6 code80 source."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
kotlin = root / "app/src/main/java/com/alkamel/network/MainActivity.kt"
source = kotlin.read_text(encoding="utf-8")

def replace_once(old: str, new: str, label: str) -> None:
    global source
    if old not in source:
        raise SystemExit(f"Expected source block not found: {label}")
    source = source.replace(old, new, 1)

replace_once(
'''                val wifiNetwork = chatWifiNetwork()
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
''',
'''                val wifiNetwork = chatWifiNetwork()
                val wifiInterface = chatWifiInterfaceName(wifiNetwork)
                Log.i("AlkamelNet", "LAN chat receiver network=${wifiNetwork} interface=${wifiInterface ?: "unavailable"}")
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
''', "receiver diagnostics")

replace_once(
'''                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && wifiNetwork != null) {
                        runCatching { wifiNetwork.bindSocket(this) }
                            .onFailure { Log.w("AlkamelNet", "Unable to bind chat receiver to Wi-Fi network", it) }
                    }
''',
'''                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && wifiNetwork != null) {
                        // Do not silently continue on Android's default (possibly cellular)
                        // network when binding the LAN receiver fails.
                        wifiNetwork.bindSocket(this)
                    }
''', "receiver socket binding")

start = source.index("    /** Select the connected Wi-Fi network rather than Android's default route. */")
end = source.index("    private fun sendLanPacket(type: String, message: String) {", start)
source = source[:start] + '''    /** Select the connected Wi-Fi network rather than Android's default route. */
    private fun chatWifiNetwork(): Network? {
        return try {
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            fun isUsableWifi(network: Network): Boolean {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
                val interfaceName = connectivity.getLinkProperties(network)?.interfaceName
                return !interfaceName.isNullOrBlank()
            }
            val active = connectivity.activeNetwork
            if (active != null && isUsableWifi(active)) active
            else connectivity.allNetworks.firstOrNull(::isUsableWifi)
        } catch (e: Exception) {
            Log.w("AlkamelNet", "Unable to identify active Wi-Fi network for chat", e)
            null
        }
    }

    /** Resolve the interface belonging to the selected Wi-Fi Network only. */
    private fun chatWifiInterfaceName(network: Network?): String? {
        if (network == null) return null
        return try {
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.getLinkProperties(network)?.interfaceName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("AlkamelNet", "Unable to resolve chat Wi-Fi interface", e)
            null
        }
    }

    /**
     * Build broadcast routes only from the interface reported by Android for the
     * selected Wi-Fi Network. Mixing wlan, AP, Ethernet and virtual interfaces can
     * send packets from an unrelated subnet and produce misleading peer discovery.
     */
    private fun chatBroadcastRoutes(): List<Pair<InetAddress, InetAddress>> {
        val wifiNetwork = chatWifiNetwork()
        val interfaceName = chatWifiInterfaceName(wifiNetwork)
        if (interfaceName.isNullOrBlank()) {
            Log.w("AlkamelNet", "No active Wi-Fi interface is available for LAN chat broadcast")
            return emptyList()
        }
        val routes = linkedMapOf<String, Pair<InetAddress, InetAddress>>()
        try {
            val networkInterface = NetworkInterface.getByName(interfaceName)
            if (networkInterface == null || !runCatching {
                    networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual
                }.getOrDefault(false)) {
                Log.w("AlkamelNet", "Selected Wi-Fi interface is unavailable: $interfaceName")
                return emptyList()
            }
            networkInterface.interfaceAddresses.forEach { address ->
                val local = address.address
                val broadcast = address.broadcast
                if (local is java.net.Inet4Address && broadcast is java.net.Inet4Address &&
                    !local.isLoopbackAddress && !local.isLinkLocalAddress && !broadcast.isAnyLocalAddress) {
                    val key = "${local.hostAddress}->${broadcast.hostAddress}"
                    routes[key] = local to broadcast
                    Log.i("AlkamelNet", "LAN chat route interface=$interfaceName local=${local.hostAddress}/${address.networkPrefixLength} broadcast=${broadcast.hostAddress}")
                }
            }
        } catch (e: Exception) {
            Log.w("AlkamelNet", "Unable to resolve selected Wi-Fi broadcast route", e)
        }
        return routes.values.toList()
    }

''' + source[end:]

replace_once(
'''            try {
                val routes = chatBroadcastRoutes()
                val limitedBroadcast = InetAddress.getByName("255.255.255.255")
''',
'''            try {
                val wifiNetwork = chatWifiNetwork()
                if (wifiNetwork == null) {
                    Log.w("AlkamelNet", "LAN chat packet not sent: no connected Wi-Fi network")
                    return@execute
                }
                val routes = chatBroadcastRoutes()
                val limitedBroadcast = InetAddress.getByName("255.255.255.255")
''', "group sender network selection")

replace_once(
'''                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            chatWifiNetwork()?.let { network ->
                                runCatching { network.bindSocket(sender) }
                                    .onFailure { Log.w("AlkamelNet", "Unable to bind fallback chat sender to Wi-Fi", it) }
                            }
                        }
''',
'''                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) wifiNetwork.bindSocket(sender)
''', "fallback sender binding")

replace_once(
'''                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                chatWifiNetwork()?.let { network ->
                                    runCatching { network.bindSocket(sender) }
                                        .onFailure { Log.w("AlkamelNet", "Unable to bind chat sender to Wi-Fi", it) }
                                }
                            }
''',
'''                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) wifiNetwork.bindSocket(sender)
''', "broadcast sender binding")

replace_once(
'''                DatagramSocket(null).use { sender ->
                    // Private unicast packets must follow the same Wi-Fi route as
                    // group chat; otherwise Android may send them over cellular.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        chatWifiNetwork()?.let { network ->
                            runCatching { network.bindSocket(sender) }
                                .onFailure { Log.w("AlkamelNet", "Unable to bind private chat sender to Wi-Fi", it) }
                        }
                    }
                    sender.bind(java.net.InetSocketAddress(0))
''',
'''                DatagramSocket(null).use { sender ->
                    // Private unicast packets must follow the same Wi-Fi route as
                    // group chat; otherwise Android may send them over cellular.
                    val wifiNetwork = chatWifiNetwork()
                    if (wifiNetwork == null) {
                        Log.w("AlkamelNet", "Private LAN packet not sent: no connected Wi-Fi network")
                        return@use
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) wifiNetwork.bindSocket(sender)
                    sender.bind(java.net.InetSocketAddress(0))
''', "private sender binding")


# Extend the repair with reliable profile-photo discovery and seat activity UI.
replace_once(
'''                            if (packetType in setOf("presence", "profile_photo", "private_request", "private_response", "private_message")) {''',
'''                            if (packetType in setOf("presence", "profile_photo", "profile_photo_request", "private_request", "private_response", "private_message")) {''',
"photo request peer learning")
replace_once(
'''                            val isPublicPacket = packetType in setOf("presence", "message", "profile_photo", "gift", "voice_seat")''',
'''                            val isPublicPacket = packetType in setOf("presence", "message", "profile_photo", "profile_photo_request", "gift", "voice_seat")''',
"photo request public delivery")

replace_once(
'''        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 3500 && dataUrl.startsWith("data:image/jpeg;base64,"))) sendLanPacket("profile_photo", dataUrl) }''',
'''        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 3500 && dataUrl.startsWith("data:image/jpeg;base64,"))) sendLanPacket("profile_photo", dataUrl) }
        @JavascriptInterface fun requestProfilePhoto() { sendLanPacket("profile_photo_request", "") }''',
"profile photo request bridge")

home = root / "app/src/main/assets/home.html"
html = home.read_text(encoding="utf-8")
def html_replace_once(old: str, new: str, label: str) -> None:
    global html
    if old not in html:
        raise SystemExit(f"Expected HTML block not found: {label}")
    html = html.replace(old, new, 1)

html_replace_once(
'''const profilePhotos=new Map();try{localProfilePhoto=localStorage.getItem(PROFILE_PHOTO_KEY)||'';}''',
'''const profilePhotos=new Map();const profilePhotoRequestTimes=new Map();try{localProfilePhoto=localStorage.getItem(PROFILE_PHOTO_KEY)||'';}''',
"photo request tracking")
html_replace_once(
'''if(seat&&seat.id&&profilePhotos.has(seat.id)){const img=document.createElement('img');img.src=profilePhotos.get(seat.id);''',
'''const seatPhoto=seat?(seat.id===voiceSeatLocalId?localProfilePhoto:profilePhotos.get(seat.id)):'';if(seatPhoto){const img=document.createElement('img');img.src=seatPhoto;''',
"local seat photo rendering")
html_replace_once(
'''function receiveVoiceSeatEvent(p){if(p.action==='sync'){''',
'''function receiveVoiceSeatEvent(p){if(p.action==='join'&&p.id&&p.id!==voiceSeatLocalId&&!profilePhotos.has(p.id)&&window.AndroidBridge)requestPeerProfilePhoto(p.id);if(p.action==='sync'){''',
"seat join photo request")
html_replace_once(
'''function receiveChatPacket(raw){try{const p=JSON.parse(raw);if(!p.id||!p.name)return;if(p.type==='profile_photo'){''',
'''function requestPeerProfilePhoto(peerId){if(!peerId||!window.AndroidBridge)return;const now=Date.now(),last=profilePhotoRequestTimes.get(peerId)||0;if(now-last<20000)return;profilePhotoRequestTimes.set(peerId,now);AndroidBridge.requestProfilePhoto();}
function receiveChatPacket(raw){try{const p=JSON.parse(raw);if(!p.id||!p.name)return;if(p.type==='profile_photo_request'){if(localProfilePhoto&&window.AndroidBridge)AndroidBridge.sendProfilePhoto(localProfilePhoto);return;}if(p.type==='profile_photo'){''',
"photo request handler")
html_replace_once(
'''if(isNewPeer&&localProfilePhoto&&window.AndroidBridge)AndroidBridge.sendProfilePhoto(localProfilePhoto);const cutoff=Date.now()-16000;''',
'''if(isNewPeer&&localProfilePhoto&&window.AndroidBridge)AndroidBridge.sendProfilePhoto(localProfilePhoto);if(!profilePhotos.has(p.id))requestPeerProfilePhoto(p.id);const cutoff=Date.now()-16000;''',
"presence photo synchronization")
html_replace_once(
'''function onVoiceState(state,detail){
 const status=$('voiceStatus'),mute=$('voiceMuteButton');if(!status||!mute)return;''',
'''function onVoiceState(state,detail){
 if(state==='speaking'&&selectedVoiceSeat>=0)markVoiceSeatSpeaking(voiceSeatLocalId);
 const status=$('voiceStatus'),mute=$('voiceMuteButton');if(!status||!mute)return;''',
"local speaker activity hook")
html_replace_once(
'''function onVoiceActivity(senderId){const now=Date.now();const seat=voiceSeats.find(item=>item&&item.id===senderId);if(seat){voiceActivity.set(seat.id,now);renderVoiceSeats();$('voiceSpeakers').textContent='يوجد متحدث في الغرفة الآن: '+(seat.name||'عضو');setTimeout(()=>{if(Date.now()-(voiceActivity.get(senderId)||0)>=2200){voiceActivity.delete(senderId);renderVoiceSeats();$('voiceSpeakers').textContent='لا يوجد متحدثون الآن';}},2300);}}''',
'''function markVoiceSeatSpeaking(senderId){if(!senderId)return;const seat=voiceSeats.find(item=>item&&item.id===senderId);if(!seat)return;const now=Date.now();voiceActivity.set(senderId,now);renderVoiceSeats();$('voiceSpeakers').textContent='يوجد متحدث في الغرفة الآن: '+(seat.name||'عضو');setTimeout(()=>{if(Date.now()-(voiceActivity.get(senderId)||0)>=900){voiceActivity.delete(senderId);renderVoiceSeats();if(!voiceActivity.size)$('voiceSpeakers').textContent='لا يوجد متحدثون الآن';}},1000);}
function onVoiceActivity(senderId){markVoiceSeatSpeaking(senderId);}''',
"unified local and remote seat activity")
html_replace_once(
'''        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 3500 && dataUrl.startsWith("data:image/jpeg;base64,"))) sendLanPacket("profile_photo", dataUrl) }''',
'''        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 3500 && dataUrl.startsWith("data:image/jpeg;base64,"))) sendLanPacket("profile_photo", dataUrl) }
        @JavascriptInterface fun requestProfilePhoto() { sendLanPacket("profile_photo_request", "") }''',
"photo request bridge") if False else None
home.write_text(html, encoding="utf-8")

kotlin.write_text(source, encoding="utf-8")

gradle = root / "app/build.gradle.kts"
g = gradle.read_text(encoding="utf-8").replace("versionCode = 80", "versionCode = 82").replace('versionName = "1.9.6"', 'versionName = "1.9.8"')
if g == gradle.read_text(encoding="utf-8"):
    raise SystemExit("Could not bump app version")
gradle.write_text(g, encoding="utf-8")

validator = root / "scripts/validate_project.py"
v = validator.read_text(encoding="utf-8")
v = v.replace('"app version 1.9.6": "versionCode = 80" in GRADLE and \'versionName = "1.9.6"\' in GRADLE,',
              '"app version 1.9.8": "versionCode = 82" in GRADLE and \'versionName = "1.9.8"\' in GRADLE,')
v = v.replace('"private chat unicast sender binds to Wi-Fi": "Unable to bind private chat sender to Wi-Fi" in KOTLIN.read_text(encoding="utf-8") and "chatWifiNetwork()?.let { network" in KOTLIN.read_text(encoding="utf-8"),',
              '"private chat unicast sender binds to Wi-Fi": "Private LAN packet not sent: no connected Wi-Fi network" in KOTLIN.read_text(encoding="utf-8") and "wifiNetwork.bindSocket(sender)" in KOTLIN.read_text(encoding="utf-8"),')
v = v.replace('"private chat replies learn sender address": \'packetType in setOf("presence", "profile_photo", "private_request", "private_response", "private_message")\' in KOTLIN.read_text(encoding="utf-8") and "chatPeerAddresses[senderId] = packet.address" in KOTLIN.read_text(encoding="utf-8"),',
              '"private chat replies learn sender address": \'packetType in setOf("presence", "profile_photo", "profile_photo_request", "private_request", "private_response", "private_message")\' in KOTLIN.read_text(encoding="utf-8") and "chatPeerAddresses[senderId] = packet.address" in KOTLIN.read_text(encoding="utf-8"),')
v = v.replace("'setOf(\"presence\", \"message\", \"profile_photo\", \"gift\", \"voice_seat\")' in kotlin_source",
              "'setOf(\"presence\", \"message\", \"profile_photo\", \"profile_photo_request\", \"gift\", \"voice_seat\")' in kotlin_source")
v = v.replace('"item.id===senderId" in html_source and "voiceActivity.set(seat.id,now)" in html_source',
              '"voiceSeats.find(item=>item&&item.id===senderId)" in html_source and "voiceActivity.set(senderId,now)" in html_source')
v = v.replace('"integrated repair version": "versionCode = 80" in GRADLE and \'versionName = "1.9.6"\' in GRADLE,',
              '"integrated repair version": "versionCode = 82" in GRADLE and \'versionName = "1.9.8"\' in GRADLE,')

# Update validation to assert the MQTT transport selected for this release.
# Force the MQTT transport check to validate the patched MainActivity and manifest.
import re
mqtt_check = '"MQTT broker is the active group/private chat transport": "startMqttChat(name)" in kotlin_source and "publishMqtt" in kotlin_source and "MqttChatService" in kotlin_source and "MqttChatService" in MANIFEST and "org.eclipse.paho" in GRADLE,'
v, mqtt_check_count = re.subn(r'(?m)^\\s*"MQTT broker is the active group/private chat transport":.*,v = v.replace(
    '"LAN chat is the only active transport; MQTT service and broker dependency are removed": "startLanChat(name)" in kotlin_source and "publishMqtt" not in kotlin_source and "MqttChatService" not in kotlin_source and "MqttChatService" not in MANIFEST and "org.eclipse.paho" not in GRADLE,',
    '"MQTT broker is the active group/private chat transport": "startMqttChat(name)" in kotlin_source and "publishMqtt" in kotlin_source and "MqttChatService" in kotlin_source and "MqttChatService" in MANIFEST and "org.eclipse.paho" in GRADLE,')
v = v.replace(
    '"LAN profile photos are compressed below a safe single-datagram size": "const side=64" in html_source and "data.length>3500" in html_source and "dataUrl.length <= 3500" in kotlin_source,',
    '"MQTT profile photo payloads stay within the broker payload limit": "const side=64" in html_source and "data.length>3500" in html_source and "dataUrl.length <= 78000" in kotlin_source,')
v = v.replace(
    '"private messages use direct LAN unicast": "sendPrivateLanPacket(type, targetId, requestId, message.trim())" in kotlin_source,',
    '"private messages use MQTT private topics": "publishMqtt(type, message.trim(), targetId, requestId)" in kotlin_source,')

validator.write_text(v, encoding="utf-8")
print("Applied LAN Wi-Fi, profile-photo synchronization, and voice-seat activity repairs; version 1.9.8 (code 82).")


# Integrate MQTT transport into the exact 1.9.6 source before producing 1.9.8.
import shutil
mqtt_source = Path(__file__).with_name("MqttChatService.kt")
if not mqtt_source.is_file():
    raise SystemExit("Missing scripts/MqttChatService.kt")
mqtt_target = root / "app/src/main/java/com/alkamel/network/MqttChatService.kt"
mqtt_target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(mqtt_source, mqtt_target)

main_file = root / "app/src/main/java/com/alkamel/network/MainActivity.kt"
main = main_file.read_text(encoding="utf-8")
def main_once(old, new, label):
    global main
    if old not in main:
        raise SystemExit(f"Expected MainActivity block not found: {label}")
    main = main.replace(old, new, 1)

main_once(
"import android.content.Intent\n",
"import android.content.Intent\nimport android.content.BroadcastReceiver\nimport android.content.IntentFilter\n",
"MQTT receiver imports")
main_once(
"    private val mainHandler = Handler(Looper.getMainLooper())\n",
"""    private val mainHandler = Handler(Looper.getMainLooper())
    private var mqttReceiverRegistered = false
    private val mqttEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(MqttChatService.EXTRA_STATE)
            if (state != null) {
                chatRunning.set(state == "joined")
                notifyChatState(state, intent.getStringExtra(MqttChatService.EXTRA_DETAIL).orEmpty())
            }
            val raw = intent?.getStringExtra(MqttChatService.EXTRA_RAW)
            if (!raw.isNullOrBlank() && ::web.isInitialized && !isFinishing && !isDestroyed) {
                web.evaluateJavascript("if(window.onLanChatPacket)window.onLanChatPacket(" + JSONObject.quote(raw) + ");", null)
            }
        }
    }
""",
"MQTT event receiver")
main_once(
"        super.onCreate(savedInstanceState)\n        restoreFirewallIfNeeded()\n",
"""        super.onCreate(savedInstanceState)
        val mqttFilter = IntentFilter(MqttChatService.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mqttEventReceiver, mqttFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(mqttEventReceiver, mqttFilter)
        mqttReceiverRegistered = true
        restoreFirewallIfNeeded()
""",
"MQTT receiver registration")
# Insert MQTT lifecycle methods before the LAN service methods.
anchor = "    private fun startLanChat(name: String, ensureService: Boolean = true) {"
if anchor not in main:
    raise SystemExit("Expected LAN chat start method not found")
mqtt_methods = """    private fun startMqttChat(name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        chatNickname = clean
        chatRequested.set(true)
        val intent = Intent(this, MqttChatService::class.java).apply {
            action = MqttChatService.ACTION_CONNECT
            putExtra(MqttChatService.EXTRA_NAME, clean)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Log.e("AlkamelNet", "Unable to start MQTT chat service", e)
            notifyChatState("error", "تعذر تشغيل خدمة الدردشة في الخلفية.")
        }
    }

    private fun publishMqtt(type: String, message: String, targetId: String? = null, requestId: String? = null) {
        val intent = Intent(this, MqttChatService::class.java).apply {
            action = MqttChatService.ACTION_PUBLISH
            putExtra(MqttChatService.EXTRA_TYPE, type)
            putExtra(MqttChatService.EXTRA_TEXT, message.take(if (type == "voice_message") 1_200_000 else if (type == "profile_photo") 78_000 else 1000))
            targetId?.let { putExtra(MqttChatService.EXTRA_TARGET, it) }
            requestId?.let { putExtra(MqttChatService.EXTRA_REQUEST_ID, it) }
        }
        runCatching { startService(intent) }.onFailure { Log.w("AlkamelNet", "Unable to queue MQTT packet", it) }
    }

    private fun stopMqttChat() {
        chatRequested.set(false)
        chatRunning.set(false)
        runCatching { stopService(Intent(this, MqttChatService::class.java)) }
        notifyChatState("stopped", "")
    }

"""
main = main.replace(anchor, mqtt_methods + anchor, 1)

main_once(
"        @JavascriptInterface fun startChat(name: String) { startLanChat(name) }",
"        @JavascriptInterface fun startChat(name: String) { startMqttChat(name) }",
"Bridge start MQTT")
main_once(
"        @JavascriptInterface fun sendChatMessage(message: String) { if (message.isNotBlank() && message.length <= 1000) sendLanPacket(\"message\", message.trim()) }",
"        @JavascriptInterface fun sendChatMessage(message: String) { if (message.isNotBlank() && message.length <= 1000) publishMqtt(\"message\", message.trim()) }",
"Bridge group message MQTT")
main_once(
"        @JavascriptInterface fun sendChatGift(giftId: String) { if (giftId in setOf(\"rose\", \"heart\", \"crown\", \"diamond\", \"trophy\", \"fireworks\")) sendLanPacket(\"gift\", giftId) }",
"        @JavascriptInterface fun sendChatGift(giftId: String) { if (giftId in setOf(\"rose\", \"heart\", \"crown\", \"diamond\", \"trophy\", \"fireworks\")) publishMqtt(\"gift\", giftId) }",
"Bridge gift MQTT")
main_once(
"        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 3500 && dataUrl.startsWith(\"data:image/jpeg;base64,\"))) sendLanPacket(\"profile_photo\", dataUrl) }",
"        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 78000 && (dataUrl.isEmpty() || dataUrl.startsWith(\"data:image/jpeg;base64,\")))) publishMqtt(\"profile_photo\", dataUrl) }",
"Bridge profile photo MQTT")
main_once(
"if (action in setOf(\"join\", \"leave\") && seat in 0..4) sendLanPacket(\"voice_seat\", JSONObject().put(\"action\", action).put(\"seat\", seat).put(\"muted\", false).toString())",
"if (action in setOf(\"join\", \"leave\") && seat in 0..4) publishMqtt(\"voice_seat\", JSONObject().put(\"action\", action).put(\"seat\", seat).put(\"muted\", false).toString())",
"Bridge voice seat MQTT")
main_once(
"sendLanPacket(\"voice_seat\", JSONObject().put(\"action\", \"sync\").put(\"seat\", -1).toString())",
"publishMqtt(\"voice_seat\", JSONObject().put(\"action\", \"sync\").put(\"seat\", -1).toString())",
"Bridge voice seat sync MQTT")
main_once(
"runOnUiThread { Toast.makeText(this@MainActivity, \"الرسائل الصوتية المسجلة غير متاحة عبر LAN حالياً؛ استخدم مجلس الصوت المباشر\", Toast.LENGTH_LONG).show() }",
"if (dataUrl.length in 1..1_200_000 && dataUrl.startsWith(\"data:audio/\")) publishMqtt(\"voice_message\", dataUrl)",
"Bridge voice message MQTT")
main_once(
"sendPrivateLanPacket(type, targetId, requestId, message.trim())",
"publishMqtt(type, message.trim(), targetId, requestId)",
"Bridge private chat MQTT")
main_once(
"@JavascriptInterface fun stopChat() { stopVoiceRoom(); stopLanChat() }",
"@JavascriptInterface fun stopChat() { stopVoiceRoom(); stopMqttChat(); stopLanChat() }",
"Bridge stop MQTT")
main_once(
"        pendingImageFileCallback?.onReceiveValue(null)\n        pendingImageFileCallback = null\n",
"""        pendingImageFileCallback?.onReceiveValue(null)
        pendingImageFileCallback = null
        if (mqttReceiverRegistered) { runCatching { unregisterReceiver(mqttEventReceiver) }; mqttReceiverRegistered = false }
""",
"MQTT receiver unregister")
main_file.write_text(main, encoding="utf-8")

# Add Paho and base64-encoded secret-backed BuildConfig fields.
gradle_file = root / "app/build.gradle.kts"
g = gradle_file.read_text(encoding="utf-8")
g = g.replace('    defaultConfig {\n', '''    buildFeatures { buildConfig = true }
    defaultConfig {
        val mqttUsername = providers.gradleProperty("MQTT_USERNAME").orElse(providers.environmentVariable("MQTT_USERNAME")).orElse("alkamel").get()
        val mqttPassword = providers.gradleProperty("MQTT_PASSWORD").orElse(providers.environmentVariable("MQTT_PASSWORD")).getOrElse("")
        if (mqttPassword.isBlank()) throw GradleException("MQTT_PASSWORD is missing. Add it as a GitHub Actions secret before building.")
        val mqttUsernameB64 = Base64.getEncoder().encodeToString(mqttUsername.toByteArray())
        val mqttPasswordB64 = Base64.getEncoder().encodeToString(mqttPassword.toByteArray())
        buildConfigField("String", "MQTT_USERNAME_B64", "\\\"${mqttUsernameB64}\\\"")
        buildConfigField("String", "MQTT_PASSWORD_B64", "\\\"${mqttPasswordB64}\\\"")
''', 1)
if 'org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5' not in g:
    marker = "dependencies {"
    if marker not in g:
        raise SystemExit("Could not locate dependencies block in app/build.gradle.kts")
    g = g.replace(marker, marker + '\n    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")', 1)
if not g.startswith("import java.util.Base64\n"):
    g = "import java.util.Base64\n" + g

manifest_file = root / "app/src/main/AndroidManifest.xml"
mf = manifest_file.read_text(encoding="utf-8")
if 'android:name=".MqttChatService"' not in mf:
    mf = mf.replace('    <uses-permission android:name="android.permission.INTERNET" />\n',
        '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />\n', 1)
    mf = mf.replace('</application>', '''        <service
            android:name=".MqttChatService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="connectedDevice" />
    </application>''', 1)
manifest_file.write_text(mf, encoding="utf-8")

# 1.9.8 version identity
gradle_file = root / "app/build.gradle.kts"
g = gradle_file.read_text(encoding="utf-8").replace("versionCode = 80", "versionCode = 82").replace('versionName = "1.9.6"', 'versionName = "1.9.8"')
gradle_file.write_text(g, encoding="utf-8")

print("MQTT_DIAG startMqttChat=", "startMqttChat(name)" in main)
print("MQTT_DIAG publishMqtt=", "publishMqtt" in main)
print("MQTT_DIAG service_in_main=", "MqttChatService" in main)
print("MQTT_DIAG service_in_manifest=", "MqttChatService" in mf)
print("MQTT_DIAG paho_in_gradle=", "org.eclipse.paho" in g)
print("MQTT_DIAG buildconfig_fields=", "MQTT_PASSWORD_B64" in g and "MQTT_USERNAME_B64" in g)
print("MQTT_DIAG validator_test=", [line.strip() for line in v.splitlines() if "MQTT broker is the active" in line])
, '    ' + mqtt_check, v)
if mqtt_check_count != 1:
    raise SystemExit(f"Expected exactly one MQTT transport validator check, found {mqtt_check_count}")
v = v.replace(
    '"LAN chat is the only active transport; MQTT service and broker dependency are removed": "startLanChat(name)" in kotlin_source and "publishMqtt" not in kotlin_source and "MqttChatService" not in kotlin_source and "MqttChatService" not in MANIFEST and "org.eclipse.paho" not in GRADLE,',
    '"MQTT broker is the active group/private chat transport": "startMqttChat(name)" in kotlin_source and "publishMqtt" in kotlin_source and "MqttChatService" in kotlin_source and "MqttChatService" in MANIFEST and "org.eclipse.paho" in GRADLE,')
v = v.replace(
    '"LAN profile photos are compressed below a safe single-datagram size": "const side=64" in html_source and "data.length>3500" in html_source and "dataUrl.length <= 3500" in kotlin_source,',
    '"MQTT profile photo payloads stay within the broker payload limit": "const side=64" in html_source and "data.length>3500" in html_source and "dataUrl.length <= 78000" in kotlin_source,')
v = v.replace(
    '"private messages use direct LAN unicast": "sendPrivateLanPacket(type, targetId, requestId, message.trim())" in kotlin_source,',
    '"private messages use MQTT private topics": "publishMqtt(type, message.trim(), targetId, requestId)" in kotlin_source,')

validator.write_text(v, encoding="utf-8")
print("Applied LAN Wi-Fi, profile-photo synchronization, and voice-seat activity repairs; version 1.9.8 (code 82).")


# Integrate MQTT transport into the exact 1.9.6 source before producing 1.9.8.
import shutil
mqtt_source = Path(__file__).with_name("MqttChatService.kt")
if not mqtt_source.is_file():
    raise SystemExit("Missing scripts/MqttChatService.kt")
mqtt_target = root / "app/src/main/java/com/alkamel/network/MqttChatService.kt"
mqtt_target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(mqtt_source, mqtt_target)

main_file = root / "app/src/main/java/com/alkamel/network/MainActivity.kt"
main = main_file.read_text(encoding="utf-8")
def main_once(old, new, label):
    global main
    if old not in main:
        raise SystemExit(f"Expected MainActivity block not found: {label}")
    main = main.replace(old, new, 1)

main_once(
"import android.content.Intent\n",
"import android.content.Intent\nimport android.content.BroadcastReceiver\nimport android.content.IntentFilter\n",
"MQTT receiver imports")
main_once(
"    private val mainHandler = Handler(Looper.getMainLooper())\n",
"""    private val mainHandler = Handler(Looper.getMainLooper())
    private var mqttReceiverRegistered = false
    private val mqttEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(MqttChatService.EXTRA_STATE)
            if (state != null) {
                chatRunning.set(state == "joined")
                notifyChatState(state, intent.getStringExtra(MqttChatService.EXTRA_DETAIL).orEmpty())
            }
            val raw = intent?.getStringExtra(MqttChatService.EXTRA_RAW)
            if (!raw.isNullOrBlank() && ::web.isInitialized && !isFinishing && !isDestroyed) {
                web.evaluateJavascript("if(window.onLanChatPacket)window.onLanChatPacket(" + JSONObject.quote(raw) + ");", null)
            }
        }
    }
""",
"MQTT event receiver")
main_once(
"        super.onCreate(savedInstanceState)\n        restoreFirewallIfNeeded()\n",
"""        super.onCreate(savedInstanceState)
        val mqttFilter = IntentFilter(MqttChatService.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mqttEventReceiver, mqttFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(mqttEventReceiver, mqttFilter)
        mqttReceiverRegistered = true
        restoreFirewallIfNeeded()
""",
"MQTT receiver registration")
# Insert MQTT lifecycle methods before the LAN service methods.
anchor = "    private fun startLanChat(name: String, ensureService: Boolean = true) {"
if anchor not in main:
    raise SystemExit("Expected LAN chat start method not found")
mqtt_methods = """    private fun startMqttChat(name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        chatNickname = clean
        chatRequested.set(true)
        val intent = Intent(this, MqttChatService::class.java).apply {
            action = MqttChatService.ACTION_CONNECT
            putExtra(MqttChatService.EXTRA_NAME, clean)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Log.e("AlkamelNet", "Unable to start MQTT chat service", e)
            notifyChatState("error", "تعذر تشغيل خدمة الدردشة في الخلفية.")
        }
    }

    private fun publishMqtt(type: String, message: String, targetId: String? = null, requestId: String? = null) {
        val intent = Intent(this, MqttChatService::class.java).apply {
            action = MqttChatService.ACTION_PUBLISH
            putExtra(MqttChatService.EXTRA_TYPE, type)
            putExtra(MqttChatService.EXTRA_TEXT, message.take(if (type == "voice_message") 1_200_000 else if (type == "profile_photo") 78_000 else 1000))
            targetId?.let { putExtra(MqttChatService.EXTRA_TARGET, it) }
            requestId?.let { putExtra(MqttChatService.EXTRA_REQUEST_ID, it) }
        }
        runCatching { startService(intent) }.onFailure { Log.w("AlkamelNet", "Unable to queue MQTT packet", it) }
    }

    private fun stopMqttChat() {
        chatRequested.set(false)
        chatRunning.set(false)
        runCatching { stopService(Intent(this, MqttChatService::class.java)) }
        notifyChatState("stopped", "")
    }

"""
main = main.replace(anchor, mqtt_methods + anchor, 1)

main_once(
"        @JavascriptInterface fun startChat(name: String) { startLanChat(name) }",
"        @JavascriptInterface fun startChat(name: String) { startMqttChat(name) }",
"Bridge start MQTT")
main_once(
"        @JavascriptInterface fun sendChatMessage(message: String) { if (message.isNotBlank() && message.length <= 1000) sendLanPacket(\"message\", message.trim()) }",
"        @JavascriptInterface fun sendChatMessage(message: String) { if (message.isNotBlank() && message.length <= 1000) publishMqtt(\"message\", message.trim()) }",
"Bridge group message MQTT")
main_once(
"        @JavascriptInterface fun sendChatGift(giftId: String) { if (giftId in setOf(\"rose\", \"heart\", \"crown\", \"diamond\", \"trophy\", \"fireworks\")) sendLanPacket(\"gift\", giftId) }",
"        @JavascriptInterface fun sendChatGift(giftId: String) { if (giftId in setOf(\"rose\", \"heart\", \"crown\", \"diamond\", \"trophy\", \"fireworks\")) publishMqtt(\"gift\", giftId) }",
"Bridge gift MQTT")
main_once(
"        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 3500 && dataUrl.startsWith(\"data:image/jpeg;base64,\"))) sendLanPacket(\"profile_photo\", dataUrl) }",
"        @JavascriptInterface fun sendProfilePhoto(dataUrl: String) { if (dataUrl.isEmpty() || (dataUrl.length <= 78000 && (dataUrl.isEmpty() || dataUrl.startsWith(\"data:image/jpeg;base64,\")))) publishMqtt(\"profile_photo\", dataUrl) }",
"Bridge profile photo MQTT")
main_once(
"if (action in setOf(\"join\", \"leave\") && seat in 0..4) sendLanPacket(\"voice_seat\", JSONObject().put(\"action\", action).put(\"seat\", seat).put(\"muted\", false).toString())",
"if (action in setOf(\"join\", \"leave\") && seat in 0..4) publishMqtt(\"voice_seat\", JSONObject().put(\"action\", action).put(\"seat\", seat).put(\"muted\", false).toString())",
"Bridge voice seat MQTT")
main_once(
"sendLanPacket(\"voice_seat\", JSONObject().put(\"action\", \"sync\").put(\"seat\", -1).toString())",
"publishMqtt(\"voice_seat\", JSONObject().put(\"action\", \"sync\").put(\"seat\", -1).toString())",
"Bridge voice seat sync MQTT")
main_once(
"runOnUiThread { Toast.makeText(this@MainActivity, \"الرسائل الصوتية المسجلة غير متاحة عبر LAN حالياً؛ استخدم مجلس الصوت المباشر\", Toast.LENGTH_LONG).show() }",
"if (dataUrl.length in 1..1_200_000 && dataUrl.startsWith(\"data:audio/\")) publishMqtt(\"voice_message\", dataUrl)",
"Bridge voice message MQTT")
main_once(
"sendPrivateLanPacket(type, targetId, requestId, message.trim())",
"publishMqtt(type, message.trim(), targetId, requestId)",
"Bridge private chat MQTT")
main_once(
"@JavascriptInterface fun stopChat() { stopVoiceRoom(); stopLanChat() }",
"@JavascriptInterface fun stopChat() { stopVoiceRoom(); stopMqttChat(); stopLanChat() }",
"Bridge stop MQTT")
main_once(
"        pendingImageFileCallback?.onReceiveValue(null)\n        pendingImageFileCallback = null\n",
"""        pendingImageFileCallback?.onReceiveValue(null)
        pendingImageFileCallback = null
        if (mqttReceiverRegistered) { runCatching { unregisterReceiver(mqttEventReceiver) }; mqttReceiverRegistered = false }
""",
"MQTT receiver unregister")
main_file.write_text(main, encoding="utf-8")

# Add Paho and base64-encoded secret-backed BuildConfig fields.
gradle_file = root / "app/build.gradle.kts"
g = gradle_file.read_text(encoding="utf-8")
g = g.replace('    defaultConfig {\n', '''    buildFeatures { buildConfig = true }
    defaultConfig {
        val mqttUsername = providers.gradleProperty("MQTT_USERNAME").orElse(providers.environmentVariable("MQTT_USERNAME")).orElse("alkamel").get()
        val mqttPassword = providers.gradleProperty("MQTT_PASSWORD").orElse(providers.environmentVariable("MQTT_PASSWORD")).getOrElse("")
        if (mqttPassword.isBlank()) throw GradleException("MQTT_PASSWORD is missing. Add it as a GitHub Actions secret before building.")
        val mqttUsernameB64 = Base64.getEncoder().encodeToString(mqttUsername.toByteArray())
        val mqttPasswordB64 = Base64.getEncoder().encodeToString(mqttPassword.toByteArray())
        buildConfigField("String", "MQTT_USERNAME_B64", "\\\"${mqttUsernameB64}\\\"")
        buildConfigField("String", "MQTT_PASSWORD_B64", "\\\"${mqttPasswordB64}\\\"")
''', 1)
if 'org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5' not in g:
    marker = "dependencies {"
    if marker not in g:
        raise SystemExit("Could not locate dependencies block in app/build.gradle.kts")
    g = g.replace(marker, marker + '\n    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")', 1)
if not g.startswith("import java.util.Base64\n"):
    g = "import java.util.Base64\n" + g

manifest_file = root / "app/src/main/AndroidManifest.xml"
mf = manifest_file.read_text(encoding="utf-8")
if 'android:name=".MqttChatService"' not in mf:
    mf = mf.replace('    <uses-permission android:name="android.permission.INTERNET" />\n',
        '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />\n', 1)
    mf = mf.replace('</application>', '''        <service
            android:name=".MqttChatService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="connectedDevice" />
    </application>''', 1)
manifest_file.write_text(mf, encoding="utf-8")

# 1.9.8 version identity
gradle_file = root / "app/build.gradle.kts"
g = gradle_file.read_text(encoding="utf-8").replace("versionCode = 80", "versionCode = 82").replace('versionName = "1.9.6"', 'versionName = "1.9.8"')
gradle_file.write_text(g, encoding="utf-8")

print("MQTT_DIAG startMqttChat=", "startMqttChat(name)" in main)
print("MQTT_DIAG publishMqtt=", "publishMqtt" in main)
print("MQTT_DIAG service_in_main=", "MqttChatService" in main)
print("MQTT_DIAG service_in_manifest=", "MqttChatService" in mf)
print("MQTT_DIAG paho_in_gradle=", "org.eclipse.paho" in g)
print("MQTT_DIAG buildconfig_fields=", "MQTT_PASSWORD_B64" in g and "MQTT_USERNAME_B64" in g)
print("MQTT_DIAG validator_test=", [line.strip() for line in v.splitlines() if "MQTT broker is the active" in line])
