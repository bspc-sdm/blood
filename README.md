# Lifolo

বাংলাদেশের জন্য একটা স্বাস্থ্য অ্যাপ: রক্তদাতা খোঁজা ও জরুরি রক্তের আহ্বান, অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, ডাক্তারের চেম্বারের সিরিয়াল, বাসায় সেবা (ডাক্তার/নার্স), ডিজিটাল প্রেসক্রিপশন, প্যাথলজির রিপোর্ট, আর কেয়ারগিভার। অ্যাপ নিজে কোনো টাকা নেয় না বা রাখে না।

Health app for Bangladesh — blood donors, ambulances, pharmacies, blood banks, doctor appointments, home care, digital prescriptions, lab reports and caregivers. No money moves through the app.

## ফিচার (সংক্ষেপে)
- **রক্ত:** রিকোয়েস্ট, আশেপাশের যোগ্য ডোনারকে নোটিফিকেশন (geohash), জরুরি আহ্বান, শেয়ারের লিংক
- **লগইন ছাড়া:** তালিকা, প্রশ্নোত্তর, টিপস, আশেপাশের রিকোয়েস্ট দেখা আর ফোন করা; কাজের সময় লগইন (`lib/guest.js`)
- **অ্যাপয়েন্টমেন্ট ও সিরিয়াল সরাসরি:** ডাক্তার/সহকারী "শুরু / পরের রোগী / বিরতি"; রোগী দেখেন কত নম্বর চলছে, আগে কতজন, আনুমানিক সময়; "দেরি হবে" → ডাক্তার "ঠিক আছে / পরে আসুন" (`lib/queue.js`, `Queue.kt`)
- **ডিজিটাল প্রেসক্রিপশন:** ডাক্তার লেখেন, সহকারী রোগী এন্ট্রি করেন, কোড/QR দিয়ে যাচাই (`/rx/{code}`), রোগী কোড + ফোন দিয়ে নিজের অ্যাকাউন্টে আনেন; আবার দেখানো আর ওষুধ শেষের মনে করানো; ওষুধের অ্যালার্ম ফোনেই (`lib/prescriptions.js`)
- **ফার্মেসিতে পাঠানো:** দোকানে গিয়ে (দোকানের QR/নাম/কোড) বা অনলাইনে ডেলিভারি (ঠিকানা, ফোন, ক্যাশ অন ডেলিভারি); দোকান যা দিল লেখে, "ছিল না" তালিকা; দোকানের সেবার উপর দিকভিত্তিক রেটিং (`lib/rxnet.js`, `lib/pharmacy.js`)
- **দোকানের হিসাব-খাতা:** পণ্য, কেনা, বিক্রি, নষ্ট, লাভ-ক্ষতি — শুধু ওয়েবে; অ্যাপে "আজকের হিসাব" আর ওষুধ দেওয়ার পর "বিক্রির হিসাবে তুলবেন?" (`lib/shopbooks.js`, `PharmacyBooks.kt`)
- **প্যাথলজি:** পরীক্ষা ও দাম (ওয়েবে বসানো), প্রেসক্রিপশন পাঠানোর সময় আনুমানিক দাম, বাসা থেকে নমুনা (ঠিকানা শুধু রোগী, কেয়ারগিভার আর সেই প্যাথলজি), ছক-ভরা রিপোর্ট; ডাক্তার রিপোর্ট দেখেন টাকা মিললে বা ফি না নিলে (`lib/labprices.js`, `lib/labtests.js`, `LabPrice.kt`)
- **কেয়ারগিভার ও পরিবার:** ইমেইলে আমন্ত্রণ, রোগীর হয়ে `CARE_ACTIONS`; পরিবারের সদস্যের নামে সিরিয়াল (`lib/careaccess.js`)
- **অ্যাম্বুলেন্স:** রোগীর GPS থাকলে কাছের খালি গাড়ি আগে, "সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন"; চালকের অপেক্ষার জায়গা ঐচ্ছিক, সার্ভারে ~১ কিমির ঘরে; "এখনো খালি আছেন?" মনে করানো (`lib/ambulance.js`)
- **আমার কাজ:** নিজের ব্যবসা ও চেম্বারের এক ট্যাব — রোজের দ্রুত কাজ অ্যাপে, বসে করার বড় কাজ (হিসাব, দাম, রিপোর্ট) ওয়েবে (`Work.kt`, `web/src/sections/work`)
- **ওয়েব অ্যাপ:** মোবাইল/ট্যাবলেট/ডেস্কটপ, অ্যাপের একই অ্যাকাউন্ট (`web/`)
- **অ্যাকাউন্ট মোছা:** ৩০ দিন পরে, এর মধ্যে লগইন করলে বাতিল (`lib/account.js`)
- **ডেমো (শুধু পরীক্ষার জন্য, পরে মুছতে হবে):** নিচে দেখুন

## কোথায় কী

| অংশ | কী | কোথায় |
|---|---|---|
| Android অ্যাপ | Kotlin, Jetpack Compose, Material 3 | `app/` |
| সার্ভার | Node 22, Vercel serverless functions | `server/api`, `server/lib` |
| ওয়েব অ্যাপ (মোবাইল, ট্যাবলেট, ডেস্কটপ) | React + TypeScript (Vite), অ্যাপের একই সার্ভার ও অ্যাকাউন্ট | `web/` → বিল্ড `server/public/w` → `/` (বিস্তারিত `web/README.md`) |
| অ্যাডমিন প্যানেল (ওয়েব) | সাধারণ JS, কোনো বিল্ড লাগে না | `server/public/admin` → `/admin` |
| ডাটাবেস | Firebase Firestore (Spark/ফ্রি প্ল্যান), Firebase Auth (ইমেইল কোড) | `firestore.rules` |
| CI | GitHub Actions | `.github/workflows` |
| নীতিমালা | প্রাইভেসি পলিসি, ব্যবহারের শর্ত | `PRIVACY.md`, `TERMS.md` |
| কাজের ইতিহাস | প্রতিটা ভার্সনে কী বদলেছে | `PROGRESS.md` |

### অ্যাপ (`app/src/main/java/com/bspc/blood`)
সব এক প্যাকেজে, ফিচার ধরে ফাইল: `MainActivity.kt` (নেভিগেশন, নোটিফিকেশন খোলা), `Repositories.kt` (Firestore সরাসরি — প্রোফাইল, রক্তের রিকোয়েস্ট), `ServerApi.kt` (সার্ভারে POST), `Care.kt`/`Appointment.kt` (ডাক্তার, চেম্বার, সিরিয়াল), `HomeCare.kt`, `Ambulance.kt`, `Pharmacy.kt`, `BloodBank.kt`, `Prescriptions.kt` (প্রেসক্রিপশন), `MedAlarms.kt` (ওষুধের অ্যালার্ম, ফোনেই), `RxNet.kt` (ফার্মেসি/প্যাথলজি/রিপোর্ট), `Queue.kt` (সিরিয়াল সরাসরি), `PharmacyBooks.kt` (দোকানের আজকের হিসাব), `LabPrice.kt` (প্যাথলজির দাম), `Work.kt` (আমার কাজ), `CareAccess.kt` (কেয়ারগিভার ও পরিবার), `AdminMobile.kt`, `DemoLogin.kt` (ডেমো — পরে মুছবেন), `Cards.kt`/`Common.kt` (শেয়ার করা UI)। লেখা বাংলা/ইংরেজি দুটোই `tr("English", "বাংলা")` দিয়ে।

### সার্ভার (`server/`)
- Vercel Hobby-তে সর্বোচ্চ ১২টা function — সব ভরা। তাই নতুন কাজ নতুন ফাইলে নয়: `api/community.js`-এ `action` হিসেবে (`lib/*.js`-এর `actions` অবজেক্ট), অথবা `api/share.js`-এ query দিয়ে, `vercel.json`-এর rewrite সহ।
- `api/community.js`: একটা endpoint, `body.action` দিয়ে কাজ বাছাই; লগইন (Firebase ID token বা ওয়েবের কুকি) এখানেই যাচাই হয়। লগইন ছাড়া (অতিথি) শুধু `lib/guest.js`-এর `PUBLIC_ACTIONS` চলে — নতুন পড়ার কাজ অতিথিকে দিতে হলে সেখানে আর অ্যাপের `GuestApi.PUBLIC_ACTIONS`-এ যোগ করুন, আর কাজটা `user.uid == null` সামলায় কি না দেখুন। কেয়ারগিভার রোগীর হয়ে কাজ করলে `body.asUid` — শুধু `lib/careaccess.js`-এর `CARE_ACTIONS`।
- `api/cron/daily.js`: প্রতিদিন ০৩:০০ UTC — মেয়াদ পেরোনো রিকোয়েস্ট, রিমাইন্ডার, geohash রক্ষণাবেক্ষণ, প্রেসক্রিপশনের ফলো-আপ/ওষুধ শেষের মনে করানো।
- নতুন সংগ্রহ (collection) শুধু সার্ভার পড়ে-লেখে; `firestore.rules` ডিফল্টে সব বন্ধ।
- Firestore ফ্রি প্ল্যান: শুধু এক-ঘরের সমান-সমান খোঁজ (composite index নেই)। ছবি Firestore ডকে base64 (Storage-এর জন্য Blaze লাগে)।

### সার্ভারের Environment Variables (Vercel)
| নাম | কী |
|---|---|
| `FIREBASE_SERVICE_ACCOUNT` | Firebase সার্ভিস অ্যাকাউন্টের JSON (সরাসরি বা base64) |
| `CRON_SECRET` | দৈনিক ক্রনের অনুমতি (Vercel নিজে পাঠায়) |
| `WEB_SESSION_SECRET` | ওয়েব প্যানেলের কুকি সই |
| `ADMIN_EMAILS` | মালিক অ্যাডমিনদের ইমেইল (কমা দিয়ে) |
| `GMAIL_USER`, `GMAIL_APP_PASSWORD` | লগইন কোডের ইমেইল পাঠানো |
| `SMS_URL_TEMPLATE` | (ঐচ্ছিক) ফোন যাচাইয়ের SMS; না থাকলে ফোন যাচাই বন্ধ |

এগুলো কোডে বা রিপোজিটরিতে নেই — Vercel-এর সেটিংসে। নতুন কাউকে দিলে আলাদাভাবে, নিরাপদে দিন।

## চালানো ও পরীক্ষা

**সার্ভার:**
```
cd server
npm ci
npm test        # মেমোরিতে নকল Firebase দিয়ে — কোনো কী লাগে না
```
`test/rxflow.test.js` ডেমো অ্যাকাউন্টে পুরো পথ চালায়: প্রেসক্রিপশন → কেয়ারগিভার → দোকানে/অনলাইনে ফার্মেসি → প্যাথলজির ছক → পরিবারের সদস্য → অ্যাডমিন।

**অ্যাপ:** Android Studio (JDK 17, Gradle 8.11)। `app/google-services.json` আছে; `app/debug.keystore` শুধু ডিবাগ সই (Play Store-এর জন্য আলাদা release key লাগবে)।
```
gradle assembleDebug
```
স্ক্রিনশট টেস্ট (Robolectric + Roborazzi): `.github/workflows/screens.yml` হাতে চালালে ছবি `screens` শাখায় জমা হয়।

**ডিপ্লয়:**
- Vercel নিজে কোনো push-এ ডিপ্লয় করে না (`server/vercel.json`: `git.deploymentEnabled: false`) — বাতিল হওয়া (Ignored Build Step) ডিপ্লয়ও দিনের ১০০টার সীমায় গোনা হয়। `main`-এ `server/` বদলালে `.github/workflows/deploy-server.yml` ১০ মিনিট অপেক্ষা করে (এর মাঝে আরও push হলে আগেরটা বাতিল) তারপর Vercel Deploy Hook ডাকে — তাই কয়েকটা বদল মিলে একটা ডিপ্লয়। হুকের URL GitHub সিক্রেট `VERCEL_DEPLOY_HOOK`-এ। হাতে ডিপ্লয়: Actions › Deploy server › Run workflow। হুক না থাকলে: এক কমিটে `server/vercel.json` থেকে `git` অংশ সরিয়ে push (Vercel নিজে ডিপ্লয় করে), সফল হলে পরের কমিটে আবার ফিরিয়ে দেওয়া — একটা ডিপ্লয় খরচ।
- `main`-এ `app/` বদলালে `build.yml` APK বানিয়ে GitHub-এর `latest` রিলিজে রাখে (`lifolo-<version>-<code>.apk`)। ভার্সন: `app/build.gradle.kts`-এর `versionName`/`versionCode`।

## ডেমো (শুধু পরীক্ষার জন্য — পরে মুছতে হবে)
১০টা রোলের ডেমো অ্যাকাউন্ট (রোগী, রক্তদাতা, ডাক্তার, সহকারী, ফার্মেসি, প্যাথলজি, অ্যাম্বুলেন্স, ব্লাড ব্যাংক, নার্স, কেয়ারগিভার) আর ময়মনসিংহে ডামি তথ্য। অ্যাডমিন ডেমোতে নেই (মালিকের নিজের অ্যাকাউন্টে পরীক্ষা)।
- **বানানো:** অ্যাডমিন প্যানেল (`/admin`) › "ডেমো (পরীক্ষা)" › বানান / নতুন করে বানান — শুধু মালিক (`ADMIN_EMAILS`)। সার্ভারে `admin_demo_seed`।
- **লগইন:** অ্যাপ বা ওয়েবের লগইন পাতায় "ডেমো (পরীক্ষার জন্য)" — রোলে চাপলেই সেই অ্যাকাউন্ট (ইমেইল/কোড নেই); প্রোফাইলে "রোল বদলান"। ডেমো বানানো না থাকলে তালিকা খালি, লগইন ৪০৪।
- **মোছা:** একই পাতায় "সব মুছুন" (`admin_demo_wipe`) — আসল অ্যাকাউন্ট-মোছা দিয়ে, সাথে ডেমো ডাক্তারের লেখা প্রেসক্রিপশন।
- **আলাদা রাখা:** ডেমো uid `demo-…`, ইমেইল `@lifolo-demo.test`, প্রতিটা ডামি ডকে `demo: true`; ডেমোর রিকোয়েস্ট/ওষুধ খোঁজায় আসলদের নোটিফিকেশন যায় না, ডেমো আর আসল একে অপরের দোকান/প্যাথলজি/অ্যাম্বুলেন্স/রিকোয়েস্টে যায় না (`sameWorld`)।
- **কোড মুছতে (পরে):** `server/lib/demo/`, `server/test/demo*.test.js` (আর `server/package.json`-এর test তালিকা থেকে), `web/src/demo/`, `app/src/main/java/com/bspc/blood/DemoLogin.kt`; বাকি ফাইলে "ডেমো" মন্তব্য দেওয়া এক-দুই লাইন (`community.js`, `verify-code.js`, `notify-request.js`, `respond.js`, `admin/app.js`, `LoginFlow.tsx`, `Profile.tsx`, `Screens.kt`, `Profile.kt`, আর `lib/`-এর `sameWorld` ফিল্টার) — খুঁজতে: `grep -rn "ডেমো\|sameWorld"`। মোছার আগে প্যানেল থেকে "সব মুছুন" চালান।

## হাসপাতাল/ডায়াগনস্টিকের তালিকা
`server/lib/hospital-dir.json` স্বাস্থ্য অধিদপ্তরের (DGHS) Facility Registry থেকে বানানো। নতুন করে আনতে: `fetch-dghs.yml` ওয়ার্কফ্লো চালান (ফল `data-dghs` শাখায়), তারপর `node scripts/build-hospital-dir.js`।

## অ্যাডমিনের আইকন
`server/public/admin/icons.js` হাতে বদলাবেন না — `node scripts/build-admin-icons.js` দিয়ে বানানো।
