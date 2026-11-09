#!/usr/bin/env python3
from pathlib import Path
import hashlib, sys
root=Path(sys.argv[1]); hp=root/"app/src/main/assets/home.html"; kp=root/"app/src/main/java/com/alkamel/network/MainActivity.kt"; hd=root/"app/src/main/assets/hd/index.html"
hd_hash=hashlib.sha256(hd.read_bytes()).hexdigest(); s=hp.read_text(encoding="utf-8"); k=kp.read_text(encoding="utf-8")
old_live="""<section id="live" class="page"><button class="back" onclick="showPage('home')">→ الرئيسية</button><h2 class="heading">البث المباشر</h2><div class="panel"><p class="key">اختر البث المطلوب</p><button class="btn" onclick="player('org.videolan.vlc')">البث المباشر 1</button><button class="btn secondary" onclick="player('com.mxtech.videoplayer.ad')">البث المباشر 2</button><button class="btn" onclick="openHd()">بث HD</button><div class="notice" id="liveNotice"></div></div></section>"""
new_live="""<section id="live" class="page"><button class="back" onclick="showPage('home')">→ الرئيسية</button><h2 class="heading">البث المباشر</h2><div class="panel"><p class="key">اختر البث المطلوب</p><button class="btn" onclick="goService('live1')">البث المباشر 1</button><button class="btn secondary" onclick="goService('live2')">البث المباشر 2</button><button class="btn" onclick="openHd()">بث HD</button><div class="notice" id="liveNotice"></div></div></section>"""
if old_live not in s: raise SystemExit("FAIL: original live section differs")
s=s.replace(old_live,new_live,1)
old_speed="""<section id="speed" class="page"><button class="back" onclick="showPage('home')">→ الرئيسية</button><h2 class="heading">سرعة الكرت الحالية</h2><div class="panel"><div class="key">السرعة الحالية</div><div class="val" id="currentSpeed">يتم جلبها من بيانات الحساب…</div><div class="key" style="margin-top:18px">السرعات المعرفة في ملف إعدادات الموقع</div><div id="speedOptions"></div><button class="btn" id="applySpeed" onclick="applySelectedSpeed()" disabled>اعتماد السرعة المختارة</button><div class="notice" id="speedNotice">اختر السرعة ثم اعتمدها. سيتم تمرير الاختيار إلى دالة الموقع الأصلية.</div></div></section>"""
new_speed="""<section id="speed" class="page"><button class="back" onclick="showPage('home')">→ الرئيسية</button><h2 class="heading">سرعة الكرت الحالية</h2><div class="panel"><div class="key">السرعة الحالية</div><div class="val" id="currentSpeed">يتم جلبها من بيانات الحساب…</div><div class="key" style="margin-top:18px">اختر السرعة المطلوبة</div><div id="speedOptions"></div><div class="notice" id="speedNotice">اختر السرعة وسيتم تطبيقها مباشرة.</div></div></section>"""
if old_speed not in s: raise SystemExit("FAIL: original speed section differs")
s=s.replace(old_speed,new_speed,1)
s=s.replace("</style>",".speed-blink{display:inline-block;color:#7cecff;font-weight:700;animation:speedBlink 1.15s ease-in-out infinite}@keyframes speedBlink{0%,100%{opacity:1}50%{opacity:.25}}</style>",1)
s=s.replace('<span id="homeCurrentSpeed">السرعة الحالية</span>','<span id="homeCurrentSpeed" class="speed-blink">يتم جلب السرعة…</span>',1)
start=s.index("let siteConfig=null,selectedSpeed=null;"); end=s.index("function googleAction(v)",start)
js="""let siteConfig=null,selectedSpeed=null;
function speedLabel(raw){const value=String(raw||'').split('|')[0].trim();if(!value)return 'غير محددة';const opts=(siteConfig&&siteConfig.speedOptions)||[];const found=opts.find(o=>String(o.value).split('|')[0]===value);return found?found.label:value;}
function renderCurrentSpeed(){if(!lastData)return;const value=String(lastData.spes||'').split('|')[0];const label=speedLabel(value);$('currentSpeed').textContent=label;$('homeCurrentSpeed').textContent=label;$('spes').textContent=label;}
function renderSpeeds(){if(siteConfig){fillSpeeds();renderCurrentSpeed();return;}const s=document.createElement('script');s.src='original/js/config%20(87).js';s.onload=()=>{siteConfig=window.siteConfig;fillSpeeds();renderCurrentSpeed();};s.onerror=()=>$('speedNotice').textContent='تعذر قراءة إعدادات السرعة الأصلية.';document.head.appendChild(s);}
function fillSpeeds(){const list=(siteConfig&&siteConfig.speedOptions)||[];$('speedOptions').innerHTML='';list.forEach(o=>{const e=document.createElement('button');e.type='button';e.className='option';e.style.width='100%';e.style.color='inherit';e.style.font='inherit';e.style.textAlign='right';const current=String(lastData&&lastData.spes||'').split('|')[0];e.setAttribute('aria-pressed',String(current===o.value));const t=document.createElement('span');t.textContent=o.label+(current===o.value?' · السرعة الحالية':(o.selected?' · الافتراضية':''));e.appendChild(t);e.onclick=()=>{selectedSpeed=o.value;$('speedNotice').textContent='جارٍ تطبيق '+o.label+'…';const flag=(lastData&&lastData.spes&&String(lastData.spes).includes('|'))?String(lastData.spes).split('|')[1]:'*no**';if(window.AndroidBridge)AndroidBridge.applyNetworkSetting(o.value,flag);else $('speedNotice').textContent='يتطلب تطبيق السرعة من تطبيق Android.';};$('speedOptions').appendChild(e);});}
"""
s=s[:start]+js+s[end:]
old_status="""$('spes').textContent=d.spes||'—';const spesParts=String(d.spes||'').split('|');
$('currentSpeed').textContent=spesParts[0]||'غير محددة';"""
new_status="""$('spes').textContent=speedLabel(String(d.spes||'').split('|')[0]);const spesParts=String(d.spes||'').split('|');
$('currentSpeed').textContent=speedLabel(spesParts[0]);$('homeCurrentSpeed').textContent=speedLabel(spesParts[0]);"""
if old_status not in s: raise SystemExit("FAIL: status speed renderer differs")
s=s.replace(old_status,new_status,1)
startup="refreshStatus();setInterval(refreshStatus,30000);window.appBack=()=>showPage('home');"
if startup not in s: raise SystemExit("FAIL: startup sequence differs")
s=s.replace(startup,"renderSpeeds();refreshStatus();setInterval(refreshStatus,30000);window.appBack=()=>showPage('home');",1)
old_cb="""if(document.getElementById('speedNotice')){document.getElementById('speedNotice').textContent=\${JSONObject.quote(message)};document.getElementById('applySpeed').disabled=false;}if(document.getElementById('googleNotice'))document.getElementById('googleNotice').textContent=\${JSONObject.quote(message)};"""
new_cb="""if(document.getElementById('speedNotice')){document.getElementById('speedNotice').textContent=\${JSONObject.quote(message)};if(\${success})setTimeout(function(){if(window.AndroidBridge)AndroidBridge.refresh();},1200);}if(document.getElementById('googleNotice'))document.getElementById('googleNotice').textContent=\${JSONObject.quote(message)};"""
if old_cb not in k: raise SystemExit("FAIL: native command callback differs")
k=k.replace(old_cb,new_cb,1)
hp.write_text(s,encoding="utf-8"); kp.write_text(k,encoding="utf-8")
if hashlib.sha256(hd.read_bytes()).hexdigest()!=hd_hash: raise SystemExit("FAIL: HD asset changed")
for item in ["original/js/config%20(87).js","AndroidBridge.applyNetworkSetting(o.value,flag)","goService('live1')","goService('live2')","speed-blink"]:
    if item not in s: raise SystemExit("FAIL: missing "+item)
if "اعتماد السرعة المختارة" in s or "v.textContent=o.value" in s: raise SystemExit("FAIL: old speed UI/raw values remain")
if "function openHd()" not in s: raise SystemExit("FAIL: HD handler missing")
print("PATCH_OK: speed/live functions connected; HD unchanged",hd_hash)
