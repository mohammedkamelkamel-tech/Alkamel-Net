# Keep JavaScript bridge entry points exposed to the bundled WebView assets.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers,allowoptimization class * {
    @android.webkit.JavascriptInterface <methods>;
}
# WebView discovers this bridge and its annotated methods by reflection.
-keep class com.alkamel.network.MainActivity$Bridge { *; }
-keep class com.alkamel.network.MainActivity { *; }
# Preserve Android components instantiated by the framework.
-keep class com.alkamel.network.ChatKeepAliveService { *; }
# Keep source/line metadata out of production stack traces while retaining mapping files in build output.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
