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
              '"app version 1.9.7": "versionCode = 81" in GRADLE and \'versionName = "1.9.7"\' in GRADLE,')
v = v.replace('"integrated repair version": "versionCode = 80" in GRADLE and \'versionName = "1.9.6"\' in GRADLE,',
              '"integrated repair version": "versionCode = 81" in GRADLE and \'versionName = "1.9.7"\' in GRADLE,')
v = v.replace('"private chat unicast sender binds to Wi-Fi": "Unable to bind private chat sender to Wi-Fi" in KOTLIN.read_text(encoding="utf-8") and "chatWifiNetwork()?.let { network" in KOTLIN.read_text(encoding="utf-8"),',
              '"private chat unicast sender binds to Wi-Fi": "Private LAN packet not sent: no connected Wi-Fi network" in KOTLIN.read_text(encoding="utf-8") and "wifiNetwork.bindSocket(sender)" in KOTLIN.read_text(encoding="utf-8"),')
validator.write_text(v, encoding="utf-8")
print("Applied LAN Wi-Fi routing repair and bumped app version to 1.9.7 (code 81).")
