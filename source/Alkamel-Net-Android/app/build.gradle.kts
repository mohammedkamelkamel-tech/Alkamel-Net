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
        versionCode = 5
        versionName = "1.2.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
