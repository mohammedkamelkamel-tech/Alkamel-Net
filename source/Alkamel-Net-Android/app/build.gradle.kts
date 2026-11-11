plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.alkamel.network"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mohammedkamel.alkamelnet.portal2026"
        minSdk = 24
        targetSdk = 35
        versionCode = 69
        versionName = "1.8.5"
    }
    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    // MQTT transport for group chat, private messages, and member presence.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
