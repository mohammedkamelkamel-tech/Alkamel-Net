# Preserve runtime annotations used by the Android WebView JavaScript bridge.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# JavaScript calls these annotated methods by their exact names.
# Keep the method names, but allow R8 to optimize their implementation.
-keepclassmembers,allowoptimization class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Android instantiates this Activity and Service from the manifest.
# Keep component class names and constructors only; allow internal methods to be obfuscated.
-keep,allowoptimization class com.alkamel.network.MainActivity {
    public <init>();
}
-keep,allowoptimization class com.alkamel.network.ChatKeepAliveService {
    public <init>();
}

# The bridge is instantiated directly by MainActivity; its annotated entry points
# are retained by the rule above, while unrelated implementation details stay obfuscatable.
# Do not add a blanket keep rule for MainActivity or Bridge members.

# Retain useful crash line metadata for diagnosing field issues.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Paho discovers its logging implementation reflectively; keep it from R8 removal/obfuscation.
-keep class org.eclipse.paho.client.mqttv3.internal.logging.** { *; }
-keep class org.eclipse.paho.client.mqttv3.internal.websocket.** { *; }
-keep class org.eclipse.paho.client.mqttv3.MqttAsyncClient { *; }
-keep class org.eclipse.paho.client.mqttv3.MqttConnectOptions { *; }
-keep class com.alkamel.network.MqttChatService { *; }

# Paho MQTT selects its logger by class name at runtime.
-keep class org.eclipse.paho.client.mqttv3.logging.** { *; }
-keep class org.eclipse.paho.client.mqttv3.internal.logging.** { *; }
