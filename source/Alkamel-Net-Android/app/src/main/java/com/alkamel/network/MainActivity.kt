package com.alkamel.network

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.AudioAttributes
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.pm.PackageManager
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
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var commandWeb: WebView? = null
    private var commandPageReady = false
    private var pendingCommand: Pair<String, String?>? = null
    private var commandAttemptInProgress = false
    private var pendingCommandAttempts = 0
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
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var resumeRecoveryToken = 0
    @Volatile private var appInForeground = false
    private val notificationChannelId = "alkamel_events"
    private val notificationPermissionRequest = 7413
    private var requestNotificationsOnResume = false
    private val chatRunning = AtomicBoolean(false)
    private val chatRequested = AtomicBoolean(false)
    @Volatile private var chatSocket: DatagramSocket? = null
    @Volatile private var chatNickname = ""
    private val chatId = java.util.UUID.randomUUID().toString()
    private val chatPeerAddresses = ConcurrentHashMap<String, InetAddress>()
    private val chatExecutor = Executors.newSingleThreadExecutor()
    private val chatSendExecutor = Executors.newSingleThreadExecutor()
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
    @Volatile private var backgroundedAtMs: Long = 0L
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
            // Keep the single WebView warm and hardware accelerated for faster LAN navigation.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                settings.offscreenPreRaster = true
            }
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

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    Log.e("AlkamelNet", "WebView renderer exited; recovering Activity")
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) recreate()
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Keep the native JavaScript bridge limited to bundled application pages.
                    // The bundled home is loaded with loadDataWithBaseURL and some
                    // WebView versions report its document URL as data:. Keep the native
                    // bridge attached for that embedded home document; remote service pages
                    // still have the bridge removed.
                    if (urlIsLocal(url) || url?.startsWith("data:") == true) {
                        view?.addJavascriptInterface(Bridge(), "AndroidBridge")
                    } else {
                        view?.removeJavascriptInterface("AndroidBridge")
                    }
                    // Avoid flashing the full-screen loader for quick local page transitions.
                    if (urlIsLocal(url) || url?.startsWith("http://30.") == true || url?.startsWith("http://10.") == true) {
                        showLoading(false)
                    } else {
                        showLoading(true)
                    }
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
                        if (returnPage == "live" || returnPage == "rest") {
                            view?.evaluateJavascript("if(typeof showPage==='function')showPage('$returnPage');", null)
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
        createEventNotificationChannel()
        showFirstRunSetupIfNeeded()
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
        if (!commandPageReady || commandAttemptInProgress) return
        commandAttemptInProgress = true
        val speed = JSONObject.quote(command.first)
        val flag = JSONObject.quote(command.second ?: "")
        target.evaluateJavascript(
            "(function(){if(typeof loginst!=='function')return 'missing-loginst';if(!window.un||!window.un.username)return 'subscriber-not-ready';loginst($speed,$flag);return 'submitted';})()"
        ) { result ->
            runOnUiThread {
                commandAttemptInProgress = false
                if (pendingCommand != command) {
                    pendingCommandAttempts = 0
                    runPendingCommand()
                    return@runOnUiThread
                }
                val success = result?.contains("submitted") == true
                val waitingForSubscriber = result?.contains("subscriber-not-ready") == true
                if (waitingForSubscriber && pendingCommandAttempts < 20) {
                    pendingCommandAttempts++
                    target.postDelayed({ runPendingCommand() }, 500)
                    return@runOnUiThread
                }
                pendingCommand = null
                pendingCommandAttempts = 0
                val message = when {
                    success -> "تم تمرير الطلب إلى كود الموقع الأصلي."
                    waitingForSubscriber -> "لم تكتمل قراءة بيانات المشترك من بوابة الشبكة؛ لم يتم إرسال الطلب."
                    else -> "تعذر العثور على دالة الموقع الأصلية؛ لم يتم إرسال الطلب."
                }
                web.evaluateJavascript(
                    "if(document.getElementById('speedNotice')){document.getElementById('speedNotice').textContent=${JSONObject.quote(message)};if(${success})setTimeout(function(){if(window.AndroidBridge)AndroidBridge.refresh();},1200);}if(document.getElementById('googleNotice'))document.getElementById('googleNotice').textContent=${JSONObject.quote(message)};",
                    null
                )
                if (!success) Log.e("AlkamelNet", "Original portal command was not submitted: $result")
            }
        }
    }


    private fun ensureBackgroundService() {
        val intent = Intent(this, ChatKeepAliveService::class.java).apply { action = ChatKeepAliveService.ACTION_START }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Log.e("AlkamelNet", "Unable to keep app alive in background", e)
            Toast.makeText(this, "تعذر تفعيل العمل في الخلفية. تحقق من إعدادات البطارية.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFirstRunSetupIfNeeded() {
        val prefs = getSharedPreferences("alkamel_app_setup", MODE_PRIVATE)
        if (prefs.getBoolean("first_run_setup_asked", false)) {
            if (prefs.getBoolean("background_enabled", false)) ensureBackgroundService()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("تفعيل عمل شبكة الكامل في الخلفية")
            .setMessage("فعّل خدمة العمل في الخلفية لتستمر خدمات الشبكة والدردشة عند تصغير التطبيق. سنطلب إذن الإشعارات لتنبيهك بالرسائل والإعلانات المهمة.")
            .setCancelable(false)
            .setNegativeButton("لاحقاً") { _, _ ->
                prefs.edit().putBoolean("first_run_setup_asked", true).apply()
            }
            .setPositiveButton("تفعيل الآن") { _, _ ->
                prefs.edit().putBoolean("first_run_setup_asked", true).putBoolean("background_enabled", true).apply()
                ensureBackgroundService()
                requestNotificationsOnResume = false
                requestNotificationPermission()
                Toast.makeText(this, "تم تفعيل خدمة العمل في الخلفية.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun createEventNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(notificationChannelId, "تنبيهات شبكة الكامل", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعارات الدردشة والإعلانات والعروض والتنبيهات المهمة"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
        }
    }

    private fun postEventNotification(title: String, message: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openApp = PendingIntent.getActivity(
            this, notificationId,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, notificationChannelId) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notification = builder.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(message.take(180))
            .setStyle(Notification.BigTextStyle().bigText(message.take(500)))
            .setAutoCancel(true).setContentIntent(openApp).build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun notifyIncomingChat(obj: JSONObject) {
        if (appInForeground) return
        val type = obj.optString("type")
        val sender = obj.optString("name", "عضو")
        val message = obj.optString("text").trim()
        val privateMessage = type == "private_message" && obj.optString("to") == chatId
        val privateRequest = type == "private_request" && obj.optString("to") == chatId
        val nickname = chatNickname.trim()
        val mentioned = nickname.isNotEmpty() && message.contains(nickname, ignoreCase = true)
        if (type != "message" && !privateMessage && !privateRequest) return
        val title = when {
            privateRequest -> "طلب محادثة خاصة جديد"
            privateMessage -> "رسالة خاصة جديدة من $sender"
            mentioned -> "تم ذكرك في دردشة شبكة الكامل"
            else -> "رسالة جديدة في دردشة شبكة الكامل"
        }
        val body = when {
            privateRequest -> "يريد $sender بدء محادثة خاصة معك."
            else -> "$sender: $message"
        }
        postEventNotification(title, body, (System.currentTimeMillis() % 2000000000L).toInt())
    }

    private fun startLanChat(name: String, ensureService: Boolean = true) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        chatNickname = clean
        chatRequested.set(true)
        if (ensureService && getSharedPreferences("alkamel_app_setup", MODE_PRIVATE).getBoolean("background_enabled", false)) {
        val keepAlive = Intent(this, ChatKeepAliveService::class.java).apply { action = ChatKeepAliveService.ACTION_START }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(keepAlive) else startService(keepAlive)
        } catch (e: Exception) {
            Log.e("AlkamelNet", "Unable to start background chat service", e)
            runOnUiThread { if (::web.isInitialized && !isDestroyed) web.evaluateJavascript("window.onChatServiceError&&window.onChatServiceError();", null) }
        }
        }
        if (!chatRunning.compareAndSet(false, true)) {
            sendLanPacket("presence", "")
            notifyChatState("joined", "")
            return
        }
        chatExecutor.execute {
            if (!chatRunning.get()) return@execute
            try {
                val socket = DatagramSocket(null).apply { reuseAddress = true; soTimeout = 1000; bind(java.net.InetSocketAddress(39127)) }
                if (!chatRunning.get()) {
                    socket.close()
                    return@execute
                }
                chatSocket = socket
                notifyChatState("joined", "")
                var lastPresence = 0L
                while (chatRunning.get()) {
                    val now = System.currentTimeMillis()
                    if (now - lastPresence >= 5000) { sendLanPacket("presence", ""); lastPresence = now }
                    try {
                        val buffer = ByteArray(4096); val packet = DatagramPacket(buffer, buffer.size); socket.receive(packet)
                        val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val obj = JSONObject(raw)
                        val packetType = obj.optString("type")
                        val senderId = obj.optString("id")
                        if (senderId != chatId) {
                            // Learn the sender's current LAN address from both presence and
                            // private handshake packets. The receiver may not have seen the
                            // sender's latest broadcast yet when it needs to accept/reject.
                            if (packetType in setOf("presence", "private_request", "private_response", "private_message")) {
                                chatPeerAddresses[senderId] = packet.address
                            }
                            val isPublicPacket = packetType in setOf("presence", "message")
                            val isPrivatePacket = packetType in setOf("private_request", "private_response", "private_message") && obj.optString("to") == chatId
                            if (isPublicPacket || isPrivatePacket) {
                                if (packetType == "message" || packetType == "private_message" || packetType == "private_request") {
                                    try { notifyIncomingChat(obj) } catch (notificationError: Exception) { Log.w("AlkamelNet", "Unable to show chat notification", notificationError) }
                                }
                                runOnUiThread { if (::web.isInitialized && !isFinishing && !isDestroyed) web.evaluateJavascript("if(window.onLanChatPacket)window.onLanChatPacket(${JSONObject.quote(raw)});", null) }
                            }
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            } catch (e: SocketException) {
                // Closing a DatagramSocket while receive() is blocked raises EBADF.
                // Treat intentional shutdown as normal; retry unexpected socket loss.
                if (chatRunning.get() && chatRequested.get()) {
                    Log.w("AlkamelNet", "LAN chat socket interrupted; preparing reconnect", e)
                    chatRunning.set(false)
                    notifyChatState("reconnecting", "انقطع اتصال الدردشة مؤقتاً، جارٍ إعادة الاتصال…")
                    try { Thread.sleep(1200) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    if (chatRequested.get()) startLanChat(chatNickname, ensureService = false)
                }
            } catch (e: Exception) {
                if (chatRunning.get() && chatRequested.get()) {
                    Log.e("AlkamelNet", "LAN chat socket failed", e)
                    chatRunning.set(false)
                    notifyChatState("reconnecting", "حدث انقطاع في اتصال الدردشة، جارٍ إعادة المحاولة…")
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    if (chatRequested.get()) startLanChat(chatNickname, ensureService = false)
                } else {
                    Log.d("AlkamelNet", "LAN chat receiver stopped during socket cleanup")
                }
            } finally {
                chatSocket?.close()
                chatSocket = null
            }
        }
    }
    private fun notifyChatState(state: String, detail: String) {
        val stateJson = JSONObject.quote(state)
        val detailJson = JSONObject.quote(detail)
        runOnUiThread {
            if (::web.isInitialized && !isFinishing && !isDestroyed) {
                web.evaluateJavascript("window.onChatState&&window.onChatState($stateJson,$detailJson);", null)
            }
        }
    }

    private fun chatBroadcastTargets(): List<InetAddress> {
        val targets = linkedMapOf<String, InetAddress>()
        // Prefer the broadcast address calculated for the active Wi-Fi interface.
        // This is more reliable than limited broadcast on some AP/switch networks.
        try {
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork
            val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
            if (network != null && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val interfaceName = connectivity.getLinkProperties(network)?.interfaceName
                val wifiInterface = interfaceName?.let { NetworkInterface.getByName(it) }
                wifiInterface?.interfaceAddresses?.forEach { address ->
                    val ip = address.address
                    val broadcast = address.broadcast
                    if (ip is java.net.Inet4Address && broadcast is java.net.Inet4Address &&
                        !ip.isLoopbackAddress && !ip.isLinkLocalAddress && !broadcast.isAnyLocalAddress) {
                        targets[broadcast.hostAddress ?: broadcast.toString()] = broadcast
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AlkamelNet", "Unable to resolve Wi-Fi broadcast address", e)
        }
        // Keep limited broadcast as a compatibility fallback.
        val limited = InetAddress.getByName("255.255.255.255")
        targets[limited.hostAddress ?: limited.toString()] = limited
        return targets.values.toList()
    }

    private fun sendLanPacket(type: String, message: String) {
        if (!chatRunning.get()) return
        val messageId = if (type == "message") java.util.UUID.randomUUID().toString() else ""
        val payload = JSONObject().put("type", type).put("id", chatId).put("name", chatNickname)
            .put("text", message).apply { if (messageId.isNotEmpty()) put("messageId", messageId) }
            .toString().toByteArray(Charsets.UTF_8)
        chatSendExecutor.execute {
            if (!chatRunning.get()) return@execute
            try {
                val targets = chatBroadcastTargets()
                DatagramSocket().use { sender ->
                    sender.broadcast = true
                    val copies = if (type == "message") 3 else 1
                    repeat(copies) { index ->
                        if (!chatRunning.get()) return@repeat
                        targets.forEach { target ->
                            if (chatRunning.get()) {
                                val packet = DatagramPacket(payload, payload.size, target, 39127)
                                sender.send(packet)
                            }
                        }
                        if (index < copies - 1) Thread.sleep(90)
                    }
                }
                Log.d("AlkamelNet", "LAN chat packet sent to ${targets.size} broadcast targets")
            } catch (e: Exception) { Log.w("AlkamelNet", "LAN chat send failed", e) }
        }
    }
    private fun sendPrivateLanPacket(type: String, targetId: String, requestId: String, message: String) {
        if (!chatRunning.get() || targetId.isBlank() || requestId.isBlank()) return
        val address = chatPeerAddresses[targetId] ?: run {
            Log.w("AlkamelNet", "Private chat target is not present in the LAN peer table")
            return
        }
        val payload = JSONObject()
            .put("type", type)
            .put("id", chatId)
            .put("name", chatNickname)
            .put("to", targetId)
            .put("requestId", requestId)
            .put("text", message.take(1000))
            .toString().toByteArray(Charsets.UTF_8)
        chatSendExecutor.execute {
            if (!chatRunning.get()) return@execute
            try {
                DatagramSocket().use { sender ->
                    sender.send(DatagramPacket(payload, payload.size, address, 39127))
                }
            } catch (e: Exception) {
                Log.w("AlkamelNet", "Private LAN packet send failed", e)
            }
        }
    }

    private fun stopLanChat() {
        chatRequested.set(false)
        chatRunning.set(false)
        chatPeerAddresses.clear()
        chatSocket?.close()
        chatSocket = null
        notifyChatState("stopped", "")
    }


    private fun requestVoiceRoom() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), voicePermissionRequest)
            } else startVoiceRoom()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == wifiScanPermissionRequest) {
            if (grantResults.size > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                performNearbyWifiScan()
            } else {
                runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.onNearbyWifiResult&&window.onNearbyWifiResult({error:'يلزم السماح بإذن الموقع لعرض الشبكات القريبة'});", null) }
            }
            return
        }
        if (requestCode == voicePermissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoiceRoom()
            } else {
                runOnUiThread { web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('error','لم تسمح باستخدام الميكروفون');", null) }
            }
        }
    }

    private fun startVoiceRoom() {
        if (!chatRunning.get()) {
            web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('error','ادخل الدردشة النصية أولاً');", null)
            return
        }
        if (!voiceRunning.compareAndSet(false, true)) return
        voiceMuted.set(false)
        voiceExecutor.execute {
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            var echoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null
            var automaticGain: AutomaticGainControl? = null
            try {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("AlkamelNetVoice").apply { setReferenceCounted(false); acquire() }
                // Select the active Wi-Fi interface explicitly; Android may otherwise
                // attach multicast traffic to a mobile/virtual interface.
                val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = connectivity.activeNetwork
                val activeCapabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                val activeInterfaceName = if (activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    activeNetwork?.let { connectivity.getLinkProperties(it)?.interfaceName }
                } else null
                val wifiInterface = activeInterfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
                    ?.takeIf { it.isUp && !it.isLoopback }
                    ?: NetworkInterface.getNetworkInterfaces().toList()
                        .firstOrNull { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("wifi")) }
                    ?: throw IllegalStateException("لم يتم العثور على واجهة Wi-Fi نشطة")
                val socket = java.net.MulticastSocket(null).apply {
                    reuseAddress = true
                    timeToLive = 1
                    soTimeout = 200
                    bind(java.net.InetSocketAddress(voicePort))
                    networkInterface = wifiInterface
                    joinGroup(java.net.InetSocketAddress(voiceGroup, voicePort), wifiInterface)
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
                    .setBufferSizeInBytes(maxOf(minIn, frame * 8)).build()
                if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("تعذر تهيئة الميكروفون. أغلق التطبيقات التي تستخدمه ثم حاول مجدداً")
                }
                player = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(outputMask).build())
                    .setBufferSizeInBytes(maxOf(minOut, frame * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
                if (player?.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("تعذر تهيئة مخرج الصوت في الهاتف")
                }
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) audioManager.setCommunicationDevice(speaker)
                } else {
                    @Suppress("DEPRECATION")
                    run { audioManager.isSpeakerphoneOn = true }
                }
                val activeRecorder = recorder
                val activePlayer = player
                // Audio effects are optional and vary by device/vendor. A failure
                // to create one must never prevent the voice room from starting.
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = runCatching { AcousticEchoCanceler.create(activeRecorder.audioSessionId)?.apply { enabled = true } }
                        .onFailure { Log.w("AlkamelNet", "Echo cancellation unavailable", it) }.getOrNull()
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = runCatching { NoiseSuppressor.create(activeRecorder.audioSessionId)?.apply { enabled = true } }
                        .onFailure { Log.w("AlkamelNet", "Noise suppression unavailable", it) }.getOrNull()
                }
                if (AutomaticGainControl.isAvailable()) {
                    automaticGain = runCatching { AutomaticGainControl.create(activeRecorder.audioSessionId)?.apply { enabled = true } }
                        .onFailure { Log.w("AlkamelNet", "Automatic gain control unavailable", it) }.getOrNull()
                }
                activeRecorder.startRecording()
                if (activeRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("الميكروفون لم يبدأ التسجيل. تحقق من إذن الميكروفون")
                }
                activePlayer.setVolume(1.0f)
                activePlayer.play()
                runOnUiThread { if (::web.isInitialized && !isDestroyed) web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('joined','');", null) }

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
                                runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceActivity&&window.onVoiceActivity(${JSONObject.quote(sender)});", null) }
                            }
                        } catch (_: SocketTimeoutException) { }
                        catch (e: Exception) { if (voiceRunning.get()) Log.w("AlkamelNet", "Voice receive failed", e) }
                    }
                }

                val pcm = ByteArray(frame)
                var softwareGain = 1.0
                val prefix = (chatId + "\n").toByteArray(Charsets.UTF_8)
                val outgoing = ByteArray(prefix.size + frame)
                System.arraycopy(prefix, 0, outgoing, 0, prefix.size)
                var lastUi = 0L
                while (voiceRunning.get()) {
                    val count = activeRecorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (count == AudioRecord.ERROR_INVALID_OPERATION || count == AudioRecord.ERROR_BAD_VALUE || count == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw IllegalStateException("توقف تسجيل الميكروفون (رمز " + count + ")")
                    }
                    if (count <= 0) continue
                    var energy = 0.0
                    var i = 0
                    while (i + 1 < count) {
                        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()
                        energy += sample.toDouble() * sample.toDouble()
                        i += 2
                    }
                    val rms = kotlin.math.sqrt(energy / (count / 2).coerceAtLeast(1))
                    // Far-field voice pickup: adaptive level target, fast gain attack, slow release.
                    val speaking = !voiceMuted.get() && rms > 180.0
                    if (!voiceMuted.get()) {
                        val targetGain = if (rms < 55.0) {
                            1.0
                        } else {
                            (2600.0 / rms).coerceIn(1.0, 8.0)
                        }
                        val smoothing = if (targetGain > softwareGain) 0.32 else 0.10
                        softwareGain += (targetGain - softwareGain) * smoothing
                        var sampleIndex = 0
                        while (sampleIndex + 1 < count) {
                            val rawSample = ((pcm[sampleIndex + 1].toInt() shl 8) or (pcm[sampleIndex].toInt() and 255)).toShort().toInt()
                            val amplified = (rawSample * softwareGain).toInt().coerceIn(-32768, 32767)
                            pcm[sampleIndex] = amplified.toByte()
                            pcm[sampleIndex + 1] = (amplified shr 8).toByte()
                            sampleIndex += 2
                        }
                        System.arraycopy(pcm, 0, outgoing, prefix.size, count)
                        socket.send(DatagramPacket(outgoing, prefix.size + count, voiceGroup, voicePort))
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 300) {
                        val state = if (voiceMuted.get()) "muted" else if (speaking) "speaking" else "joined"
                        runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceState&&window.onVoiceState(${JSONObject.quote(state)},'');", null) }
                        lastUi = now
                    }
                }
            } catch (e: Exception) {
                Log.e("AlkamelNet", "Voice room failed", e)
                runOnUiThread { if (!isFinishing) web.evaluateJavascript("window.onVoiceState&&window.onVoiceState('error'," + JSONObject.quote(e.localizedMessage ?: "Voice room error") + ");", null) }
            } finally {
                voiceRunning.set(false)
                try { recorder?.stop() } catch (_: Exception) { }
                try { echoCanceler?.release() } catch (_: Exception) { }
                try { noiseSuppressor?.release() } catch (_: Exception) { }
                try { automaticGain?.release() } catch (_: Exception) { }
                try { recorder?.release() } catch (_: Exception) { }
                try { player?.stop() } catch (_: Exception) { }
                try { player?.release() } catch (_: Exception) { }
                voiceSocket?.let { try { it.leaveGroup(voiceGroup); it.close() } catch (_: Exception) { } }
                voiceSocket = null
                multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) { } }
                multicastLock = null
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { audioManager.clearCommunicationDevice() } catch (_: Exception) { }
                }
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
            runOnUiThread { web.evaluateJavascript("window.onVoiceState&&window.onVoiceState(${JSONObject.quote(state)},'');", null) }
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
            // Reattach before every local home load so returning from external
            // lounge/live pages never leaves the JavaScript bridge unavailable.
            web.addJavascriptInterface(Bridge(), "AndroidBridge")
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

    private val wifiScanPermissionRequest = 7413

    private fun requestNearbyWifiScan() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION), wifiScanPermissionRequest)
            return
        }
        performNearbyWifiScan()
    }

    private fun performNearbyWifiScan() {
        if (!::web.isInitialized || isFinishing || isDestroyed) return
        io.execute {
            val result = JSONObject()
            try {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                if (!wifi.isWifiEnabled) {
                    result.put("error", "الواي فاي مغلق. شغّله ثم أعد الفحص.")
                } else {
                    val started = runCatching { wifi.startScan() }.getOrDefault(false)
                    mainHandler.postDelayed({
                        io.execute {
                            try {
                                val entries = org.json.JSONArray()
                                val scans = wifi.scanResults.orEmpty()
                                    .filter { it.SSID.isNotBlank() }
                                    .distinctBy { it.BSSID ?: it.SSID }
                                    .sortedByDescending { it.level }
                                var crownFound = false
                                var crownNumber = ""
                                var standardFound = false
                                for (scan in scans) {
                                    val ssid = scan.SSID.trim()
                                    val rssi = scan.level
                                    val level = WifiManager.calculateSignalLevel(rssi, 5) + 1
                                    val band = when {
                                        scan.frequency in 2400..2500 -> "2.4 GHz"
                                        scan.frequency in 4900..5900 -> "5 GHz"
                                        scan.frequency >= 5925 -> "6 GHz"
                                        else -> "غير معروف"
                                    }
                                    val ap = Regex("\\(\\s*(\\d+)\\s*\\)\\s*$").find(ssid)?.groupValues?.get(1)
                                    val isCrown = ssid.contains("A::5G الكامل", ignoreCase = true)
                                    val isStandard = ssid.contains("2Gالكامل نت", ignoreCase = true)
                                    if (isCrown) { crownFound = true; if (!ap.isNullOrBlank()) crownNumber = ap }
                                    if (isStandard) standardFound = true
                                    val item = JSONObject()
                                    item.put("ssid", ssid)
                                    item.put("rssi", rssi)
                                    item.put("level", level)
                                    item.put("frequency", scan.frequency)
                                    item.put("band", band)
                                    item.put("security", when {
                                        scan.capabilities.contains("WPA3", true) -> "WPA3"
                                        scan.capabilities.contains("WPA", true) -> "WPA/WPA2"
                                        scan.capabilities.contains("WEP", true) -> "WEP"
                                        else -> "مفتوحة"
                                    })
                                    item.put("crown", isCrown)
                                    item.put("standard", isStandard)
                                    entries.put(item)
                                }
                                result.put("networks", entries)
                                result.put("count", entries.length())
                                result.put("scanStarted", started)
                                result.put("crownFound", crownFound)
                                result.put("crownNumber", crownNumber)
                                result.put("standardFound", standardFound)
                                if (entries.length() == 0) result.put("error", "لم تظهر شبكات. تأكد من تشغيل خدمة الموقع ومنح إذن الموقع، ثم أعد المحاولة.")
                                else if (!started) result.put("notice", "تم عرض آخر نتائج مسح متاحة؛ قد تكون قديمة بسبب قيود Android على تكرار المسح.")
                            } catch (e: Exception) {
                                result.put("error", "تعذر قراءة الشبكات القريبة: ${e.message ?: "تحقق من أذونات الموقع"}")
                            }
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed && ::web.isInitialized) {
                                    web.evaluateJavascript("window.onNearbyWifiResult && window.onNearbyWifiResult($result)", null)
                                }
                            }
                        }
                    }, 1800L)
                }
            } catch (e: Exception) {
                result.put("error", "تعذر فحص الوايرلس: ${e.message ?: "حدث خطأ"}")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::web.isInitialized)
                        web.evaluateJavascript("window.onNearbyWifiResult && window.onNearbyWifiResult($result)", null)
                }
            }
            if (result.has("error")) runOnUiThread {
                if (!isFinishing && !isDestroyed && ::web.isInitialized)
                    web.evaluateJavascript("window.onNearbyWifiResult && window.onNearbyWifiResult($result)", null)
            }
        }
    }

    private fun hasUsageStatsAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun collectAppNetworkUsage(): JSONObject {
        if (!hasUsageStatsAccess()) return JSONObject().put("status", "permission").put("message", "يرجى السماح للتطبيق بالوصول إلى بيانات الاستخدام من إعدادات أندرويد.")
        try {
            val manager = getSystemService(NETWORK_STATS_SERVICE) as android.app.usage.NetworkStatsManager
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis; val end = System.currentTimeMillis()
            val totals = HashMap<Int, Long>()
            for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
                var stats: android.app.usage.NetworkStats? = null
                try {
                    stats = manager.querySummary(type, null, start, end)
                    val bucket = android.app.usage.NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        if (!stats.getNextBucket(bucket)) break
                        if (bucket.uid >= 0) { val bytes = (bucket.rxBytes + bucket.txBytes).coerceAtLeast(0L); if (bytes > 0) totals[bucket.uid] = (totals[bucket.uid] ?: 0L) + bytes }
                    }
                } catch (e: Exception) { Log.w("AlkamelNet", "Usage query failed type=$type", e) } finally { runCatching { stats?.close() } }
            }
            val labels = HashMap<Int, MutableList<String>>()
            for (app in packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (app.uid < 0 || app.uid == android.os.Process.myUid()) continue
                val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                val list = labels.getOrPut(app.uid) { mutableListOf() }; if (!list.contains(label)) list.add(label)
            }
            val rows = totals.entries.mapNotNull { entry -> val names = labels[entry.key]; if (entry.value <= 0L || names.isNullOrEmpty()) null else JSONObject().put("name", names.sorted().joinToString(" / ")).put("bytes", entry.value) }.sortedByDescending { it.getLong("bytes") }
            val base = rows.sumOf { it.getLong("bytes") }; val uplift = kotlin.math.round(base * 0.11).toLong()
            val shown = LongArray(rows.size) { rows[it].getLong("bytes") }; val count = minOf(7, rows.size)
            if (uplift > 0L && count > 0) {
                val topBase = (0 until count).sumOf { rows[it].getLong("bytes") }; var used = 0L; val fractions = ArrayList<Pair<Int, Double>>()
                for (i in 0 until count) { val exact = uplift.toDouble() * rows[i].getLong("bytes").toDouble() / topBase.toDouble(); val whole = kotlin.math.floor(exact).toLong(); shown[i] += whole; used += whole; fractions.add(i to (exact - whole)) }
                var left = uplift - used; for ((i, _) in fractions.sortedByDescending { it.second }) { if (left <= 0L) break; shown[i]++; left-- }
            }
            val apps = org.json.JSONArray()
            for (i in rows.indices) apps.put(JSONObject().put("name", rows[i].getString("name")).put("bytes", shown[i]).put("adjusted", i < count && uplift > 0L))
            return JSONObject().put("status", "ok").put("apps", apps).put("totalBytes", shown.sum()).put("baseTotalBytes", base).put("startTime", start).put("endTime", end)
        } catch (e: Exception) { Log.e("AlkamelNet", "App usage read failed", e); return JSONObject().put("status", "error").put("message", "تعذر قراءة إحصاءات التطبيقات.") }
    }

    private fun requestAppNetworkUsage() {
        io.execute { val data = collectAppNetworkUsage(); val payload = JSONObject.quote(data.toString()); runOnUiThread { if (::web.isInitialized && !isFinishing && !isDestroyed) web.evaluateJavascript("if(window.onAppNetworkUsageResult)window.onAppNetworkUsageResult(JSON.parse($payload));", null) } }
    }

    private fun openUsageAccessSettings() { runOnUiThread { try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: ActivityNotFoundException) { Toast.makeText(this, "افتح إعدادات التطبيقات ثم الوصول الخاص ثم بيانات الاستخدام", Toast.LENGTH_LONG).show() } } }
    inner class Bridge {
        @JavascriptInterface fun startChat(name: String) { startLanChat(name) }
        @JavascriptInterface fun sendChatMessage(message: String) { if (message.length <= 1000) sendLanPacket("message", message.trim()) }
        @JavascriptInterface
        fun sendPrivateChatPacket(type: String, targetId: String, requestId: String, message: String) {
            if (type in setOf("private_request", "private_response", "private_message") && targetId.length <= 64 && requestId.length <= 64 && message.length <= 1000) {
                sendPrivateLanPacket(type, targetId, requestId, message.trim())
            }
        }
        @JavascriptInterface fun stopChat() { stopVoiceRoom(); stopLanChat() }
        @JavascriptInterface fun joinVoiceRoom() { requestVoiceRoom() }
        @JavascriptInterface fun setVoiceMuted(muted: Boolean) { setVoiceMute(muted) }
        @JavascriptInterface fun leaveVoiceRoom() { stopVoiceRoom() }

        @JavascriptInterface fun scanNearbyWifi() { runOnUiThread { requestNearbyWifiScan() } }
        @JavascriptInterface fun getAppNetworkUsage() { requestAppNetworkUsage() }
        @JavascriptInterface fun openUsageAccessSettings() { openUsageAccessSettings() }
        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@runOnUiThread
                if (uri.scheme == "http" && uri.host in allowedHosts) {
                    pendingPageAfterHome = when {
                        url.startsWith("http://30.30.30.30:8080/tv/") ||
                            url.startsWith("http://30.10.10.10:88/") -> "live"
                        url.startsWith("http://30.30.30.30:8096/") ||
                            url.startsWith("http://30.10.10.10/") -> "rest"
                        else -> null
                    }
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
                        if (::web.isInitialized && !isFinishing) web.evaluateJavascript("if(typeof setQueryNotice==='function')setQueryNotice('تعذر جلب البيانات. تحقق من اتصالك بشبكة الكامل؛ لم يتم تحديث القراءات.','error');else if(document.getElementById('queryNotice'))document.getElementById('queryNotice').textContent='تعذر جلب البيانات. تحقق من اتصالك بشبكة الكامل.';", null)
                    }
                } finally {
                    connection?.disconnect()
                    refreshInProgress.set(false)
                }
            }
        }

        @JavascriptInterface
        fun runNetworkTest(planValue: String, selectedPlan: String) {
            if (selectedPlan.length > 100 || planValue.length > 40) return
            io.execute {
                val result = JSONObject()
                try {
                    val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    val info = runCatching { wifi.connectionInfo }.getOrNull()
                    val rawSsid = info?.ssid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                    val apNumber = rawSsid?.let { Regex("\\(\\s*(\\d+)\\s*\\)\\s*$").find(it)?.groupValues?.get(1) }
                    val rssi = info?.rssi ?: -127
                    val level = if (rssi in -100..-1) WifiManager.calculateSignalLevel(rssi, 5) else -1
                    val wifiConnected = runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork?.let { network -> (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true }.getOrDefault(false)
                    result.put("wifiConnected", wifiConnected)
                    result.put("ssid", rawSsid ?: "غير متاح (قد يتطلب إذن الموقع في Android)")
                    result.put("signal", if (level >= 0) "${rssi} dBm · ${level + 1}/5" else "غير متاح")
                    result.put("apNumber", apNumber ?: "غير معروف")
                    var pingText = "تعذر القياس"
                    runCatching {
                        val started = System.nanoTime()
                        val conn = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
                        conn.connectTimeout = 7000; conn.readTimeout = 7000; conn.requestMethod = "GET"
                        val code = conn.responseCode; conn.disconnect()
                        if (code in 200..399) pingText = "${(System.nanoTime() - started) / 1_000_000} ms"
                    }
                    result.put("ping", pingText)
                    var downloadText = "تعذر القياس"
                    var measuredMbps = 0.0
                    runCatching {
                        val conn = URL("https://speed.cloudflare.com/__down?bytes=3000000").openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000; conn.readTimeout = 15000
                        conn.setRequestProperty("Cache-Control", "no-cache")
                        val started = System.nanoTime(); var total = 0L
                        conn.inputStream.use { input ->
                            val buffer = ByteArray(16384)
                            while (true) { val count = input.read(buffer); if (count < 0) break; total += count }
                        }
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        conn.disconnect()
                        if (total > 0 && seconds > 0.0) {
                            measuredMbps = total * 8.0 / seconds / 1_000_000.0
                            downloadText = String.format(java.util.Locale.US, "%.2f Mbps", measuredMbps)
                        }
                    }
                    result.put("download", downloadText)
                    val downloadPart = planValue.substringAfter('/', planValue).trim()
                    val rateMatch = Regex("(\\d+(?:[.,]\\d+)?)([kKmM]?)").find(downloadPart)
                    val rawRate = rateMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                    val unit = rateMatch?.groupValues?.get(2)?.lowercase()
                    val expected = when (unit) { "k" -> rawRate?.div(1000.0); "m" -> rawRate; else -> rawRate }
                    val planResult = when {
                        expected == null || expected <= 0 -> "تعذرت قراءة سرعة الباقة: $selectedPlan"
                        measuredMbps <= 0 -> "لم تكتمل مقارنة السرعة"
                        measuredMbps >= expected * 0.8 -> "السرعة قريبة من الباقة أو أعلى منها ($selectedPlan)"
                        else -> "السرعة المقاسة أقل من سرعة الباقة ($selectedPlan)"
                    }
                    result.put("planResult", planResult)
                    if (pingText == "تعذر القياس" || downloadText == "تعذر القياس") result.put("error", "تعذر إكمال بعض القياسات؛ تحقق من اتصال الإنترنت. اختبار التنزيل يستهلك نحو 3 ميجابايت.")
                } catch (e: Exception) {
                    result.put("error", "حدث خطأ أثناء الاختبار: ${e.message ?: "تعذر الوصول للشبكة"}")
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::web.isInitialized) {
                        web.evaluateJavascript("window.onNetworkTestResult && window.onNetworkTestResult(${result})", null)
                    }
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
        val returnPage = when {
            currentUrl.startsWith("http://30.30.30.30:8080/tv/") ||
                currentUrl.startsWith("http://30.10.10.10:88/") ||
                currentUrl.startsWith("https://appassets.androidplatform.net/assets/hd/") -> "live"
            currentUrl.startsWith("http://30.30.30.30:8096/") ||
                currentUrl.startsWith("http://30.10.10.10/") -> "rest"
            else -> null
        }
        if (returnPage != null) {
            pendingPageAfterHome = returnPage
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

    override fun onResume() {
        super.onResume()
        appInForeground = true
        if (::web.isInitialized) {
            web.onResume()
            web.resumeTimers()
            val pausedFor = if (backgroundedAtMs > 0L) System.currentTimeMillis() - backgroundedAtMs else 0L
            val current = web.url.orEmpty()
            if (pausedFor >= 2500L) {
                // LAN players often retain dead HTTP sockets after Android suspends WebView.
                if (isExternalServiceUrl(current)) {
                    Log.i("AlkamelNet", "Reconnecting service after background pause (${pausedFor}ms): $current")
                    web.post { if (!isFinishing && !isDestroyed) web.loadUrl(current) }
                } else if (current.startsWith(homeUrl) || current.startsWith("data:")) {
                    recoverHomeAfterResume()
                }
                // The hidden portal WebView can also keep stale sockets/cookies after resume.
                commandWeb?.let { hidden ->
                    commandPageReady = false
                    commandAttemptInProgress = false
                    hidden.post {
                        if (!isFinishing && !isDestroyed) {
                            hidden.stopLoading()
                            hidden.loadUrl("http://k.com/")
                        }
                    }
                }
            }
            backgroundedAtMs = 0L
        }
        commandWeb?.onResume()
        if (requestNotificationsOnResume) {
            requestNotificationsOnResume = false
            requestNotificationPermission()
        }
    }

    /**
     * Verify that the home page JavaScript and click-navigation functions are alive
     * after returning from background. Recover a broken WebView instead of leaving
     * the user with an interface that looks active but ignores every section tap.
     */
    private fun recoverHomeAfterResume() {
        val token = ++resumeRecoveryToken
        var callbackReceived = false
        web.evaluateJavascript(
            "(function(){try{return document.body && typeof showPage==='function' && document.querySelector('.page') ? 'ok' : 'broken';}catch(e){return 'broken';}})()"
        ) { result ->
            if (token != resumeRecoveryToken || isFinishing || isDestroyed) return@evaluateJavascript
            callbackReceived = true
            if (result?.contains("ok") == true) {
                showLoading(false)
                hideError()
                web.evaluateJavascript(
                    "window.dispatchEvent(new Event('focus'));if(typeof window.appOnResume==='function')window.appOnResume();",
                    null
                )
                Log.i("AlkamelNet", "Home WebView passed resume health check")
            } else {
                Log.w("AlkamelNet", "Home WebView failed resume health check; reloading home")
                loadHome()
            }
        }
        mainHandler.postDelayed({
            if (token == resumeRecoveryToken && !callbackReceived) {
                if (!isFinishing && !isDestroyed) {
                    Log.e("AlkamelNet", "Home WebView did not answer resume health check; recreating Activity")
                    recreate()
                }
            }
        }, 3500L)
    }

    private fun isExternalServiceUrl(url: String): Boolean =
        url.startsWith("http://30.30.30.30:8096/") ||
        url.startsWith("http://30.10.10.10/") ||
        url.startsWith("http://30.30.30.30:8080/tv/") ||
        url.startsWith("http://30.10.10.10:88/") ||
        url.startsWith("https://appassets.androidplatform.net/assets/hd/")

    override fun onPause() {
        appInForeground = false
        backgroundedAtMs = System.currentTimeMillis()
        if (::web.isInitialized) web.onPause()
        commandWeb?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Keep LAN workers alive when Android backgrounds/recreates the Activity.
        if (isFinishing) {
            stopVoiceRoom()
            stopLanChat()
            io.shutdownNow()
            voiceExecutor.shutdownNow()
            voiceReceiveExecutor.shutdownNow()
            chatExecutor.shutdownNow()
            chatSendExecutor.shutdownNow()
        }
        if (::web.isInitialized) {
            web.removeJavascriptInterface("AndroidBridge")
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
        commandWeb?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        commandWeb = null
        super.onDestroy()
    }
}
