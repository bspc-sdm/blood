# Lifolo

বাংলাদেশের জন্য একটা স্বাস্থ্য অ্যাপ: রক্তদাতা খোঁজা ও জরুরি রক্তের আহ্বান, অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, ডাক্তারের চেম্বারের সিরিয়াল, বাসায় সেবা (ডাক্তার/নার্স), ডিজিটাল প্রেসক্রিপশন, প্যাথলজির রিপোর্ট, আর কেয়ারগিভার। অ্যাপ নিজে কোনো টাকা নেয় না বা রাখে না।

Health app for Bangladesh — blood donors, ambulances, pharmacies, blood banks, doctor appointments, home care, digital prescriptions, lab reports and caregivers. No money moves through the app.

## কোথায় কী

| অংশ | কী | কোথায় |
|---|---|---|
| Android অ্যাপ | Kotlin, Jetpack Compose, Material 3 | `app/` |
| সার্ভার | Node 22, Vercel serverless functions | `server/api`, `server/lib` |
| ওয়েব অ্যাপ (মোবাইল, ট্যাবলেট, ডেস্কটপ) | React + TypeScript (Vite), অ্যাপের একই সার্ভার ও অ্যাকাউন্ট | `web/` → বিল্ড `server/public/w` → `/` (বিস্তারিত `web/README.md`) |
| অ্যাডমিন প্যানেল (ওয়েব) | সাধারণ JS, কোনো বিল্ড লাগে না | `server/public/admin` → `/admin` |
| ডাক্তার/সহকারী/ফার্মেসি/ল্যাবের ওয়েব | সাধারণ JS | `server/public/doctor` → `/doctor` |
| ডাটাবেস | Firebase Firestore (Spark/ফ্রি প্ল্যান), Firebase Auth (ইমেইল কোড) | `firestore.rules` |
| CI | GitHub Actions | `.github/workflows` |
| নীতিমালা | প্রাইভেসি পলিসি, ব্যবহারের শর্ত | `PRIVACY.md`, `TERMS.md` |
| কাজের ইতিহাস | প্রতিটা ভার্সনে কী বদলেছে | `PROGRESS.md` |

### অ্যাপ (`app/src/main/java/com/bspc/blood`)
সব এক প্যাকেজে, ফিচার ধরে ফাইল: `MainActivity.kt` (নেভিগেশন, নোটিফিকেশন খোলা), `Repositories.kt` (Firestore সরাসরি — প্রোফাইল, রক্তের রিকোয়েস্ট), `ServerApi.kt` (সার্ভারে POST), `Care.kt`/`Appointment.kt` (ডাক্তার, চেম্বার, সিরিয়াল), `HomeCare.kt`, `Ambulance.kt`, `Pharmacy.kt`, `BloodBank.kt`, `Prescriptions.kt` (প্রেসক্রিপশন), `RxNet.kt` (ফার্মেসি/প্যাথলজি/রিপোর্ট), `CareAccess.kt` (কেয়ারগিভার ও পরিবার), `AdminMobile.kt`, `Cards.kt`/`Common.kt` (শেয়ার করা UI)। লেখা বাংলা/ইংরেজি দুটোই `tr("English", "বাংলা")` দিয়ে।

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

## হাসপাতাল/ডায়াগনস্টিকের তালিকা
`server/lib/hospital-dir.json` স্বাস্থ্য অধিদপ্তরের (DGHS) Facility Registry থেকে বানানো। নতুন করে আনতে: `fetch-dghs.yml` ওয়ার্কফ্লো চালান (ফল `data-dghs` শাখায়), তারপর `node scripts/build-hospital-dir.js`।

## অ্যাডমিনের আইকন
`server/public/admin/icons.js` হাতে বদলাবেন না — `node scripts/build-admin-icons.js` দিয়ে বানানো।
