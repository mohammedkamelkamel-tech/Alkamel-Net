#!/usr/bin/env python3
"""Fast source checks for Alkamel-Net before Android compilation."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
MANIFEST = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
HOME = APP / "assets/home.html"
HD = APP / "assets/hd/index.html"
KOTLIN = APP / "java/com/alkamel/network/MainActivity.kt"

checks = {
    "portal commands wait for subscriber status before submitting": "waitingForSubscriber" in KOTLIN.read_text(encoding="utf-8") and "target.postDelayed({ runPendingCommand() }, 500)" in KOTLIN.read_text(encoding="utf-8"),
    "JavaScript bridge is restricted to bundled app pages": "if (urlIsLocal(url)) view?.addJavascriptInterface(Bridge(), \"AndroidBridge\")" in KOTLIN.read_text(encoding="utf-8") and "else view?.removeJavascriptInterface(\"AndroidBridge\")" in KOTLIN.read_text(encoding="utf-8"),
    "defensive page navigation and status parsing": "const target=$(id);if(!target||!target.classList.contains('page'))return;" in HOME.read_text(encoding="utf-8") and "if(!d||typeof d!=='object'||Array.isArray(d))" in HOME.read_text(encoding="utf-8"),
    "professional responsive interface layer": "Unified professional visual system for every application section" in HOME.read_text(encoding="utf-8") and "@media(max-width:420px)" in HOME.read_text(encoding="utf-8") and ":focus-visible" in HOME.read_text(encoding="utf-8"),
    "new شبكة الكامل header and approved icon": '<h1>شبكة الكامل</h1>' in HOME.read_text(encoding="utf-8") and 'src="brand-icon.webp"' in HOME.read_text(encoding="utf-8"),
    "technical support section exists": 'id="support"' in HOME.read_text(encoding="utf-8") and "الدعم الفني" in HOME.read_text(encoding="utf-8"),
    "six support sub-sections with assigned names and matching icons": all((f'id="support{i}"' in HOME.read_text(encoding="utf-8") and f"showPage('support{i}')" in HOME.read_text(encoding="utf-8")) for i in range(1,7)) and all(x in HOME.read_text(encoding="utf-8") for x in ("معرفة التطبيقات الأكثر استهلاكاً","المشاكل والحلول","خطوات شراء الكروت","📊","🧰","🛒")) and "support-sections" in HOME.read_text(encoding="utf-8"),
    "rest lounges are grouped inside the leisure page": 'onclick="showPage(\\'rest\\')"' in HOME.read_text(encoding="utf-8") and 'id="rest"' in HOME.read_text(encoding="utf-8") and 'http://30.30.30.30:8096/web/index.html#!/home' in HOME.read_text(encoding="utf-8") and 'http://30.10.10.10/' in HOME.read_text(encoding="utf-8") and 'class="tile tile-rest1"' not in HOME.read_text(encoding="utf-8") and 'class="tile tile-rest2"' not in HOME.read_text(encoding="utf-8"),
    "icon palette theme classes": all(x in HOME.read_text(encoding="utf-8") for x in (".tile-ads",".tile-query",".tile-speed",".tile-google",".tile-live",".tile-support",".tile-rest1",".tile-rest2",".tile-chat")),
    "logout action moved from home to subscriber query": "onclick=\"logout()\" " not in HOME.read_text(encoding="utf-8"),
    "logout button is below subscriber refresh": HOME.read_text(encoding="utf-8").find("onclick=\"refreshStatus()\"") < HOME.read_text(encoding="utf-8").find("onclick=\"logout()\"") and "إنهاء جلسة الحساب" not in HOME.read_text(encoding="utf-8"),
    "minSdk 24": "minSdk = 24" in GRADLE,
    "targetSdk 35": "targetSdk = 35" in GRADLE,
    "app version 1.6.3": "versionCode = 47" in GRADLE and 'versionName = "1.6.3"' in GRADLE,
    "app label": 'android:label="شبكة الكامل"' in MANIFEST,
    "launcher icon uses approved artwork": 'android:icon="@drawable/ic_launcher"' in MANIFEST and 'android:roundIcon="@drawable/ic_launcher"' in MANIFEST and (APP / "res/drawable-nodpi/ic_launcher.webp").is_file() and (APP / "res/drawable-nodpi/ic_launcher.webp").stat().st_size > 4000 and (APP / "res/drawable-nodpi/ic_launcher.webp").read_bytes()[:4] == b"RIFF" and (APP / "res/drawable-nodpi/ic_launcher.webp").read_bytes()[8:12] == b"WEBP",
    "internet permission": "android.permission.INTERNET" in MANIFEST,
    "leisure main section groups both lounges beside live tile": 'onclick="showPage(\'rest\')"' in HOME.read_text(encoding="utf-8") and 'id="rest"' in HOME.read_text(encoding="utf-8") and "الاستراحة الأولى" in HOME.read_text(encoding="utf-8") and "الاستراحة الثانية" in HOME.read_text(encoding="utf-8") and HOME.read_text(encoding="utf-8").find('class="tile tile-live"') < HOME.read_text(encoding="utf-8").find('onclick="showPage(\'rest\')"') < HOME.read_text(encoding="utf-8").find('class="tile tile-support"'),
    "home asset": HOME.is_file() and HOME.stat().st_size > 0,
    "HD asset": HD.is_file() and HD.stat().st_size > 0,
    "native activity": KOTLIN.is_file() and "class MainActivity" in KOTLIN.read_text(encoding="utf-8"),
    "LAN chat bridge": "startLanChat" in KOTLIN.read_text(encoding="utf-8") and "sendLanPacket" in KOTLIN.read_text(encoding="utf-8"),
    "chat join acknowledgement": "onChatState" in HOME.read_text(encoding="utf-8") and "notifyChatState" in KOTLIN.read_text(encoding="utf-8"),
    "chat pages and packet handler": 'id="chat"' in HOME.read_text(encoding="utf-8") and 'id="chatRoom"' in HOME.read_text(encoding="utf-8") and "onLanChatPacket" in HOME.read_text(encoding="utf-8"),
    "chat lobby feedback": "chatLobbyNotice" in HOME.read_text(encoding="utf-8") and "joinChatButton" in HOME.read_text(encoding="utf-8"),
    "remembered chat identity and automatic rejoin": "CHAT_NAME_KEY" in HOME.read_text(encoding="utf-8") and "function openChat()" in HOME.read_text(encoding="utf-8") and "joinChat();" in HOME.read_text(encoding="utf-8"),
    "local chat history persistence": "CHAT_HISTORY_KEY" in HOME.read_text(encoding="utf-8") and "saveChatHistory" in HOME.read_text(encoding="utf-8") and "restoreChatHistory" in HOME.read_text(encoding="utf-8"),
    "compact live member count and clean room title": "chatRoomPeers" in HOME.read_text(encoding="utf-8") and "غرفة عامة · LAN" not in HOME.read_text(encoding="utf-8"),
    "private chat request and decision UI": "requestPrivateChat" in HOME.read_text(encoding="utf-8") and "acceptPrivateRequest" in HOME.read_text(encoding="utf-8") and "rejectPrivateRequest" in HOME.read_text(encoding="utf-8"),
    "private chat isolated messaging and local history": "sendPrivateChatMessage" in HOME.read_text(encoding="utf-8") and "private_message" in HOME.read_text(encoding="utf-8") and "PRIVATE_HISTORY_KEY" in HOME.read_text(encoding="utf-8"),
    "targeted private LAN transport": "sendPrivateChatPacket" in HOME.read_text(encoding="utf-8") and "sendPrivateLanPacket" in KOTLIN.read_text(encoding="utf-8") and "chatPeerAddresses" in KOTLIN.read_text(encoding="utf-8"),
    "private chat replies learn sender address": 'packetType in setOf("presence", "private_request", "private_response", "private_message")' in KOTLIN.read_text(encoding="utf-8") and "chatPeerAddresses[senderId] = packet.address" in KOTLIN.read_text(encoding="utf-8"),
    "private packets are delivered only to their target": 'obj.optString("to") == chatId' in KOTLIN.read_text(encoding="utf-8") and 'put("to", targetId)' in KOTLIN.read_text(encoding="utf-8"),
    "private decisions are matched to original request": "outgoing.peerId!==packet.id" in HOME.read_text(encoding="utf-8") and "requestId" in HOME.read_text(encoding="utf-8"),
    "separate chat lobby and room": 'id="chat"' in HOME.read_text(encoding="utf-8") and 'id="chatRoom"' in HOME.read_text(encoding="utf-8") and "appendChatMessage" in HOME.read_text(encoding="utf-8"),
    "message duplicate protection": "messageId" in KOTLIN.read_text(encoding="utf-8") and "seenChatMessageIds" in HOME.read_text(encoding="utf-8"),
    "group chat sender and message inline at 70 percent font size": "#chatRoom .chat-meta{display:contents!important}" in HOME.read_text(encoding="utf-8") and "font-size:7.7px!important" in HOME.read_text(encoding="utf-8") and "font-size:10.5px!important" in HOME.read_text(encoding="utf-8"),
    "chat sender highlighting": "chat-name" in HOME.read_text(encoding="utf-8") and ".chat-message.mine" in HOME.read_text(encoding="utf-8"),
    "advertisement image fills available width with natural ratio": ".ads-image{position:relative!important" in HOME.read_text(encoding="utf-8") and "width:100%!important" in HOME.read_text(encoding="utf-8") and "max-height:82vh!important" in HOME.read_text(encoding="utf-8"),
    "advertisement image keeps natural portrait and landscape ratio": ".ads-image{position:relative!important" in HOME.read_text(encoding="utf-8") and "height:auto!important" in HOME.read_text(encoding="utf-8") and "object-fit:contain!important" in HOME.read_text(encoding="utf-8") and "max-height:82vh!important" in HOME.read_text(encoding="utf-8"),
    "advertisement assets use hotspot root URL": "const ADS_BASE='http://k.com/';" in HOME.read_text(encoding="utf-8") and "http://k.com/772072056/" not in HOME.read_text(encoding="utf-8"),
    "advertisements section and MikroTik image source": 'id="ads"' in HOME.read_text(encoding="utf-8") and "http://k.com/" in HOME.read_text(encoding="utf-8") and "img/'+(i+1)+'.jpg" in HOME.read_text(encoding="utf-8") and "imageCount" in HOME.read_text(encoding="utf-8"),
    "chat dock is horizontal and compact": "display:flex!important;flex-direction:row!important" in HOME.read_text(encoding="utf-8") and "flex:0 0 38px!important" in HOME.read_text(encoding="utf-8") and "1.4.7: compact horizontal chat dock" in HOME.read_text(encoding="utf-8"),
    "icon-first voice controls and accessible mic state": 'id="voiceMuteButton"' in HOME.read_text(encoding="utf-8") and "updateVoiceMuteButton" in HOME.read_text(encoding="utf-8") and "dock-btn send" in HOME.read_text(encoding="utf-8") and 'aria-label="دخول غرفة الصوت"' in HOME.read_text(encoding="utf-8"),
    "voice controls": "joinVoiceRoom" in HOME.read_text(encoding="utf-8") and "toggleVoiceMute" in HOME.read_text(encoding="utf-8"),
    "background chat service": (APP / "java/com/alkamel/network/ChatKeepAliveService.kt").is_file() and ".ChatKeepAliveService" in MANIFEST,
    "first-run background activation prompt stays in app": "showFirstRunSetupIfNeeded()" in KOTLIN.read_text(encoding="utf-8") and "first_run_setup_asked" in KOTLIN.read_text(encoding="utf-8") and "requestNotificationPermission()" in KOTLIN.read_text(encoding="utf-8") and "Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS" not in KOTLIN.read_text(encoding="utf-8"),
    "notification permission and channels": "android.permission.POST_NOTIFICATIONS" in MANIFEST and "requestNotificationPermission()" in KOTLIN.read_text(encoding="utf-8") and "createEventNotificationChannel()" in KOTLIN.read_text(encoding="utf-8"),
    "background chat event notifications": "notifyIncomingChat(obj)" in KOTLIN.read_text(encoding="utf-8") and "تم ذكرك في دردشة شبكة الكامل" in KOTLIN.read_text(encoding="utf-8"),
    "background advertisement update monitor": "startAdsUpdateMonitor()" in (APP / "java/com/alkamel/network/ChatKeepAliveService.kt").read_text(encoding="utf-8") and "checkForNewAds()" in (APP / "java/com/alkamel/network/ChatKeepAliveService.kt").read_text(encoding="utf-8"),
    "background service is not stopped when leaving chat": "chatRequested.set(false)" in KOTLIN.read_text(encoding="utf-8") and "ACTION_STOP" not in KOTLIN.read_text(encoding="utf-8").split("private fun stopLanChat()",1)[1].split("private fun requestVoiceRoom()",1)[0],
    "expected socket close does not surface EBADF": "catch (e: SocketException)" in KOTLIN.read_text(encoding="utf-8") and "if (chatRunning.get() && chatRequested.get())" in KOTLIN.read_text(encoding="utf-8"),
    "socket recovery reconnects automatically": "startLanChat(chatNickname, ensureService = false)" in KOTLIN.read_text(encoding="utf-8") and "جارٍ إعادة الاتصال" in KOTLIN.read_text(encoding="utf-8"),

    "voice microphone route": "MediaRecorder.AudioSource.VOICE_COMMUNICATION" in KOTLIN.read_text(encoding="utf-8"),
    "expanded group chat UI": "chat-room-shell" in HOME.read_text(encoding="utf-8") and "chat-bottom-dock" in HOME.read_text(encoding="utf-8"),
    "microphone permission": "android.permission.RECORD_AUDIO" in MANIFEST,
    "multicast permission": "android.permission.CHANGE_WIFI_MULTICAST_STATE" in MANIFEST,
    "cleartext enabled for private HTTP services": 'android:usesCleartextTraffic="true"' in MANIFEST,
}
for label, passed in checks.items():
    print(("PASS" if passed else "FAIL") + " - " + label)
if not all(checks.values()):
    raise SystemExit(1)

home = HOME.read_text(encoding="utf-8")
for endpoint in (
    "http://k.com/status?var=callBack",
    "http://k.com/logout?erase-cookie=yes&var=callBack",
    "http://30.30.30.30:8096/web/index.html#!/home",
    "http://30.10.10.10/",
    "http://30.30.30.30:8080/tv/",
    "http://30.10.10.10:88/",
):
    if endpoint not in home and endpoint not in KOTLIN.read_text(encoding="utf-8"):
        raise SystemExit(f"FAIL - required service endpoint missing: {endpoint}")
print("PASS - documented service endpoints present")

kotlin = KOTLIN.read_text(encoding="utf-8")
for required in (
    "http://k.com/logout?var=callBack",
    "http://k.com/logout?erase-cookie=yes&var=callBack",
    "http://10.10.10.10:8888/live/index.m3u8",
):
    if required not in kotlin and required not in (APP / "assets/hd/index.html").read_text(encoding="utf-8"):
        raise SystemExit(f"FAIL - required native action or stream missing: {required}")
print("PASS - logout actions and HD stream are present")

# Syntax-check inline JavaScript blocks with Node when available.
for page in (HOME, HD):
    source = page.read_text(encoding="utf-8")
    blocks = re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>", source, re.I | re.S)
    for index, block in enumerate(blocks):
        if not block.strip():
            continue
        with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8", delete=False) as tmp:
            tmp.write(block)
            name = tmp.name
        try:
            result = subprocess.run(["node", "--check", name], capture_output=True, text=True)
            if result.returncode:
                raise SystemExit(f"FAIL - JavaScript syntax in {page.relative_to(ROOT)} block {index}: {result.stderr}")
        finally:
            Path(name).unlink(missing_ok=True)
    print(f"PASS - JavaScript syntax: {page.relative_to(ROOT)}")
