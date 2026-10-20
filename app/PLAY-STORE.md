# Lifolo — Google Play Store প্রস্তুতি

অবস্থা: ২০ অক্টোবর ২০২৬, অ্যাপ ২.৩৪.১ (১২১)। ভার্সন এখানে বাড়ানো হয়নি — রিলিজ ক্যান্ডিডেটের সময় বাড়বে।
এই ফাইলে কোনো পাসওয়ার্ড বা key নেই। সার্টিফিকেটের fingerprint গোপন নয় (যেকোনো APK থেকে পড়া যায়)।

---

## ১. মালিকের করণীয় (এই ক্রমে)

1. **GitHub Secrets যোগ করুন** (রিপো → Settings → Secrets and variables → Actions → New repository secret)। নাম চারটে:
   `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`।
   মানগুলো আলাদা গোপন ফাইলে (`RELEASE-SECRETS.txt`) দেওয়া হয়েছে, সাথে keystore ফাইল `lifolo-release.p12` — রিপোতে নেই, কখনো রাখবেন না।
   keystore ফাইল আর পাসওয়ার্ড password manager-এ আর একটা অফলাইন জায়গায় (পেনড্রাইভ) রাখুন।
   চারটের মধ্যে কয়েকটা দিলে বিল্ড থেমে যাবে (ইচ্ছে করে — যাতে ভুল key-এ চুপচাপ বিল্ড না হয়)।
2. **backend টিম:** `/.well-known/assetlinks.json`-এ নতুন key-এর SHA-256 যোগ (নিচে §২)। না হলে শেয়ার করা `/r/…` লিংক অ্যাপে না খুলে ব্রাউজারে খুলবে।
3. **ওয়েব টিম:** অ্যাকাউন্ট মোছার পাবলিক পাতা `https://lifolo.com/delete-account` (নিচে §৮) আর প্রাইভেসি পলিসির হালনাগাদ (§৬-এর "খোলা বিষয়")।
4. **ভার্সন বাড়িয়ে** (`app/build.gradle.kts`-এর versionCode/versionName) main-এ push → "Build APK" release key দিয়ে APK + AAB বানাবে।
   নিজের ফোনে **একবার আনইনস্টল করে নতুনটা ইনস্টল** (§৩)।
5. **Play Console:** অ্যাকাউন্ট (§৭) → অ্যাপ তৈরি → App signing (§২) → AAB Internal testing-এ → App content-এর ফর্মগুলো (§৯–§১৩) → স্টোর লিস্টিং (§১৪)।

---

## ২. সাইনের key (release key = Play-র upload key)

| | |
|---|---|
| ফাইল | `lifolo-release.p12` (PKCS12, RSA 4096) |
| alias | `lifolo` |
| মেয়াদ | ২০ অক্টোবর ২০২৬ থেকে ১২ অক্টোবর ২০৫৬ (Play চায় ২০৩৩-এর পরে পর্যন্ত) |
| SHA-256 | `07:6E:13:D0:F9:0A:22:81:86:AE:53:15:54:55:FD:C6:A0:48:72:9B:75:8E:DA:AF:52:32:A3:F9:2C:19:69:43` |
| SHA-1 | `28:97:38:00:F5:D3:2E:53:23:1D:1E:C0:73:58:A7:06:D9:35:D4:60` |
| পুরোনো debug key-এর SHA-256 | `FC:BE:F9:19:39:40:5B:C2:32:72:18:DB:DA:CD:DD:BE:86:47:F6:4B:F9:0A:06:F7:37:AC:42:99:D2:F0:F8:39` |

**CI ("Build APK", `.github/workflows/build.yml`):**
- চারটে সিক্রেট থাকলে: `assembleRelease` → lifolo.com-এর APK (release key-এ সাইন, debuggable নয়) আর `bundleRelease -PlifoloStore=play` → Play-র AAB।
  দুটোরই সাইন মেলানো হয় (apksigner, jarsigner), targetSdk 36 আর debuggable=no যাচাই হয়।
  AAB যায় GitHub-এর "latest" রিলিজে `lifolo-<ভার্সন>-<কোড>.aab` নামে, আর Actions-এর artifact-এ।
- সিক্রেট না থাকলে: আগের মতোই debug APK (debug key), AAB হয় না — কিছু ভাঙে না।
- main ছাড়া অন্য branch-এ শুধু বিল্ড আর যাচাই, কোথাও প্রকাশ হয় না (লগ: `ci-log-<branch>`)।
- লোকালি: `gradle bundleRelease -PlifoloStore=play -PreleaseStoreFile=… -PreleaseStorePassword=… -PreleaseKeyAlias=lifolo -PreleaseKeyPassword=…`

**Play App Signing — সিদ্ধান্ত: Google-কে এই key-টাই দিন ("Use your own key" → PEPK দিয়ে export)।**
Play Console → Test and release → App integrity → App signing → "Change app signing key" / প্রথমবার "Use a different key" →
"Export and upload a key from Java keystore" → Console-এর দেওয়া `pepk.jar` আর encryption key দিয়ে:
`java -jar pepk.jar --keystore=lifolo-release.p12 --alias=lifolo --output=lifolo-pepk.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem`
→ zip upload। একই key তখন upload key হিসেবেও চলে (AAB এটা দিয়েই সাইন হয়)।

- **কেন:** তাহলে Play থেকে ইনস্টল করা অ্যাপ আর lifolo.com-এর APK-র সাইন একই — মানুষ দুই জায়গার মধ্যে আনইনস্টল ছাড়াই আসা-যাওয়া করতে পারবেন,
  assetlinks-এ একটাই fingerprint, অ্যাপের "একবার আনইনস্টল" বার্তাও আর লাগবে না।
- **বাদ দেওয়া বিকল্প — Google-এর বানানো key:** তখন Play-র অ্যাপের সাইন Google-এর key, lifolo.com-এর APK-র সাইন আমাদের key —
  একই package নামে দুই সাইন, তাই Play থেকে lifolo.com-এ (বা উল্টো) যেতে প্রতিবার আনইনস্টল আর আবার লগইন লাগবে, assetlinks-এ দুটো fingerprint রাখতে হবে।
  এর একমাত্র সুবিধা (key Google-এর কাছে, আমাদের হারানোর ভয় কম) এই আকারের অ্যাপে ওই ঝামেলার চেয়ে কম।
- key হারালে: Play-তে upload key reset চাওয়া যায় (Google-এর কাছে app signing key থাকে), কিন্তু lifolo.com-এর APK আর একই সাইনে বানানো যাবে না। তাই ব্যাকআপ জরুরি।

**Firebase:** লগইন ইমেইল-কোড → custom token, তাই Firebase Console-এ SHA-1/SHA-256 যোগ করা লাগে না (Google Sign-In নেই)।
পরে কখনো Google Sign-In যোগ হলে নতুন key-এর SHA-1 আর SHA-256 Firebase-এ দিতে হবে।

**assetlinks (backend টিমের কাজ):** `backend/src/main/kotlin/com/lifolo/blood/ShareController.kt`-এর `CERT_SHA256` তালিকায় নতুন SHA-256
(`07:6E:13:…:69:43`, ওপরে পুরোটা) **যোগ** করুন, পুরোনো debug-এরটা আপাতত রাখুন (মালিকের ফোন বদলানো পর্যন্ত)।
Google-এর বানানো key নিলে (সুপারিশ নয়) Play Console-এর "App signing key certificate"-এর SHA-256-ও যোগ করতে হতো।

---

## ৩. debug key থেকে release key — একবারের আনইনস্টল

Android একই অ্যাপের নতুন ভার্সন পুরোনোটার উপরে তখনই বসায়, যখন দুটো একই key-এ সাইন করা। এখন পর্যন্ত সব APK রিপোর ভেতরের debug key-এ সাইন হতো।
release key-এর প্রথম APK তাই পুরোনো অ্যাপের উপরে বসবে না — ফোন "App not installed" / "package conflicts with an existing package" দেখাবে।
এটা **একবারই** হয়; এরপর থেকে সব আপডেট আগের মতো উপরেই বসবে। এখন একমাত্র ব্যবহারকারী মালিক নিজে, তাই কাজটা ছোট:

1. নতুন ভার্সন বেরোলে (সিক্রেট যোগের পরে প্রথম "Build APK") https://lifolo.com/download/lifolo.apk ডাউনলোড করুন।
2. ফোনের Settings → Apps → Lifolo → **Uninstall**।
3. ডাউনলোড করা ফাইল ইনস্টল করুন → ইমেইল দিয়ে কোড নিয়ে **আবার লগইন**।

যা থাকবে: অ্যাকাউন্ট, প্রোফাইল, রিকোয়েস্ট, প্রেসক্রিপশন, রিপোর্ট — সব সার্ভারে, লগইনের পর আগের মতো।
যা ফোন থেকে যাবে: ভাষা/থিমের পছন্দ, সাম্প্রতিক এলাকা, **ওষুধের অ্যালার্ম** (প্রেসক্রিপশন থেকে আবার চালু করুন)। নোটিফিকেশনের টোকেন লগইনের পর নিজে থেকে আবার নিবন্ধিত হয়।

**অ্যাপের ভেতরে:** এই বদল থেকে অ্যাপ নিজের সাইন release key-এর (`BuildConfig.RELEASE_CERT_SHA256`) সাথে মেলায়। না মিললে (debug key-এর অ্যাপ)
"নতুন ভার্সন এসেছে" কার্ডে বাড়তি লেখা — "একবারের কাজ: ফোন "App not installed" দেখালে আগে এই অ্যাপটা আনইনস্টল করুন …" — আর
**"পুরোনোটা সরান"** বাটন (অ্যাপের তথ্যের পাতা খোলে, সেখানে Uninstall)। release key-এর অ্যাপে এই লেখা কখনো আসে না।
মনে রাখবেন: এখন ফোনে থাকা ২.৩৪.১-এ এই লেখা নেই (ওটা আগের কোড), তাই মালিকের প্রথম বদলটা ওপরের ধাপে হাতে করতে হবে।

**"Build APK (next)" (`lifolo-next.apk`) এখনো debug key-এ সাইন হয়** — release key-এর অ্যাপের উপরে ওটা বসবে না।
নতুন সার্ভারের পরীক্ষা শেষ হলে ওটা বন্ধ করুন, অথবা ওই workflow-এও release key দিন (সিদ্ধান্ত মালিকের; এখানে বদলানো হয়নি)।

---

## ৪. Play-র বিল্ড বনাম lifolo.com-এর APK

| | lifolo.com APK | Play AAB |
|---|---|---|
| Gradle | `assembleRelease` (`lifoloStore=web`, ডিফল্ট) | `bundleRelease -PlifoloStore=play` |
| "নতুন ভার্সন এসেছে" কার্ড | আছে (lifolo.com/download/version.json) | **নেই** — Play-র নীতিতে অ্যাপ নিজে নিজেকে আপডেট করতে পারে না; আপডেট Play দেয় |
| সাইন | release key | release key (upload) → Play App Signing (§২-এর সিদ্ধান্তে একই key) |
| versionCode | একই ক্রম | একই ক্রম — Play-তে প্রতি upload-এ versionCode আগের চেয়ে বড় হতে হয় |

---

## ৫. targetSdk আর Android 16

- Play-র নিয়ম: **৩১ আগস্ট ২০২৬ থেকে নতুন অ্যাপ আর আপডেট targetSdk 36 (Android 16) বা বেশি**; পুরোনো অ্যাপ বাড়তি সময় চাইতে পারে ১ নভেম্বর ২০২৬ পর্যন্ত — নতুন অ্যাপের জন্য সে সুযোগ নেই।
  সূত্র: https://developer.android.com/google/play/requirements/target-sdk
- ছিল compileSdk/targetSdk 35 → **এখন 36**; Android Gradle Plugin 8.7.3 → **8.10.1** (API 36 সমর্থন; Gradle 8.11.1 আগের মতোই)। CI-তে বিল্ড আর যাচাই সবুজ।
- Android 16-এ যা দেখে নেবেন (ফোনে প্রায় কিছু বদলায় না):
  - edge-to-edge বাধ্যতামূলক — অ্যাপ আগে থেকেই `enableEdgeToEdge` + `safeDrawingPadding` করে, তাই কিছু বদলায় না।
  - predictive back — অ্যাপ `BackHandler` ব্যবহার করে (সঠিক পদ্ধতি); পেছনে যাওয়ার সময় সিস্টেমের নতুন অ্যানিমেশন দেখা যেতে পারে।
  - বড় স্ক্রিনে (ট্যাবলেট/ফোল্ডেবল, ৬০০dp+) orientation লক মানা হয় না — অ্যাপে লক নেই, তাই শুধু ট্যাবলেটে একবার দেখে নিন।

---

## ৬. কোড থেকে কমপ্লায়েন্স যাচাই

### অনুমতি (মিলিত manifest, CI-র `aapt2 dump permissions` থেকে)

| অনুমতি | কে যোগ করে | কেন / মন্তব্য |
|---|---|---|
| `INTERNET` | অ্যাপ | সার্ভারের সাথে সব কাজ |
| `ACCESS_COARSE_LOCATION` | অ্যাপ | আশেপাশের রিকোয়েস্ট, ডোনার, ফার্মেসি, অ্যাম্বুলেন্স খোঁজা (≈১–৩ কিমি যথেষ্ট); শুধু অ্যাপ খোলা অবস্থায় |
| `ACCESS_FINE_LOCATION` | অ্যাপ | শুধু অ্যাম্বুলেন্সের "আমার এখনকার জায়গা" / "গাড়ি যেখানে অপেক্ষা করে" বাটনে; সার্ভার ~১ কিমির ঘরে কেটে রাখে |
| `POST_NOTIFICATIONS` | অ্যাপ | রক্তের রিকোয়েস্ট, অ্যাপয়েন্টমেন্ট, ওষুধের অ্যালার্ম; Android 13+-এ লগইনের পর চাওয়া হয় |
| `RECEIVE_BOOT_COMPLETED` | অ্যাপ | ফোন চালু হলে ওষুধের অ্যালার্ম আবার সেট |
| `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `com.google.android.c2dm.permission.RECEIVE` | Firebase Messaging | পুশ নোটিফিকেশন (FCM) |
| `com.google.android.providers.gsf.permission.READ_GSERVICES` | Google Play services লাইব্রেরি | সাধারণ; কোনো ঘোষণা লাগে না |
| `com.bspc.blood.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX core | অ্যাপের নিজের ভেতরের নিরাপত্তা অনুমতি |

**নেই (তাই Play-র আলাদা ঘোষণা লাগে না):** background location, `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`
(অনুমতি না থাকলে অ্যালার্ম inexact — Android 14+-এ কয়েক মিনিট দেরি হতে পারে; এটাই রাখা ভালো, কারণ `USE_EXACT_ALARM` শুধু অ্যালার্ম/ক্যালেন্ডার অ্যাপের জন্য),
foreground service (তাই foreground service type ঘোষণা লাগে না), `READ_MEDIA_*`/স্টোরেজ (ছবি বাছাই Android-এর photo picker দিয়ে),
`CAMERA` (QR স্ক্যান Google-এর code scanner দিয়ে), `REQUEST_INSTALL_PACKAGES` (আপডেট শুধু ব্রাউজারে লিংক খোলে), `QUERY_ALL_PACKAGES`, SMS/কল লগ, contacts।
`AD_ID` কোনো লাইব্রেরি যেন যোগ না করে, সেজন্য manifest-এ `tools:node="remove"`।

### Exported অংশ

| অংশ | exported | মন্তব্য |
|---|---|---|
| `MainActivity` | হ্যাঁ | লঞ্চার; `https://lifolo.com/r/…` (autoVerify), পুরোনো Vercel লিংক, `getblood://r/…` |
| `ShareActivity` | হ্যাঁ | Google Maps থেকে "শেয়ার" (text/plain) — লেখা শুধু পড়ে, ২০০০ অক্ষরে কাটে, MainActivity খোলে |
| `BloodMessagingService`, `MedAlarmReceiver`, `MedAlarmBootReceiver`, `FileProvider` | না | |
| Firebase `FirebaseInstanceIdReceiver` | হ্যাঁ | `c2dm.permission.SEND` দিয়ে সুরক্ষিত (শুধু Google Play services পাঠাতে পারে) |
| Play services `RevocationBoundService` | হ্যাঁ | নিজস্ব permission দিয়ে সুরক্ষিত |
| AndroidX `ProfileInstallReceiver` | হ্যাঁ | `android.permission.DUMP` দিয়ে সুরক্ষিত |
| Firebase Auth `GenericIdpActivity`, `RecaptchaActivity` | ~~হ্যাঁ~~ → **সরানো হয়েছে** | OAuth/ফোন-লগইনের পাতা; Lifolo-তে লাগে না |
| `androidx.activity.ComponentActivity` | হ্যাঁ | শুধু debug বিল্ডে (টেস্টের লাইব্রেরি) — release/AAB-এ নেই |

### অবস্থা — ঠিক করা / ঠিক আছে / খোলা

| বিষয় | অবস্থা |
|---|---|
| targetSdk/compileSdk 36 | **ঠিক করা হয়েছে** (§৫) |
| debuggable APK | CI এতদিন **debug বিল্ড** (debuggable=true) ছড়াত; Play debuggable বা debug-key-এ সাইন করা ফাইল নেয় না → release key থাকলে release বিল্ড (**ঠিক করা হয়েছে**, সিক্রেট যোগের পর চালু) |
| অ্যাপের নিজের আপডেট (Play নীতি) | Play বিল্ডে কার্ড বন্ধ (**ঠিক করা হয়েছে**) |
| ব্যাকআপ (`allowBackup`) | আগে নিয়ম ছিল না — Firebase লগইনের টোকেন, FCM, সার্ভারের উত্তরের ক্যাশ (প্রোফাইল, রিকোয়েস্ট), ক্র্যাশের ফাইল সবই Google Drive ব্যাকআপে আর নতুন ফোনে যেত। এখন `data_extraction_rules.xml` (Android 12+) আর `backup_rules.xml` (১১ বা আগে) — শুধু ভাষা/থিম, সাম্প্রতিক এলাকা, ফিল্টার, অ্যাম্বুলেন্সের শেষ ঠিকানা আর ওষুধের অ্যালার্ম যায় (**ঠিক করা হয়েছে**)। ফল: নতুন ফোনে আবার লগইন করতে হয় |
| cleartext (http) | সব https; `usesCleartextTraffic="false"` স্পষ্ট করে দেওয়া (**ঠিক করা হয়েছে**, আচরণ একই) |
| exported অংশ | অপ্রয়োজনীয় দুটো সরানো (**ঠিক করা হয়েছে**) |
| নোটিফিকেশনের অনুমতি (Android 13+) | লগইনের পর চাওয়া হয়; ওষুধের অ্যালার্ম অনুমতি না থাকলে চুপ (ঠিক আছে) |
| লোকেশন | শুধু অ্যাপ খোলা অবস্থায়, প্রসঙ্গে চাওয়া (ঠিক আছে) |
| ছবি | photo picker (ঠিক আছে — Photo & video permissions ঘোষণা লাগে না) |
| Sentry | শুধু ক্র্যাশ/ANR; ইমেইল, ফোন, টোকেন মুছে পাঠায়; screenshot/view hierarchy/session নয় (ঠিক আছে) |
| **Firebase Crashlytics** | **খোলা (মালিকের সিদ্ধান্ত):** Sentry-র পাশাপাশি Crashlytics-ও চালু (`firebase-crashlytics`, কোনো ছাঁকনি ছাড়া; ক্র্যাশের তথ্য + Crashlytics installation ID যায়)। সুপারিশ: Crashlytics সরানো — Sentry একই কাজ করে আর ব্যক্তিগত তথ্য ছাঁকে; তাতে Data safety-তে একটা প্রক্রিয়াকারী কম। ব্যবহারকারী কোনো পার্থক্য দেখবেন না, কিন্তু এটা আপনার ক্র্যাশ দেখার জায়গা বদলায়, তাই এখানে করা হয়নি |
| **প্রাইভেসি পলিসি (`PRIVACY.md`)** | **খোলা (ওয়েব/মালিক):** "তথ্য কোথায় থাকে" এখনো বলে Firebase (মুম্বাই) আর Vercel — এখন সার্ভার নিজের VPS (lifolo.com), ছবি Cloudflare R2। প্রক্রিয়াকারীদের নাম নেই: Sentry, Firebase (লগইন, FCM, Crashlytics), Cloudflare, SMS গেটওয়ে, ইমেইল পাঠানোর সেবা। "কোনো তৃতীয় পক্ষের সাথে শেয়ার করা হয় না" ঠিক আছে, কিন্তু "এই সেবাদাতারা আমাদের হয়ে তথ্য প্রক্রিয়া করে" লাইনটা লাগবে। অ্যাকাউন্ট মোছার ওয়েব লিংকও (§৮) পলিসিতে দিন। Play রিভিউ পলিসি আর Data safety মেলায় |
| **App Links** | **খোলা (backend):** assetlinks-এ নতুন SHA-256 (§২) |
| minify (R8) বন্ধ | চলবে; APK বড়। পরে চালু করলে mapping ফাইল Play-তে দিতে হবে — এখন নয় |
| "Build APK (next)" debug key | **খোলা (মালিক):** §৩ |

---

## ৭. Play Console অ্যাকাউন্ট — সিদ্ধান্ত: **Organization অ্যাকাউন্ট**

- **কেন:** Google-এর নির্দেশনা — medical/health অ্যাপ organization অ্যাকাউন্ট থেকে প্রকাশ করা উচিত
  (https://support.google.com/googleplay/android-developer/answer/13634885)। Lifolo-তে প্রেসক্রিপশন, রিপোর্ট, অ্যাপয়েন্টমেন্ট আছে।
  organization অ্যাকাউন্টে নতুন personal অ্যাকাউন্টের "১২ জন টেস্টার নিয়ে ১৪ দিন closed testing" শর্তও নেই।
- **বাদ দেওয়া বিকল্প — personal অ্যাকাউন্ট:** দ্রুত খোলা যায়, কিন্তু health অ্যাপ হিসেবে রিভিউতে আটকানোর ঝুঁকি, আর production-এর আগে ১৪ দিনের closed test বাধ্যতামূলক।
- লাগবে: প্রতিষ্ঠানের D-U-N-S নম্বর (বিনামূল্যে, Dun & Bradstreet থেকে; কয়েক দিন–কয়েক সপ্তাহ), প্রতিষ্ঠানের ওয়েবসাইট (lifolo.com), যোগাযোগের ইমেইল/ফোন যাচাই।

---

## ৮. অ্যাকাউন্ট মোছার ওয়েব লিংক (Play-র বাধ্যতামূলক)

Play চায়: অ্যাপের ভেতরে মোছার পথ **আর** একটা ওয়েব লিংক, যেখানে অ্যাপ ইনস্টল ছাড়াই মোছার অনুরোধ করা যায়।

- অ্যাপে: Profile → "অ্যাকাউন্ট মুছুন" (`delete_account`) — **আছে**।
- ওয়েবে (lifolo.com, Vue): লগইনের পর প্রোফাইলে "অ্যাকাউন্ট মুছুন" — **আছে**, কিন্তু লগইনের পেছনে, আলাদা কোনো ঠিকানা নেই।
  প্রাইভেসি পলিসিতে ইমেইলে (ekapkbd@gmail.com) মোছার অনুরোধের কথা আছে।
  web-vue-এর router-এ `/delete-account` বা এরকম কোনো পাবলিক পাতা **নেই** (এই sandbox থেকে lifolo.com সরাসরি খোলা যায়নি; কোড দেখে বলা)।
- **সিদ্ধান্ত: ওয়েব টিম একটা পাবলিক পাতা বানাবে — `https://lifolo.com/delete-account`** — আর Play Console-এ সেটাই দেবেন।
  (বাদ: `https://lifolo.com/privacy` দেওয়া — Play-র রিভিউয়ার আলাদা, সহজে খুঁজে পাওয়া পাতা চায়, পলিসির লম্বা লেখার ভেতরে ধাপ দেখলে প্রায়ই প্রত্যাখ্যান করে।)

**পাতার স্পেক (ওয়েব টিমের জন্য; এখানে বানানো হয়নি):**
1. লগইন ছাড়াই খোলে; শিরোনাম "Lifolo অ্যাকাউন্ট মুছুন / Delete your Lifolo account"; অ্যাপ আর ডেভেলপারের নাম (Play Console-এ যে নাম) স্পষ্ট।
2. ধাপ: (ক) অ্যাপে Profile → অ্যাকাউন্ট মুছুন; (খ) এই সাইটে "লগইন করে মুছুন" বাটন → ইমেইল-কোডে লগইন → সোজা প্রোফাইলের মোছার নিশ্চিতকরণে
   (`/login` → `/profile`, মোছার ডায়ালগ খোলা); (গ) অ্যাকাউন্টে ঢুকতে না পারলে ekapkbd@gmail.com-এ অ্যাকাউন্টের ইমেইল থেকে মেইল — ৩০ দিনের মধ্যে মোছা।
3. কী মোছে, কী থাকে, কতদিন: `PRIVACY.md`-এর "অ্যাকাউন্ট মোছা" অংশের সারাংশ — ৩০ দিন অপেক্ষা (এর মধ্যে লগইন করলে বাতিল), তারপর প্রোফাইল, ছবি, ফোন ইত্যাদি মোছে;
   ডাক্তারের লেখা প্রেসক্রিপশন ডাক্তারের কাছে থাকে; নিষেধাজ্ঞার জন্য একমুখী hash থাকে।
4. বাংলা + ইংরেজি; ছোট, মোবাইলে পড়া যায়।
5. `PRIVACY.md`-এর "আপনার অধিকার"-এ এই লিংক।

---

## ৯. Data safety ফর্ম / Data safety form

**সাধারণ প্রশ্ন / General**

| প্রশ্ন | উত্তর |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** — সব যোগাযোগ HTTPS (cleartext বন্ধ) |
| Do you provide a way for users to request that their data is deleted? | **Yes** — অ্যাপে আর `https://lifolo.com/delete-account` |
| Account creation | "My app allows users to create an account" → **Username and other authentication** (ইমেইলে কোড; পাসওয়ার্ড নেই) |
| Delete account URL | `https://lifolo.com/delete-account` (§৮) |
| Independent security review | No |

**"Shared" = No সব জায়গায় — কেন:** Play-র সংজ্ঞায় (১) আমাদের হয়ে কাজ করা সেবাদাতা (Sentry, Firebase, Cloudflare, SMS/ইমেইল গেটওয়ে) "sharing" নয়,
(২) ব্যবহারকারী নিজে বেছে যাকে পাঠান (ডাক্তার, ফার্মেসি, প্যাথলজি, কেয়ারগিভার, সাড়া দেওয়া রক্তদাতা/রক্তপ্রার্থী) — user-initiated transfer, সেটাও "sharing" নয়।
তথ্য বিক্রি হয় না, বিজ্ঞাপন নেই।
"Ephemeral" কোনোটাই নয় (সব সার্ভারে রাখা হয়)।

**Data types** (Collected = Yes; Shared = No; Optional = ব্যবহারকারী চাইলে দেন, Required = অ্যাকাউন্টের জন্য লাগে)

| Play-র ধরন | Lifolo-তে কী | Required? | Purposes |
|---|---|---|---|
| Personal info → **Name** | প্রোফাইলের নাম; পরিবারের সদস্য/রোগীর নাম | Required | App functionality, Account management |
| Personal info → **Email address** | লগইনের ইমেইল; কেয়ারগিভার/সহকারীর ইমেইল | Required | App functionality, Account management |
| Personal info → **User IDs** | অ্যাকাউন্টের uid | Required | App functionality, Account management |
| Personal info → **Address** | বাসা থেকে নমুনা, ফার্মেসির ডেলিভারি, ব্যবসার ঠিকানা | Optional | App functionality |
| Personal info → **Phone number** | মোবাইল নম্বর (যোগাযোগ, যাচাই) | Optional | App functionality, Fraud prevention, security, and compliance |
| Personal info → **Other info** | জন্ম তারিখ, লিঙ্গ (ঐচ্ছিক), পেশার রেজিস্ট্রেশন/লাইসেন্স নম্বর | Optional | App functionality, Fraud prevention, security, and compliance |
| Financial info → **Other financial info** | রিপোর্ট দেখানোর ফি-র TrxID, ডাক্তারের bKash/Nagad/Rocket নম্বর (Lifolo টাকা ধরে না) | Optional | App functionality |
| Health and fitness → **Health info** | রক্তের গ্রুপ, শেষ রক্তদান, ওজন, প্রেসক্রিপশন, ওষুধ, পরীক্ষা ও রিপোর্ট, অ্যাপয়েন্টমেন্টে সমস্যার বিবরণ, ওষুধের অ্যালার্ম | Optional | App functionality |
| Location → **Approximate location** | এলাকা (~১ কিমি ঘরে) আর জেলা/উপজেলা | Optional | App functionality |
| Location → **Precise location** | অ্যাম্বুলেন্সের "আমার এখনকার জায়গা", ফার্মেসির দোকানের পিন (সার্ভারে ব্যবহারকারীর জায়গা ~১ কিমিতে কাটা) | Optional | App functionality |
| Photos and videos → **Photos** | প্রোফাইলের ছবি, রিপোর্টের ছবি, লাইসেন্স/সনদ/পরিচয়পত্রের ছবি (যাচাই) | Optional | App functionality, Fraud prevention, security, and compliance |
| App activity → **App interactions** | অ্যাপ থেকে অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংকে ফোন বাটন চাপা (কে, কোনটায়, কখন) | Optional | App functionality, Analytics |
| App activity → **In-app search history** | "ওষুধ খুঁজুন" (ওষুধের নাম + এলাকা) | Optional | App functionality |
| App activity → **Other user-generated content** | Health Discussion-এর প্রশ্ন/উত্তর, টিপস, রেটিং ও মন্তব্য, রক্তের রিকোয়েস্ট | Optional | App functionality |
| App info and performance → **Crash logs** | Sentry (ছাঁকা) আর Firebase Crashlytics | Required (বন্ধ করার উপায় নেই) | Analytics |
| App info and performance → **Diagnostics** | ANR, ফোনের মডেল/Android ভার্সন, অ্যাপের ভার্সন | Required | Analytics |
| Device or other IDs → **Device or other IDs** | FCM টোকেন (নোটিফিকেশন), Firebase installation ID (Crashlytics) | Required | App functionality, Analytics |

**Collected নয়:** Web browsing, Contacts, Calendar, Messages (SMS/ইমেইল পড়া হয় না), Audio, Files and docs (কাগজ শুধু ছবি হিসেবে), Fitness info, Purchase history, Credit/debit card, Race/ethnicity, Political/religious beliefs, Sexual orientation, Installed apps, Advertising ID।
Crashlytics সরালে (§৬) "Crash logs" থাকে (Sentry), শুধু Device IDs-এর "Firebase installation ID" অংশ বাদ যায়।

---

## ১০. Health apps declaration (Policy → App content → Health apps)

টিক দিন (Medical অংশ):

| বিভাগ | Lifolo-তে |
|---|---|
| **Healthcare Services and Management** | ডাক্তারের অ্যাপয়েন্টমেন্ট ও সিরিয়াল, ডিজিটাল প্রেসক্রিপশন, পরীক্ষার রিপোর্ট, বাসা থেকে নমুনা, কেয়ারগিভার |
| **Medication and Treatment Management** | ওষুধ খাওয়ার অ্যালার্ম, প্রেসক্রিপশন, ফার্মেসিতে প্রেসক্রিপশন পাঠানো, ওষুধ খোঁজা |
| **Emergency and First Aid** | জরুরি রক্তের আহ্বান, অ্যাম্বুলেন্স ডাকা, ব্লাড ব্যাংক |
| **Medical Reference and Education** | Health Tips, Health Discussion (সাধারণ পরামর্শ, রোগনির্ণয় নয়) |

টিক দেবেন না: **Medical Device Apps** (Lifolo চিকিৎসা-যন্ত্র হিসেবে নিবন্ধিত নয়; ডোজ হিসাব বা রোগনির্ণয় করে না), Clinical Decision Support
(ডাক্তার প্রেসক্রিপশন লেখেন, অ্যাপ সিদ্ধান্ত দেয় না), Activity/Fitness, Nutrition, Period, Sleep, Stress, Mental health, Disease prevention, Physical therapy, Reproductive, Research।
Health Connect ব্যবহার হয় না।

---

## ১১. Content rating (IARC questionnaire)

- Category: **All Other App Types** (খেলা নয়, সোশ্যাল/নিউজ নয়)।
- Violence, Fear, Sexuality, Language, Gambling, Crude humour — **No**।
- Controlled substances: অ্যাপে ওষুধের নাম, প্রেসক্রিপশন আর ফার্মেসি আছে (চিকিৎসার প্রসঙ্গে) — মাদক/অ্যালকোহল/তামাকের ব্যবহার বা উৎসাহ **No**।
  প্রশ্নে "prescription drugs / pharmacy" আলাদা করে জানতে চাইলে সত্যি উত্তর দিন: ফার্মেসিতে প্রেসক্রিপশন পাঠানো আর ওষুধ খোঁজা যায় (অ্যাপে কেনাবেচা/পেমেন্ট নেই)।
- Interactive elements:
  - Users can interact / communicate: **Yes** (Health Discussion, রক্তের রিকোয়েস্টে সাড়া, রেটিং-মন্তব্য)
  - Shares user's location with other users: **Yes** (রিকোয়েস্টের এলাকা, অ্যাম্বুলেন্স ডাকার জায়গা চালককে)
  - Shares personal information with other users: **Yes** (সাড়া দিলে ফোন নম্বর রক্তপ্রার্থী/ডোনার দেখেন)
  - Digital purchases: **No**; Unrestricted internet access: **No**
- প্রত্যাশিত ফল: সব বয়সের রেটিং-ই আসার কথা, সাথে "Users Interact, Shares Location, Shares Info" নোটিস — চূড়ান্ত রেটিং IARC দেয়।

---

## ১২. Target audience (লক্ষ্য বয়স) — সিদ্ধান্ত: **শুধু ১৮+ (18 and over)**

- **কেন:** রক্ত দেওয়ার যোগ্যতা ১৮–৬০; অ্যাপে প্রেসক্রিপশন, রিপোর্ট, ফোন নম্বর আর লোকেশন — প্রাপ্তবয়স্কদের সেবা।
  "Appeals to children": **No**। শিশুদের জন্য কোনো লেখা/ছবি নেই।
- **বাদ দেওয়া — ১৩–১৭ যোগ করা:** তাহলে Families/কিশোরদের নীতির বাড়তি শর্ত (বয়স যাচাই, তথ্য কম নেওয়া, বিজ্ঞাপন SDK-এর নিয়ম) মানতে হবে, অথচ ১৮-এর নিচে রক্ত দেওয়াই যায় না;
  পরিবারের ছোটদের সেবা অভিভাবক নিজের অ্যাকাউন্ট থেকে "পরিবারের সদস্য" দিয়ে নেন।

---

## ১৩. App content-এর বাকি উত্তর

| অংশ | উত্তর |
|---|---|
| Privacy policy | `https://lifolo.com/privacy` (জমা দেওয়ার আগে §৬-এর হালনাগাদ) |
| Ads | **No ads** |
| Advertising ID | **No** (AD_ID অনুমতি সরানো) |
| App access | **All or some functionality is restricted** → নির্দেশনা: লগইন পাতায় নিচে "ডেমো" অংশ থেকে যেকোনো ভূমিকা (রোগী, ডাক্তার, ফার্মেসি …) চাপলে ইমেইল/কোড ছাড়াই ঢোকা যায়। শর্ত: রিভিউ চলাকালীন সার্ভারে ডেমো চালু থাকতে হবে (Deploy backend → demo = keep)। *(বাদ: রিভিউয়ারের জন্য স্থায়ী কোডওয়ালা আলাদা ইমেইল — সার্ভারে নতুন লগইন-ফাঁক তৈরি করে আর ব্যাকএন্ডের কাজ লাগে।)* |
| Government apps | No |
| Financial features | My app doesn't provide any financial features (TrxID শুধু রেকর্ড; টাকা সরাসরি ডাক্তারের নম্বরে) |
| News apps | No |
| Health apps | §১০ |
| Data safety | §৯ |
| Content rating | §১১ |
| Target audience | §১২ |

---

## ১৪. স্টোর লিস্টিং (বাংলা — ডিফল্ট ভাষা bn-BD)

**App name (৩০ অক্ষরের মধ্যে; এটা ২৭):** `Lifolo: রক্ত, অ্যাম্বুলেন্স` — Play অক্ষর গোনে Unicode হিসেবে (কার, ফলা আলাদা অক্ষর), তাই "…, ওষুধ" যোগ করলে ৩৩ হয়ে যায়

**Category:** **Medical** — মূল কাজগুলো (রক্ত, অ্যাম্বুলেন্স, প্রেসক্রিপশন, ফার্মেসি, রিপোর্ট) চিকিৎসাসেবা।
*(বাদ: Health & Fitness — ওটা ব্যায়াম/সুস্থতার অ্যাপের জায়গা; সেখানে Lifolo খুঁজে পাওয়া কঠিন হবে।)*

**Contact:** ekapkbd@gmail.com · Website: https://lifolo.com

**Short description (৮০ অক্ষরের মধ্যে; এটা ৭৪):**
`রক্তদাতা খুঁজুন, অ্যাম্বুলেন্স ডাকুন, ডাক্তার-ফার্মেসি-রিপোর্ট — এক অ্যাপে`

**Full description:**

```
Lifolo — রক্ত, অ্যাম্বুলেন্স আর চিকিৎসাসেবা এক জায়গায়।

রক্ত লাগবে?
• রক্তের রিকোয়েস্ট দিন — কাছের মানানসই রক্তদাতারা সঙ্গে সঙ্গে নোটিফিকেশন পান
• জরুরি আহ্বান: কতজন আসছেন দেখুন, প্রয়োজন মিটলে সবাইকে জানিয়ে দিন
• রক্ত দিতে চাইলে নিজের গ্রুপ আর এলাকা দিন; শেষ রক্তদানের তারিখ ধরে শুধু যোগ্য সময়েই রিকোয়েস্ট আসে
• কাছের ব্লাড ব্যাংকের ফোন নম্বর আর খোলার সময়

অ্যাম্বুলেন্স
• এলাকা আর গাড়ির ধরন অনুযায়ী অ্যাম্বুলেন্স খুঁজুন, ভাড়ার তালিকা দেখুন, সরাসরি ফোন করুন
• Google Maps থেকে জায়গা শেয়ার করে অ্যাম্বুলেন্স ডাকুন

ডাক্তার ও প্রেসক্রিপশন
• চেম্বারের সময় আর ফি দেখে অ্যাপয়েন্টমেন্ট নিন; সিরিয়াল কত নম্বর চলছে সরাসরি দেখুন
• ডাক্তারের ডিজিটাল প্রেসক্রিপশন নিজের অ্যাকাউন্টে, QR দিয়ে যাচাই করা যায়
• ওষুধ খাওয়ার সময় হলে অ্যালার্ম

ফার্মেসি ও পরীক্ষা
• প্রেসক্রিপশন কাছের ফার্মেসিতে পাঠান — দোকানে গিয়ে নিন বা বাসায় ডেলিভারি
• কোন ফার্মেসিতে ওষুধ আছে খুঁজুন
• প্যাথলজিতে পরীক্ষা পাঠান, বাসা থেকে নমুনা, রিপোর্ট অ্যাপেই — আগের-পরের ফল মিলিয়ে দেখুন

পরিবার
• পরিবারের সদস্যের নামে সিরিয়াল নিন
• কেয়ারগিভার যোগ করুন — আপনার হয়ে তিনি সেবা নিতে পারবেন

স্বাস্থ্য আলোচনা
• প্রশ্ন করুন, যাচাই করা ডাক্তার-নার্স-পুষ্টিবিদদের উত্তর আর টিপস পড়ুন

আপনার তথ্য
• লগইন শুধু ইমেইলের কোড দিয়ে — কোনো পাসওয়ার্ড নেই
• আপনার এলাকা প্রায় ১ কিমি পর্যন্ত গোল করে রাখা হয়, আসল GPS নয়
• কোনো বিজ্ঞাপন নেই, তথ্য বিক্রি হয় না
• যেকোনো সময় অ্যাপ বা ওয়েব থেকে অ্যাকাউন্ট মুছতে পারবেন

গুরুত্বপূর্ণ: Lifolo সম্ভাব্য রক্তদাতা আর সেবাদাতা খুঁজে দেয়; চিকিৎসার সিদ্ধান্ত চিকিৎসক নেবেন।
রক্ত দেওয়া যাবে কি না, ব্লাড ব্যাংক বা চিকিৎসক ক্রসম্যাচ করে ঠিক করবেন।
জরুরি অবস্থায় ৯৯৯-এ ফোন করুন।

প্রাইভেসি পলিসি: https://lifolo.com/privacy
```

(দাবি এড়ানো হয়েছে: "সেরা", "১ নম্বর", রোগ সারানো — Play-র metadata নীতি। ইংরেজি অনুবাদ চাইলে en-US-এ এর অনুবাদ দিন।)

**গ্রাফিক্স:**
- App icon: 512×512 PNG (৩২-বিট, স্বচ্ছ নয়), ≤১ MB — `ic_launcher`-এর বড় সংস্করণ।
- Feature graphic: 1024×500 JPG/PNG (বাধ্যতামূলক) — লোগো + "রক্ত · অ্যাম্বুলেন্স · ডাক্তার · ফার্মেসি"।
- Phone screenshots: কমপক্ষে ২টা, সুপারিশ ৮টা; ৯:১৬, অন্তত 1080×1920।

**স্ক্রিনশটের তালিকা** (ডেমো অ্যাকাউন্ট থেকে, কোনো আসল মানুষের নাম/ফোন নয়; বাংলায়, লাইট থিম):

1. হোম — কাছের রক্তের রিকোয়েস্ট আর সেবার টাইল
2. রক্তের রিকোয়েস্ট দেওয়া (গ্রুপ, ব্যাগ, হাসপাতাল, সময়)
3. জরুরি আহ্বান — কতজন "আসছি" বলেছেন
4. অ্যাম্বুলেন্স খোঁজা — তালিকা, ভাড়া, ফোন বাটন
5. ডাক্তারের চেম্বার আর অ্যাপয়েন্টমেন্ট / সিরিয়াল সরাসরি
6. ডিজিটাল প্রেসক্রিপশন (QR সহ) আর ওষুধের অ্যালার্ম
7. ফার্মেসিতে প্রেসক্রিপশন পাঠানো / ওষুধ খোঁজা
8. পরীক্ষার রিপোর্ট — আগের-পরের ফল

"Screens" workflow (Robolectric) থেকে কয়েকটার খসড়া মেলে; চূড়ান্তগুলো আসল ফোনে ডেমো ডেটা দিয়ে তোলাই ভালো (স্ট্যাটাস বার পরিষ্কার)।

---

## ১৫. রিলিজের ধাপ (প্রতিবার)

1. `app/build.gradle.kts`-এ versionCode +১, versionName বদলান → main-এ push।
2. "Build APK" → lifolo.com/download (APK) + "latest" রিলিজে `lifolo-<ভার্সন>-<কোড>.aab` + Actions artifact।
   Summary-তে দেখুন: সাইন = release, সার্টিফিকেট `076E13D0…`, targetSdk 36, debuggable no।
3. Play Console → Internal testing → Create release → AAB upload → release notes (বাংলা) → Review → Rollout।
   ঠিক থাকলে Closed/Production-এ promote।

## সূত্র

- Target API: https://developer.android.com/google/play/requirements/target-sdk
- Health apps declaration: https://support.google.com/googleplay/android-developer/answer/14738291
- অ্যাকাউন্টের ধরন: https://support.google.com/googleplay/android-developer/answer/13634885
- AGP 8.10 (API 36): https://developer.android.com/build/releases/agp-8-10-0-release-notes
