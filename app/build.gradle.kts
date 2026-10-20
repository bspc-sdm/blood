plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// সার্ভারের ঠিকানা বিল্ডের সময় বদলানো যায় (CI-এর "Build APK (next)" নতুন সার্ভারের APK বানায়):
//   gradle assembleDebug -PlifoloServer=https://lifolo.com -PlifoloVersionSuffix=-next
// না দিলে আগের মতোই Vercel — সাধারণ রিলিজ (Build APK) কিছুই দেয় না, তাই রিলিজ বদলায় না।
// একই ঠিকানায় /api/… (অ্যাপের কাজ), /r/ /rx/ /shop/ (শেয়ার আর QR লিংক), /admin /work/… (ওয়েবের পাতা) — তাই একটাই ঠিকানা।
val lifoloServer: String = (findProperty("lifoloServer") as String?)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    ?: "https://blood-server-ashy.vercel.app"
require(Regex("^https://[a-z0-9.-]+(:[0-9]+)?$").matches(lifoloServer)) { "lifoloServer must be https://host: $lifoloServer" }
val lifoloLinkHost: String = lifoloServer.removePrefix("https://").substringBefore(':')
val lifoloVersionSuffix: String = (findProperty("lifoloVersionSuffix") as String?)?.trim().orEmpty()

android {
    namespace = "com.bspc.blood"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bspc.blood"
        minSdk = 24
        targetSdk = 35
        versionCode = 119
        versionName = "2.33.1"
        versionNameSuffix = lifoloVersionSuffix.ifEmpty { null }
        buildConfigField("String", "SERVER_URL", "\"$lifoloServer\"")
        // শেয়ার করা রিকোয়েস্টের লিংক (https://<host>/r/{id}) যে নামে অ্যাপে খোলে (AndroidManifest.xml)
        manifestPlaceholders["linkHost"] = lifoloLinkHost
    }

    signingConfigs {
        // স্থায়ী ডিবাগ key: প্রতিটা বিল্ডে একই SHA-1 থাকবে, তাই Google লগইন কাজ করবে।
        // Play Store-এর জন্য আলাদা গোপন release key লাগবে (GitHub Secrets-এ)।
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // কিছু ফোনের (Realme/Oppo/Xiaomi) ইনস্টলার পুরোনো v1 সাইন না পেলে
            // "package appears to be invalid" দেখায়, তাই সব ধরনের সাইন একসাথে
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
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
        buildConfig = true
    }

    // স্ক্রিনের ছবি তোলা টেস্ট (Robolectric, ফোন ছাড়াই)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
                // একটা টেস্ট ক্র্যাশ করলে বাকিগুলো যেন চলে
                it.setForkEvery(1)
                it.testLogging { events("started", "passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // লোকেশন, দূরত্ব হিসাব
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // দোকানের QR স্ক্যান (Google-এর তৈরি স্ক্যানার — ক্যামেরার অনুমতি লাগে না)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.firebase:geofire-android-common:3.2.0")
    // প্রেসক্রিপশনের যাচাইয়ের QR
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
