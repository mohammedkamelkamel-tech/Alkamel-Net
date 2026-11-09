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
    "minSdk 24": "minSdk = 24" in GRADLE,
    "targetSdk 35": "targetSdk = 35" in GRADLE,
    "app label": 'android:label="الكامل-نت"' in MANIFEST,
    "internet permission": "android.permission.INTERNET" in MANIFEST,
    "home asset": HOME.is_file() and HOME.stat().st_size > 0,
    "HD asset": HD.is_file() and HD.stat().st_size > 0,
    "native activity": KOTLIN.is_file() and "class MainActivity" in KOTLIN.read_text(encoding="utf-8"),
    "LAN chat bridge": "startLanChat" in KOTLIN.read_text(encoding="utf-8") and "sendLanPacket" in KOTLIN.read_text(encoding="utf-8"),
    "chat page": 'id="chat"' in HOME.read_text(encoding="utf-8") and "onLanChatPacket" in HOME.read_text(encoding="utf-8"),
    "unified chat room UI": "غرفة عامة" in HOME.read_text(encoding="utf-8") and "appendChatMessage" in HOME.read_text(encoding="utf-8"),
    "chat sender highlighting": "chat-name" in HOME.read_text(encoding="utf-8") and ".chat-message.mine" in HOME.read_text(encoding="utf-8"),
    "voice controls": "joinVoiceRoom" in HOME.read_text(encoding="utf-8") and "toggleVoiceMute" in HOME.read_text(encoding="utf-8"),
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
    'http://k.com/logout?var=callBack',
    'http://k.com/logout?erase-cookie=yes&var=callBack',
    'http://10.10.10.10:8888/live/index.m3u8',
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
