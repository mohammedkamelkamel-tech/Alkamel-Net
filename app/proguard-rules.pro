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
# Keep Eclipse Paho MQTT runtime classes used through reflection by its logger factory.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
