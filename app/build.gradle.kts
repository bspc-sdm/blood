plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// সার্ভারের ঠিকানা বিল্ডের সময় বদলানো যায় (CI-এর "Build APK (next)" নতুন সার্ভারের APK বানায়):
//   gradle assembleDebug -PlifoloServer=https://lifolo.com -PlifoloVersionSuffix=-next
// না দিলে https://lifolo.com (২.৩৪.০ থেকে নিজের সার্ভার; আগে ছিল https://blood-server-ashy.vercel.app)।
// একই ঠিকানায় /api/… (অ্যাপের কাজ), /r/ /rx/ /shop/ (শেয়ার আর QR লিংক), /admin /work/… (ওয়েবের পাতা) — তাই একটাই ঠিকানা।
val lifoloServer: String = (findProperty("lifoloServer") as String?)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    ?: "https://lifolo.com"
require(Regex("^https://[a-z0-9.-]+(:[0-9]+)?$").matches(lifoloServer)) { "lifoloServer must be https://host: $lifoloServer" }
val lifoloLinkHost: String = lifoloServer.removePrefix("https://").substringBefore(':')
val lifoloVersionSuffix: String = (findProperty("lifoloVersionSuffix") as String?)?.trim().orEmpty()
// Sentry (প্রকল্প lifolo-android): CI-র SENTRY_DSN_ANDROID সিক্রেট (বা -PsentryDsn=…); খালি/ভুল = Sentry বন্ধ
val sentryDsn: String = ((findProperty("sentryDsn") as String?) ?: System.getenv("SENTRY_DSN_ANDROID") ?: "").trim()
    .takeIf { Regex("^https://[A-Za-z0-9]+@[a-z0-9.-]+\\.sentry\\.io/[0-9]+$").matches(it) }.orEmpty()
// কোথায় বিতরণ: web (lifolo.com/download-এর APK, অ্যাপের ভেতরে "নতুন ভার্সন" কার্ড) | play (Play Store-এর AAB —
// Play-র নীতি: অ্যাপ নিজে নিজেকে আপডেট করতে পারবে না, তাই কার্ড বন্ধ; আপডেট Play নিজে দেয়)। -PlifoloStore=play
val lifoloStore: String = ((findProperty("lifoloStore") as String?)?.trim()?.ifEmpty { null } ?: "web")
require(lifoloStore == "web" || lifoloStore == "play") { "lifoloStore must be web or play: $lifoloStore" }
// Release/upload key (Play App Signing-এ এটাই upload key): CI-তে GitHub Secrets থেকে (RELEASE_KEYSTORE_B64 → ফাইল),
// লোকালি -PreleaseStoreFile=… ইত্যাদি। না থাকলে release বিল্ড unsigned থাকে (CI তখন আগের মতো debug APK বানায়)।
fun releaseProp(prop: String, env: String): String? =
    ((findProperty(prop) as String?) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val releaseStoreFile: String? = releaseProp("releaseStoreFile", "RELEASE_KEYSTORE_FILE")
// release key-এর সার্টিফিকেটের SHA-256 (গোপন নয় — যেকোনো APK থেকে পড়া যায়)। অ্যাপ নিজের সাইন এর সাথে মেলায়:
// না মিললে (debug key-এর পুরোনো অ্যাপ) আপডেটের কার্ডে "একবার আনইনস্টল করে ইনস্টল" বলে (Updates.kt)।
val releaseCertSha256 = "076E13D0F90A228186AE53155455FDC6A048729B758EDAAF5232A3F92C196943"

android {
    namespace = "com.bspc.blood"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bspc.blood"
        minSdk = 24
        targetSdk = 36
        versionCode = 123
        versionName = "2.35.1"
        versionNameSuffix = lifoloVersionSuffix.ifEmpty { null }
        buildConfigField("String", "SERVER_URL", "\"$lifoloServer\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        buildConfigField("String", "STORE", "\"$lifoloStore\"")
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertSha256\"")
        // শেয়ার করা রিকোয়েস্টের লিংক (https://<host>/r/{id}) যে নামে অ্যাপে খোলে (AndroidManifest.xml)
        manifestPlaceholders["linkHost"] = lifoloLinkHost
        // অ্যাপে কলের (LiveKit/WebRTC) নেটিভ লাইব্রেরি: রিলিজে শুধু ফোনের ARM দুটো (APK ছোট); debug-এ এমুলেটরের x86_64-ও (নিচে)
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // স্থায়ী ডিবাগ key (রিপোতে, গোপন নয়): release key-এর সিক্রেট না থাকলে CI এটা দিয়েই APK সাইন করে।
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
        // release/upload key (app/PLAY-STORE.md) — ফাইল থাকলে তবেই
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseProp("releaseStorePassword", "RELEASE_KEYSTORE_PASSWORD")
                keyAlias = releaseProp("releaseKeyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = releaseProp("releaseKeyPassword", "RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8: অব্যবহৃত কোড আর রিসোর্স বাদ (APK ছোট) — নাম বদলানো (obfuscation) বন্ধ, proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else null
        }
        debug {
            // এমুলেটর (CI-র Maestro যাত্রা) x86_64
            ndk { abiFilters += listOf("x86_64") }
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

    // Firebase — শুধু লগইন (Auth) আর পুশ (Messaging); ক্র্যাশ রিপোর্ট Sentry-তে (Crashlytics ২০ অক্টোবর ২০২৬-এ সরানো)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    // Sentry — শুধু ভুল আর ANR (NDK অংশ নয়)
    implementation("io.sentry:sentry-android-core:8.60.0")
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // লোকেশন, দূরত্ব হিসাব
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // দোকানের QR স্ক্যান (Google-এর তৈরি স্ক্যানার — ক্যামেরার অনুমতি লাগে না)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.firebase:geofire-android-common:3.2.0")
    // প্রেসক্রিপশনের যাচাইয়ের QR
    implementation("com.google.zxing:core:3.5.3")

    // অ্যাপে অডিও/ভিডিও কল (নিজের LiveKit সার্ভার; ঠিকানা সার্ভার দেয়) — সংস্করণ বেঁধে রাখা
    implementation("io.livekit:livekit-android:2.28.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
