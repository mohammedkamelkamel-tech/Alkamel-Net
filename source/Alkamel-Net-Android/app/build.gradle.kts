plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.alkamel.network"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "com.mohammedkamel.alkamelnet.portal2026"
        minSdk = 24
        targetSdk = 35
        versionCode = 83
        versionName = "1.9.9"
        val mqttUser = System.getenv("MQTT_USERNAME").orEmpty()
        val mqttPass = System.getenv("MQTT_PASSWORD").orEmpty()
        buildConfigField("String", "MQTT_USERNAME_B64", "\"${java.util.Base64.getEncoder().encodeToString(mqttUser.toByteArray())}\"")
        buildConfigField("String", "MQTT_PASSWORD_B64", "\"${java.util.Base64.getEncoder().encodeToString(mqttPass.toByteArray())}\"")
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
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
