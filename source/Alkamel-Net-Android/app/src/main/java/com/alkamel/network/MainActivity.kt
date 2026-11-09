package com.alkamel.network

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioAttributes
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var commandWeb: WebView? = null
    private var commandPageReady = false
    private var pendingCommand: Pair<String, String?>? = null
    private lateinit var root: FrameLayout
    private lateinit var loading: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val homeUrl = "https://appassets.androidplatform.net/assets/home.html"
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }
    private val refreshInProgress = AtomicBoolean(false)
    private val chatRunning = AtomicBoolean(false)
    @Volatile private var chatSocket: DatagramSocket? = null
    @Volatile private var chatNickname = ""
    private val chatId = java.util.UUID.randomUUID().toString()
    private val chatExecutor = Executors.newSingleThreadExecutor()
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private val voiceReceiveExecutor = Executors.newSingleThreadExecutor()
    private val voiceRunning = AtomicBoolean(false)
    private val voiceMuted = AtomicBoolean(false)
    @Volatile private var voiceSocket: java.net.MulticastSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    private val voiceGroup = InetAddress.getByName("239.10.10.10")
    private val voicePort = 39128
    private val voicePermissionRequest = 7412
    private var localFallbackAttempted = false
    private var pendingPageAfterHome: String? = null
    private val allowedHosts = setOf("k.com", "30.30.30.30", "30.10.10.10", "10.10.10.10")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 20, 38)
        window.navigationBarColor = Color.rgb(7, 20, 38)
        CookieManager.getInstance().setAcceptCookie(true)

        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 20, 38)) }
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(7, 20, 38))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    Log.d("AlkamelNet-Web", "${message.message()} -- ${message.sourceId()}:${message.lineNumber()}")
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val loaded = assetLoader.shouldInterceptRequest(uri)
                    if (loaded != null) return loaded
                    // Compatibility fallback: serve bundled assets directly if the
                    // AndroidX asset loader cannot resolve a local resource.
                    if (uri.host == "appassets.androidplatform.net" && uri.path?.startsWith("/assets/") == true) {
                        val relative = uri.path!!.removePrefix("/assets/")
                        val mime = when (relative.substringAfterLast('.', "").lowercase()) {
                            "html" -> "text/html"
                            "js" -> "application/javascript"
                            "css" -> "text/css"
                            "json" -> "application/json"
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "svg" -> "image/svg+xml"
                            "woff", "woff2" -> "font/woff2"
                            "ttf" -> "font/ttf"
                            else -> "application/octet-stream"
                        }
                        val textMime = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")
                        return try {
                            WebResourceResponse(mime, if (textMime) "UTF-8" else null, assets.open(relative))
                        } catch (e: Exception) {
                            Log.e("AlkamelNet", "Missing bundled asset: $relative", e)
                            null
                        }
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.host == "appassets.androidplatform.net" && uri.scheme == "https") return false
                    if (uri.scheme == "http" && uri.host in allowedHosts) return false
                    Log.w("AlkamelNet", "Blocked navigation: $uri")
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    showLoading(true)
                    Log.i("AlkamelNet", "Page started: $url")
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    showLoading(false)
                    hideError()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    showLoading(false)
                    Log.i("AlkamelNet", "Page finished: $url")
                    if (url == homeUrl) {
                        val returnPage = pendingPageAfterHome
                        pendingPageAfterHome = null
                        if (returnPage == "live") {
                            view?.evaluateJavascript("if(typeof showPage==='function')showPage('live');", null)
                        }
                        view?.evaluateJavascript("document.documentElement && document.body ? document.body.innerText.length : 0") { value ->
                            val hasContent = value != null && value != "null" && value != "0" && value != "\"\""
                            if (!hasContent) loadHomeFallback("الصفحة الرئيسية فارغة")
                        }
                    }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (failingUrl == view?.url) {
                        Log.e("AlkamelNet", "Load error $errorCode: $description ($failingUrl)")
                        if (urlIsLocal(failingUrl)) loadHomeFallback(description ?: "خطأ غير معروف")
                        else { showLoading(false); showError("تعذر تحميل الصفحة: ${description ?: "خطأ غير معروف"}") }
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val description = error?.description?.toString() ?: "تعذر تحميل الصفحة"
                        Log.e("AlkamelNet", "Main frame error: $description")
                        if (request?.url?.host == "appassets.androidplatform.net") loadHomeFallback(description)
                        else { showLoading(false); showError("تعذر تحميل الصفحة: $description") }
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        Log.e("AlkamelNet", "HTTP error ${errorResponse?.statusCode} at ${request.url}")
                        val message = "HTTP ${errorResponse?.statusCode ?: "?"}"
                        if (request.url.host == "appassets.androidplatform.net") loadHomeFallback(message)
                        else { showLoading(false); showError("تعذر فتح الصفحة ($message).") }
                    }
                }
            }
        }

        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(7, 20, 38))
            addView(ProgressBar(this@MainActivity))
            addView(TextView(this@MainActivity).apply {
                text = "جاري تشغيل الكامل-نت…"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(24, 18, 24, 0)
            })
        }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.rgb(7, 20, 38))
            addView(TextView(this@MainActivity).apply {
                text = "تعذر تشغيل التطبيق"
                setTextColor(Color.WHITE)
                textSize = 21f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                tag = "error_text"
                setTextColor(Color.LTGRAY)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 14, 0, 18)
            })
            addView(Button(this@MainActivity).apply {
                text = "إعادة المحاولة"
                setOnClickListener { hideError(); loadHome() }
            })
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        errorPanel.visibility = View.GONE
        setContentView(root)
        initializeCommandWebView()

        if (savedInstanceState != null) {
            val restored = web.restoreState(savedInstanceState)
            if (restored == null) loadHome() else showLoading(false)
        } else loadHome()
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeCommandWebView() {
        commandWeb = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url?.startsWith("http://k.com") == true) {
                        view?.evaluateJavascript("if(typeof getRequest==='function')getRequest('/status?var=callBack');", null)
                        view?.evaluateJavascript(
                            "(function(){var n=0;var t=setInterval(function(){n++;if(window.un&&window.un.username){clearInterval(t);window.portalCommandReady=true;}else if(n>=40){clearInterval(t);}},250);})()",
                            null
                        )
                        commandPageReady = true
                        runPendingCommand()
                    }
                }
            }
            alpha = 0f
        }
        commandWeb?.let { hidden ->
            root.addView(hidden, FrameLayout.LayoutParams(1, 1))
            hidden.loadUrl("http://k.com/")
        }
    }

    private fun runPendingCommand() {
        val command = pendingCommand ?: return
        val target = commandWeb ?: return
        if (!commandPageReady) return
        pendingCommand = null
        val speed = JSONObject.quote(command.first)
        val flag = JSONObject.quote(command.second ?: "")
        target.evaluateJavascript(
            "(function(){if(typeof loginst!=='function')return 'missing-loginst';if(!window.un||!window.un.username)return 'subscriber-not-ready';loginst($speed,$flag);return 'submitted';})()"
        ) { result ->
            runOnUiThread {
                val success = result?.contains("submitted") == true
                val message = if (success) "تم تمرير الطلب إلى كود الموقع الأصلي." else "تعذر العثور على دالة الموقع الأصلية؛ لم يتم إرسال الطلب."
                web.evaluateJavascript(
                    "if(document.getElementById('speedNotice')){document.getElementById('speedNotice').textContent=${JSONObject.quote(message)};if(${success})setTimeout(function(){if(window.AndroidBridge)AndroidBridge.refresh();},1200);}if(document.getElementById('googleNotice'))document.getElementById('googleNotice').textContent=${JSONObject.quote(message)};",
                    null
                )
                if (!success) Log.e("AlkamelNet", "Original portal loginst() unavailable: $result")
            }
        }
    }


    private fun startLanChat(name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        chatNickname = clean
        if (!chatRunning.compareAndSet(false, true)) { sendLanPacket("presence", ""); return }
        chatExecutor.execute {
            try {
                val socket = DatagramSocket(null).apply { reuseAddress = true; soTimeout = 1000; bind(java.net.InetSocketAddress(39127)) }
                chatSocket = socket
                var lastPresence = 0L
                while (chatRunning.get()) {
                    val now = System.currentTimeMillis()
                    if (now - lastPresence >= 5000) { sendLanPacket("presence", ""); lastPresence = now }
                    try {
                        val buffer = ByteArray(4096); val packet = DatagramPacket(buffer, buffer.size); socket.receive(packet)
                        val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val obj = JSONObject(raw)
                        if (obj.optString("id") != chatId && obj.optString("type") in setOf("presence", "message")) {
                            runOnUiThread { if (::web.isInitialized && !isFinishing) web.evaluateJavascript("if(window.onLanChatPacket)window.onLanChatPacket(${JSONObject.quote(raw)});", null) }
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            } catch (e: Exception) { Log.e("AlkamelNet", "LAN chat socket failed", e); chatRunning.set(false) }
            finally { chatSocket?.close(); chatSocket = null }
        }
    }
    private fun sendLanPacket(type: String, message: String) {
        if (!chatRunning.get()) return
        try {
            val data = JSONObject().put("type", type).put("id", chatId).put("name", chatNickname).put("text", message).toString().toByteArray(Charsets.UTF_8)
            DatagramSocket().use { sender -> sender.broadcast = true; sender.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 39127)) }
        } catch (e: Exception) { Log.w("AlkamelNet", "LAN chat send failed", e) }
    }
    private fun stopLanChat() { chatRunning.set(false); chatSocket?.close(); chatSocket = null }


    private fun requestVoiceRoom() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), voicePermissionRequest)
            } else startVoiceRoom()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == voicePermissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoiceRoom()
                runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('error'," + JSONObject.quote(e.localizedMessage ?: "Voice room error") + ");", null) }
        }
    }

    private fun startVoiceRoom() {
        if (!chatRunning.get()) {
                runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('error'," + JSONObject.quote(e.localizedMessage ?: "Voice room error") + ");", null) }
            return
        }
        if (!voiceRunning.compareAndSet(false, true)) return
        voiceMuted.set(false)
        voiceExecutor.execute {
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            try {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("AlkamelNetVoice").apply { setReferenceCounted(false); acquire() }
                val socket = java.net.MulticastSocket(null).apply {
                    reuseAddress = true
                    timeToLive = 1
                    soTimeout = 200
                    bind(java.net.InetSocketAddress(voicePort))
                    joinGroup(voiceGroup)
                }
                voiceSocket = socket
                val rate = 16000
                val inputMask = AudioFormat.CHANNEL_IN_MONO
                val outputMask = AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minIn = AudioRecord.getMinBufferSize(rate, inputMask, encoding)
                val minOut = AudioTrack.getMinBufferSize(rate, outputMask, encoding)
                if (minIn <= 0 || minOut <= 0) throw IllegalStateException("Audio device unavailable")
                val frame = 640
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(inputMask).build())
                    .setBufferSizeInBytes(maxOf(minIn, frame * 4)).build()
                player = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(outputMask).build())
                    .setBufferSizeInBytes(maxOf(minOut, frame * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                val activeRecorder = recorder
                val activePlayer = player
                activeRecorder.startRecording()
                activePlayer.play()
                runOnUiThread { web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('joined','');", null) }

                voiceReceiveExecutor.execute {
                    val incoming = ByteArray(1200)
                    while (voiceRunning.get()) {
                        try {
                            val packet = DatagramPacket(incoming, incoming.size)
                            socket.receive(packet)
                            val end = (packet.offset until packet.offset + packet.length).firstOrNull { incoming[it] == 10.toByte() } ?: continue
                            val sender = String(incoming, packet.offset, end - packet.offset, Charsets.UTF_8)
                            if (sender == chatId) continue
                            val start = end + 1
                            val count = packet.offset + packet.length - start
                            if (count > 0) {
                                activePlayer.write(incoming, start, count)
                                runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceActivity&&window.onVoiceActivity(\${JSONObject.quote(sender)});", null) }
                            }
                        } catch (_: SocketTimeoutException) { }
                        catch (e: Exception) { if (voiceRunning.get()) Log.w("AlkamelNet", "Voice receive failed", e) }
                    }
                }

                val pcm = ByteArray(frame)
                val prefix = (chatId + "\n").toByteArray(Charsets.UTF_8)
                val outgoing = ByteArray(prefix.size + frame)
                System.arraycopy(prefix, 0, outgoing, 0, prefix.size)
                var lastUi = 0L
                while (voiceRunning.get()) {
                    val count = activeRecorder.read(pcm, 0, pcm.size)
                    if (count <= 0) continue
                    var energy = 0.0
                    var i = 0
                    while (i + 1 < count) {
                        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()
                        energy += sample.toDouble() * sample.toDouble()
                        i += 2
                    }
                    val rms = kotlin.math.sqrt(energy / (count / 2).coerceAtLeast(1))
                    val speaking = !voiceMuted.get() && rms > 550.0
                    if (!voiceMuted.get()) {
                        System.arraycopy(pcm, 0, outgoing, prefix.size, count)
                        socket.send(DatagramPacket(outgoing, prefix.size + count, voiceGroup, voicePort))
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 300) {
                        val state = if (voiceMuted.get()) "muted" else if (speaking) "speaking" else "joined"
                        runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceState&&window.onVoiceState(\${JSONObject.quote(state)},'');", null) }
                        lastUi = now
                    }
                }
            } catch (e: Exception) {
                Log.e("AlkamelNet", "Voice room failed", e)
                runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('error'," + JSONObject.quote(e.localizedMessage ?: "Voice room error") + ");", null) }
            } finally {
                voiceRunning.set(false)
                try { recorder?.stop() } catch (_: Exception) { }
                try { recorder?.release() } catch (_: Exception) { }
                try { player?.stop() } catch (_: Exception) { }
                try { player?.release() } catch (_: Exception) { }
                voiceSocket?.let { try { it.leaveGroup(voiceGroup); it.close() } catch (_: Exception) { } }
                voiceSocket = null
                multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) { } }
                multicastLock = null
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = false }
            }
        }
    }

    private fun setVoiceMute(muted: Boolean) {
        voiceMuted.set(muted)
        if (voiceRunning.get()) {
            val state = if (muted) "muted" else "joined"
            runOnUiThread { web.evaluateJavascript("window.onVoiceState&&window.onVoiceState(\${JSONObject.quote(state)},'');", null) }
        }
    }

    private fun stopVoiceRoom() {
        voiceRunning.set(false)
        voiceSocket?.close()
        voiceSocket = null
    }

    private fun urlIsLocal(url: String?): Boolean = url?.startsWith("https://appassets.androidplatform.net/assets/") == true

    private fun loadHomeFallback(reason: String) {
        if (localFallbackAttempted || isFinishing) {
            showLoading(false)
            showError("تعذر تشغيل واجهة التطبيق. $reason")
            return
        }
        localFallbackAttempted = true
        io.execute {
            try {
                val html = assets.open("home.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
                runOnUiThread {
                    if (!isFinishing && ::web.isInitialized) {
                        Log.w("AlkamelNet", "Loading home through embedded HTML fallback: $reason")
                        web.loadDataWithBaseURL(homeUrl, html, "text/html", "UTF-8", null)
                    }
                }
            } catch (e: Exception) {
                Log.e("AlkamelNet", "Embedded home fallback failed", e)
                showLoading(false)
                showError("تعذر قراءة ملفات واجهة التطبيق: ${e.localizedMessage ?: reason}")
            }
        }
    }

    private fun loadHome() {
        try {
            showErrorHidden()
            showLoading(true)
            // Load the bundled HTML directly as the primary route. This avoids
            // depending on WebViewAssetLoader for the first document, which can
            // otherwise leave older WebView implementations on a blank screen.
            localFallbackAttempted = true
            val html = assets.open("home.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            web.loadDataWithBaseURL(homeUrl, html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            Log.e("AlkamelNet", "Embedded home load failed; trying asset URL", e)
            try {
                localFallbackAttempted = false
                web.loadUrl(homeUrl)
            } catch (second: Exception) {
                Log.e("AlkamelNet", "Unable to load home", second)
                showLoading(false)
                showError("تعذر بدء واجهة التطبيق: ${second.localizedMessage ?: e.localizedMessage ?: "خطأ غير معروف"}")
            }
        }
    }

    private fun showLoading(show: Boolean) {
        if (!::loading.isInitialized) return
        runOnUiThread { loading.visibility = if (show) View.VISIBLE else View.GONE }
    }

    private fun hideError() {
        if (!::errorPanel.isInitialized) return
        runOnUiThread { errorPanel.visibility = View.GONE }
    }

    private fun showErrorHidden() {
        if (::errorPanel.isInitialized) errorPanel.visibility = View.GONE
    }

    private fun showError(message: String) {
        if (!::errorPanel.isInitialized || isFinishing) return
        runOnUiThread {
            errorPanel.findViewWithTag<TextView>("error_text")?.text = message
            errorPanel.visibility = View.VISIBLE
        }
    }

    inner class Bridge {
        @JavascriptInterface fun startChat(name: String) { startLanChat(name) }
        @JavascriptInterface fun sendChatMessage(message: String) { if (message.length <= 1000) sendLanPacket("message", message.trim()) }
        @JavascriptInterface fun stopChat() { stopVoiceRoom(); stopLanChat() }
        @JavascriptInterface fun joinVoiceRoom() { requestVoiceRoom() }
        @JavascriptInterface fun setVoiceMuted(muted: Boolean) { setVoiceMute(muted) }
        @JavascriptInterface fun leaveVoiceRoom() { stopVoiceRoom() }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@runOnUiThread
                if (uri.scheme == "http" && uri.host in allowedHosts) {
                    pendingPageAfterHome = if (url.startsWith("http://30.30.30.30:8080/tv/") || url.startsWith("http://30.10.10.10:88/")) "live" else null
                    web.loadUrl(url)
                } else Toast.makeText(this@MainActivity, "الرابط غير مسموح به داخل التطبيق", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface fun openHd() { runOnUiThread { pendingPageAfterHome = "live"; web.loadUrl("https://appassets.androidplatform.net/assets/hd/index.html") } }
        @JavascriptInterface fun home() { runOnUiThread { if (web.url != homeUrl) loadHome() } }

        @JavascriptInterface
        fun logout() {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تسجيل الخروج")
                    .setMessage("هل تريد إنهاء جلسة حسابك في شبكة الكامل؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("تسجيل الخروج") { _, _ -> web.loadUrl("http://k.com/logout?erase-cookie=yes&var=callBack") }
                    .show()
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("قطع الاتصال")
                    .setMessage("هل تريد قطع اتصالك الحالي بالشبكة؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("قطع الاتصال") { _, _ ->
                        // Match the original portal userLogout() default: end the session
                        // without requesting erase-cookie=yes. Full logout below
                        // explicitly clears the portal cookie.
                        web.loadUrl("http://k.com/logout?var=callBack")
                    }
                    .show()
            }
        }

        @JavascriptInterface
        fun refresh() {
            if (!refreshInProgress.compareAndSet(false, true)) return
            io.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL("http://k.com/status?var=callBack").openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                    connection.setRequestProperty("Pragma", "no-cache")
                    CookieManager.getInstance().getCookie("http://k.com")?.let { connection.setRequestProperty("Cookie", it) }
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                    val json = JSONObject(body)
                    if (!json.has("logged_in")) throw IllegalStateException("Invalid status response")
                    val payload = JSONObject.quote(json.toString())
                    runOnUiThread {
                        if (::web.isInitialized && !isFinishing) web.evaluateJavascript("if(window.onStatusData){window.onStatusData(JSON.parse($payload));}", null)
                    }
                } catch (e: Exception) {
                    Log.w("AlkamelNet", "Status refresh failed", e)
                    runOnUiThread {
                        if (::web.isInitialized && !isFinishing) web.evaluateJavascript("if(document.getElementById('queryNotice'))document.getElementById('queryNotice').textContent='تعذر جلب البيانات. تحقق من اتصالك بشبكة الكامل.';", null)
                    }
                } finally {
                    connection?.disconnect()
                    refreshInProgress.set(false)
                }
            }
        }

        @JavascriptInterface
        fun applyNetworkSetting(speed: String, googleFlag: String?) {
            runOnUiThread {
                if (!speed.matches(Regex("[A-Za-z0-9./]+"))) {
                    Toast.makeText(this@MainActivity, "قيمة السرعة غير صالحة", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (googleFlag != null && googleFlag !in setOf("*yes**", "*no**")) {
                    Toast.makeText(this@MainActivity, "قيمة إعداد جوجل غير صالحة", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                pendingCommand = speed to googleFlag
                if (commandWeb == null) initializeCommandWebView()
                runPendingCommand()
            }
        }

        @JavascriptInterface
        fun externalPlayer(url: String, packageName: String) {
            runOnUiThread {
                // Only allow the bundled HD stream and the two player packages
                // exposed by the app UI; never pass arbitrary URLs to other apps.
                if (url != "http://10.10.10.10:8888/live/index.m3u8" ||
                    packageName !in setOf("org.videolan.vlc", "com.mxtech.videoplayer.ad")) {
                    Toast.makeText(this@MainActivity, "طلب تشغيل غير مسموح به", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(url), "application/vnd.apple.mpegurl")
                        setPackage(packageName)
                    }
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "المشغل المطلوب غير مثبت على الجهاز", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("AlkamelNet", "External player launch failed", e)
                    Toast.makeText(this@MainActivity, "تعذر فتح المشغل الخارجي", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!::web.isInitialized) {
            super.onBackPressed()
            return
        }
        val currentUrl = web.url.orEmpty()
        val isLivePlayer = currentUrl.startsWith("http://30.30.30.30:8080/tv/") ||
            currentUrl.startsWith("http://30.10.10.10:88/") ||
            currentUrl.startsWith("https://appassets.androidplatform.net/assets/hd/")
        if (isLivePlayer) {
            pendingPageAfterHome = "live"
            loadHome()
        } else if (currentUrl.startsWith(homeUrl) || currentUrl.startsWith("data:")) {
            web.evaluateJavascript(
                "if(typeof window.appBack==='function'){window.appBack();'handled';}else{'reload';}"
            ) { result ->
                if (result == null || result.contains("reload")) runOnUiThread { loadHome() }
            }
        } else {
            loadHome()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.removeJavascriptInterface("AndroidBridge")
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
        commandWeb?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        commandWeb = null
        stopVoiceRoom()
        stopLanChat()
        io.shutdownNow()
        voiceExecutor.shutdownNow()
        voiceReceiveExecutor.shutdownNow()
        chatExecutor.shutdownNow()
        super.onDestroy()
    }
}
