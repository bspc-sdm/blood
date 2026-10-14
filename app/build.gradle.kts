plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.bspc.blood"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bspc.blood"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        // স্থায়ী ডিবাগ key: প্রতিটা বিল্ডে একই SHA-1 থাকবে, তাই Google লগইন কাজ করবে।
        // Play Store-এর জন্য আলাদা গোপন release key লাগবে (GitHub Secrets-এ)।
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Google লগইন
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // লোকেশন, দূরত্ব হিসাব, পেছনে নোটিফিকেশন চেক
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.firebase:geofire-android-common:3.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
