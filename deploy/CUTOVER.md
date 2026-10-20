# বদল (cutover) — Vercel থেকে নিজের সার্ভারে (VPS)

**অবস্থা: বদল হয়ে গেছে — ২০ অক্টোবর ২০২৬, অ্যাপ ২.৩৪.০ (১২০)।** মালিকের অনুমোদনে; এখন একমাত্র ব্যবহারকারী মালিক নিজে।
Firestore-এর তথ্য নতুন সার্ভারে **আনা হয়নি** (মালিকের সিদ্ধান্ত, জেনেশুনে) — নতুন সার্ভার শুরু হয়েছে শুধু ডেমো দিয়ে।

| কী | কোথায় | কে ব্যবহার করে |
|---|---|---|
| নতুন সার্ভার (Spring) + তথ্য PostgreSQL-এ | VPS `169.58.58.176` — `https://lifolo.com/api/…` (আর `https://api.lifolo.com`) | অ্যাপ ২.৩৪.০ আর পরের সব |
| নতুন ওয়েব (Vue) + নতুন অ্যাডমিন | VPS — `https://lifolo.com` (`/w/`, `/admin/`; `www` → `lifolo.com`) | সবাই |
| পুরোনো সার্ভার (Node) + ওয়েব (React) + অ্যাডমিন | Vercel — `https://blood-server-ashy.vercel.app`, তথ্য Firestore-এ | শুধু পুরোনো অ্যাপ (২.৩৩.১ পর্যন্ত), যতদিন আপডেট না করে |

Firebase (লগইন আর পুশ) দুই সার্ভারেরই এক — তাই একই অ্যাকাউন্টে দুই জায়গাতেই লগইন হয়।
ডেমোর অ্যাকাউন্টগুলোও এক: VPS-এ `DEMO_KEEP_FIREBASE_USERS=true`, তাই নতুন সার্ভারে "ডেমো মুছুন" চাপলেও Firebase-এর
ডেমো অ্যাকাউন্ট থেকে যায় (Vercel-এর ডেমো ভাঙে না)।

**VPS-এ নতুন কোড বসানো:** main-এ `backend/`, `web-vue/` বা `deploy/` বদলালে **Deploy backend** নিজেই চলে (আগে shellcheck,
তারপর ডেমো `keep`, ওয়েবসাইট `lifolo.com`, আলাদা নাম `api.lifolo.com`)। হাতে: **Actions → Deploy backend → Run workflow →
branch `main`**। ৫–১০ মিনিট পর Summary-তে সব ✅ (একই ফল `ci-log-deploy` শাখার `summary.md`-এ)।

---

## যা হয়ে গেছে (২০ অক্টোবর ২০২৬)

1. `spring` (Spring সার্ভার, Vue ওয়েব, VPS-এর workflow) আর `cutover-android` (অ্যাপের ঠিকানার সুইচ, Build APK (next),
   Android e2e) main-এ মেলানো।
2. **অ্যাপ ২.৩৪.০ (১২০):** ডিফল্ট ঠিকানা `https://lifolo.com` (`app/build.gradle.kts`, এক লাইন)। পুরোনো শেয়ার লিংক
   `https://blood-server-ashy.vercel.app/r/…` এখনো অ্যাপে খোলে (AndroidManifest-এ আলাদা intent-filter)।
   main-এ push → **Build APK** → `latest` রিলিজ → পুরোনো অ্যাপ "নতুন সংস্করণ আছে" দেখায়।
3. **SSE keep-alive:** `/api/events` প্রতি ২৫ সেকেন্ডে একটা মন্তব্য-লাইন (`: ping`) পাঠায় — Cloudflare-এর proxy ১০০ সেকেন্ড
   চুপ থাকলে সংযোগ কাটে, এখন আর কাটবে না (পরীক্ষা: `backend/src/test/kotlin/com/lifolo/notify/SseKeepAliveIT.kt`)।
4. **Deploy backend** main-এ push হলে নিজেই চলে (হাতে চালানোও আগের মতো)। Backend (Spring Boot) আর Backend e2e-ও main-এ চলে।
5. **Uptime check** প্রতি ১০ মিনিটে `https://lifolo.com/api/health` দেখে (Variables-এ `HEALTH_URL` দিলে সেটা)।
6. **Vercel-এ আর ডেপ্লয় নয়:** "Deploy server" workflow-এর push-trigger সরানো আর Actions-এ Disabled (ফাইল মোছা হয়নি);
   `server/vercel.json`-এ আগে থেকেই `git.deploymentEnabled: false`। Vercel প্রকল্প, তার ডোমেইন, cron আর Firestore —
   কিছুতেই হাত দেওয়া হয়নি; `server/` কোড রিপোতে আছে।
7. **তথ্য সরানো হয়নি** — নতুন সার্ভারে শুধু ডেমো (গাজীপুর)। মালিকের নিজের অ্যাকাউন্ট Firebase-এর, তাই লগইন একই থাকে;
   কিন্তু প্রোফাইল, রক্তের রিকোয়েস্ট, দোকান ইত্যাদি নতুন সার্ভারে আবার দিতে হবে। Firestore-এ পুরোনো সব যেমন ছিল আছে।

ফল (CI আর রিলিজ): নিচে "যাচাই" অংশে।

## মালিকের বাকি কাজ

### ১. Cloudflare proxy চালু (কমলা মেঘ) — এখন করা যায়
1. Cloudflare → `lifolo.com` → **SSL/TLS → Overview → "Full (strict)"** (VPS-এ Let's Encrypt সার্টিফিকেট আছে)।
   ⚠️ আগে এটা, তারপর মেঘ — "Flexible" থাকলে সাইট বারবার নিজের দিকে ঘোরে (redirect loop)।
2. **DNS → Records:** `@` (lifolo.com), `www` আর `api` — তিনটার মেঘে চাপ দিয়ে কমলা (**Proxied**) → Save।
3. চালুর ৫ মিনিট পর খুলে দেখুন: `https://lifolo.com`, `https://lifolo.com/admin/`, `https://lifolo.com/api/health`,
   `https://api.lifolo.com/api/health` — আর ফোনের অ্যাপে লগইন, একটা তালিকা খোলা।
4. যা আগে থেকেই বসানো, কিছু করতে হবে না:
   - VPS আসল দর্শকের IP Cloudflare-এর `CF-Connecting-IP` থেকে নেয় (শুধু Cloudflare-এর ঠিকানা থেকে এলে) — অতিথির সীমা আর
     লগইন-কোডের সীমা ঠিক থাকে।
   - `/w/assets/` আর `/admin/assets/`-এর ফাইলে এক বছরের cache — Cloudflare নিজেই জমা রাখে; পাতা (`/`, `/admin/`) আর `/api`
     কখনো জমা হয় না। **"Cache Everything" নিয়ম দেবেন না।**
   - Let's Encrypt-এর নবায়ন proxy চালু থাকলেও চলে (`/.well-known/acme-challenge/` http আর https দুটোতেই খোলা)।
   - `/api/events` (SSE): ২৫ সেকেন্ডের ping আছে, Cloudflare-এর ১০০ সেকেন্ডের সীমায় কাটবে না।
5. সমস্যা হলে: মেঘ আবার ধূসর (DNS only) — কয়েক মিনিটে সরাসরি VPS।

### ২. নজর রাখা (প্রথম কয়েক দিন)
Uptime check-এর ইমেইল (`https://lifolo.com/api/health` বন্ধ হলে আসে), Sentry (চালু থাকলে), অ্যাডমিন প্যানেলের রিপোর্ট আর টিকিট।
Amazon SES production access অনুমোদিত না হলে লগইন-কোডের ইমেইল শুধু যাচাই করা ঠিকানায় যায় — অনুমোদনের পর অচেনা একটা
ঠিকানা দিয়ে `https://lifolo.com/admin/`-এ একবার লগইন করে দেখুন।

### ৩. পরে (মালিকের অনুমতি নিয়ে, একটা একটা করে)
1. **Vercel বন্ধ:** পুরোনো অ্যাপ (২.৩৩.১ পর্যন্ত) Vercel-এ কথা বলে — এখন একমাত্র ব্যবহারকারী মালিক নিজে, তাই নিজের ফোনে
   ২.৩৪.০ বসানো হলেই Vercel আর দরকার নেই। ক্রম: (ক) Vercel ড্যাশবোর্ডে Deploy Hook থাকলে মুছুন; (খ) প্রকল্প **Pause**
   (Settings → General) — মোছা নয়, ফেরার রাস্তা; (গ) কিছুদিন পর ইচ্ছে হলে মোছা। `vercel.json`-এর দৈনিক cron
   (`/api/cron/daily`)-এর কাজ নতুন সার্ভারে JobRunr নিজেই করে।
   চাইলে Vercel-এ শুধু একটা পাতা রাখা যায় "নতুন সংস্করণ নামান"।
2. **Firestore:** ✅ export আর তালা দেওয়া হয়ে গেছে (২০ অক্টোবর ২০২৬) — নিচে "Firestore বন্ধ" দেখুন। বাকি শুধু ডাটাবেস মোছা
   (ইচ্ছে হলে, মালিকের অনুমতিতে, হাতে)। Firebase Authentication (লগইন) আর Cloud Messaging (পুশ) নতুন সার্ভারও ব্যবহার করে —
   Firebase প্রকল্প **বন্ধ করা যাবে না**, শুধু Firestore ডাটাবেস।
3. **`DEMO_KEEP_FIREBASE_USERS`:** Vercel-এর ডেমো আর না থাকলে GitHub secret `DEMO_KEEP_FIREBASE_USERS` = `false` দিয়ে
   Deploy backend আবার চালান — তখন "ডেমো মুছুন" Firebase-এর ডেমো অ্যাকাউন্টও মোছে।
4. Vercel বন্ধের পর: `server/` (Node), `web/` (React), "Deploy server" আর "Web build check" workflow রিপো থেকে সরানো যায়
   (আলাদা কাজ, অনুমতি নিয়ে)।

## Firestore বন্ধ (২০ অক্টোবর ২০২৬)

মালিকের অনুমোদনে **Firestore-এ তালা দেওয়া হয়েছে** — ২০ অক্টোবর ২০২৬, ০২:৫১ UTC। workflow: **Firestore off (one-off)**
(`.github/workflows/firestore-off.yml`, চালাতে `confirm` = `LOCK`); ফল `ci-log-firestore-off` শাখার `summary.md`-এ।
অ্যাপ (২.৩৪.০+) আর ওয়েব (lifolo.com) Firestore ব্যবহার করে না (শুধু VPS-এর Spring সার্ভার + PostgreSQL); Vercel paused।

1. **পুরো export** (firebase-admin দিয়ে — (default) ডাটাবেসের সব collection আর subcollection, নথি JSON-এ; Timestamp, GeoPoint,
   reference, bytes আলাদা `__type` চিহ্নে) → gzip → সরাসরি VPS-এ (GitHub-এ কোনো কপি নেই):
   **`/var/backups/lifolo/firestore-final-2026-10-20.json.gz`** (root, `600`)। VPS-এ খুলে প্রতিটা collection-এর নথির সংখ্যা আর
   sha256 মেলানো হয়েছে ✅ — মোট ১৫৫২ নথি, ৪৭টা top-level collection (প্রকল্পে ডাটাবেস শুধু `(default)`)।
   ফাইলের গঠন: `{"format":"lifolo-firestore-export/1","project":…,"exportedAt":…,"documents":[{"path","createTime","updateTime","data"}…]}`।
   এই ফোল্ডারের দৈনিক ব্যাকআপ-পরিষ্কার শুধু `lifolo-*.dump` মোছে — এই ফাইল থেকে যায়।
2. **আগের rules** একই ফোল্ডারে: **`/var/backups/lifolo/firestore-rules-before-2026-10-20.rules`** (root, `600`)। এটা রিপোর
   `firestore.rules`-এর সাথে হুবহু এক **নয়** — ফেরত আনতে এই ফাইল (বা কনসোলের ইতিহাস) ব্যবহার করুন, রিপোরটা নয়।
3. **এখনকার rules: সব পথে deny-all** (`match /{document=**} { allow read, write: if false; }`) — প্রকাশের পর আবার পড়ে মেলানো ✅।
   ফোনের/ব্রাউজারের Firebase SDK দিয়ে কেউ আর কিছু পড়তে-লিখতে পারে না। (firebase-admin rules মানে না, কিন্তু এখন কোনো
   সার্ভার Firestore ব্যবহার করে না।)
4. **Firebase Storage:** ডিফল্ট বাকেট (`blood-3eb28.firebasestorage.app`, পুরোনো `blood-3eb28.appspot.com`) নেই, Storage rules-ও
   নেই — কিছু করার ছিল না, হাত দেওয়া হয়নি।
5. **কোনো নথি বা ডাটাবেস মোছা হয়নি।** Firebase Authentication, Cloud Messaging, Android/ওয়েব অ্যাপের নিবন্ধন, authorized
   domains আর service account — কিছুতেই হাত দেওয়া হয়নি।

**আগের rules ফেরত আনা** (দরকার হলে, যেমন পুরোনো Vercel আবার চালু করলে):
- সহজ পথ: Firebase কনসোল → Firestore Database → **Rules** → বাঁ দিকের ইতিহাস (history) থেকে ২০ অক্টোবর ০২:৫১ UTC-র **আগের**
  সংস্করণ খুলে লেখাটা কপি → এডিটরে পেস্ট → **Publish**।
- অথবা VPS-এর কপি: Contabo-র VNC কনসোলে root হিসেবে `cat /var/backups/lifolo/firestore-rules-before-2026-10-20.rules` → লেখাটা
  কনসোলের Rules এডিটরে পেস্ট → **Publish**।

**ডাটাবেস মোছা (পরে, ইচ্ছে হলে):** এটা **হাতে, Firebase/Google Cloud কনসোল থেকে**, আর **শুধু মালিকের অনুমতিতে** — কোনো workflow
বা স্ক্রিপ্ট এটা করে না। আগে নিশ্চিত হোন ওপরের export ফাইলটা VPS-এ আছে (চাইলে আরেকটা কপি নিজের কাছে)। মুছলে শুধু **Firestore
ডাটাবেস** — Firebase প্রকল্প নয়: **Firebase Authentication (লগইন) আর Cloud Messaging (পুশ) থাকতেই হবে**, নতুন সার্ভার এগুলো
ব্যবহার করে (firebase-admin দিয়ে লগইন যাচাই, ব্যবহারকারী আর পুশ)।

## ফেরা (rollback)

**Vercel পুরোটা আগের মতোই চালু আছে**, তাই ফেরা সহজ:

| কী ভেঙেছে | কী করবেন |
|---|---|
| নতুন ফোনের অ্যাপ (নতুন সার্ভারে) কাজ করছে না | ২.৩৩.১-এর কোড থেকে আরেকটা নতুন সংস্করণ (versionCode আরও বাড়িয়ে, যেমন ১২১; ঠিকানা Vercel) — `latest`-এ গেলে অ্যাপ আবার Vercel-এ। নিচে "Android" দেখুন। |
| lifolo.com-এর ওয়েব ভুল দেখাচ্ছে | VPS-এ আগের ওয়েব: `ln -sfn /var/www/lifolo-site/releases/<আগেরটা> /var/www/lifolo-site/current` (Summary-তে "আগেরটা"-র নাম আছে; reload লাগে না)। অথবা আগের commit থেকে Deploy backend আবার। |
| নতুন সার্ভার (Spring) চালু হচ্ছে না | Deploy backend নিজেই আগের jar-এ ফেরে ("আগের jar-এ ফেরা")। হাতে: `/opt/lifolo/api/app.jar.prev` → `app.jar`, `systemctl restart lifolo-api`। |
| Cloudflare proxy চালুর পর সমস্যা | Cloudflare-এ মেঘ আবার ধূসর (DNS only) — কয়েক মিনিটে সরাসরি VPS। |
| পুরো VPS-ই সমস্যায় | অ্যাপের আগের ঠিকানায় (Vercel) নতুন সংস্করণ (ওপরের প্রথম সারি); ওয়েবের জন্য Cloudflare-এ `lifolo.com` আর `www` Vercel-এর দিকে (Vercel-এ ডোমেইন যোগ করে)। |
| নতুন সার্ভারে লেখা তথ্য | Firestore-এ ফেরে না। Vercel-এ ফিরলে নতুন সার্ভারে দেওয়া তথ্য সেখানে থাকবে না (এখন শুধু মালিকের, তাই ঝুঁকি কম)। |

## Android

### অ্যাপ কোন ঠিকানা ব্যবহার করে
অ্যাপ সব কাজে **একটাই ঠিকানা** নেয় (`ServerApi.BASE_URL` = `BuildConfig.SERVER_URL`): `/api/…` (সব কাজ), `/r/{id}`
(রিকোয়েস্ট শেয়ারের লিংক), `/rx/{কোড}` আর `/shop/{কোড}` (QR), `/admin`, `/work/…` (ওয়েবের পাতা, ব্রাউজারে খোলে)। তাই ঠিকানা
**https://lifolo.com** (ওয়েব আর `/api` একসাথে)। `https://api.lifolo.com`-এ শুধু `/api`, `/r`, `/rx`, `/shop`, `/.well-known`
আছে — ওয়েবের পাতা (অ্যাডমিন, ল্যাবের দাম, ফার্মেসির হিসাব) নেই, তাই অ্যাপের জন্য নয়।

ঠিকানা বিল্ডের সময় ঠিক হয় (`app/build.gradle.kts`):
`gradle assembleDebug -PlifoloServer=https://…` — না দিলে **`https://lifolo.com`** (২.৩৪.০ থেকে; আগে
`https://blood-server-ashy.vercel.app`)। শেয়ার-লিংকের নাম (AndroidManifest-এর App Link, `${linkHost}`) একই ঠিকানা থেকে আসে;
পুরোনো Vercel-এর নামের `/r/` লিংকের জন্য আলাদা একটা intent-filter আছে (দুটো নামেই `/.well-known/assetlinks.json` চলে —
VPS আর Vercel)। QR (`/rx/`, `/shop/`) পড়া যেকোনো নামেই চলে।

### সাধারণ রিলিজ (latest)
main-এ versionCode বদলালে **Build APK** → VPS-এ **https://lifolo.com/download/** (`lifolo-<ভার্সন>-<কোড>.apk`, `lifolo.apk`,
`version.json`; ২.৩৪.১ থেকে অ্যাপের "আপডেট আছে" এখান থেকে) আর আগের মতো GitHub-এর `latest` রিলিজেও (২.৩৪.০ পর্যন্ত অ্যাপের জন্য,
রিপো public থাকা পর্যন্ত)। সরাসরি লিংক: **https://lifolo.com/download/lifolo.apk** — রিপো private করা: `deploy/GO-PRIVATE.md`

### পরীক্ষার APK (next)
**Build APK (next)** (`cutover-android` শাখায় অ্যাপ বদলালে নিজেই, বা Run workflow; `server` = auto / পুরো ঠিকানা) একই key-তে
`lifolo-next.apk` বানায় (ভার্সনের শেষে `-next`): **https://lifolo.com/download/lifolo-next.apk** (GitHub-এর `next` প্রি-রিলিজেও)।
এখন main নিজেই lifolo.com-এ, তাই next শুধু অন্য কোনো ঠিকানা পরীক্ষার জন্য।
যন্ত্রে পরীক্ষা: **E2E Android cutover (Node vs Spring)** একই ৮টা যাত্রা দুই সার্ভারে চালায়; ফল `ci-log-cutover-android`
শাখার `summary.md`-এ (২০ অক্টোবর: সমান — Spring-এ এমন কিছু ব্যর্থ হয়নি যা Node-এ পাস করে)।

### আগের সার্ভারে ফেরা
Android ছোট versionCode-এ নামতে দেয় না, তাই ফেরা মানে আরেকটা নতুন সংস্করণ: `app/build.gradle.kts`-এ ডিফল্ট ঠিকানা আবার
`https://blood-server-ashy.vercel.app`, versionCode +১ (১২১…), versionName নতুন → main-এ push → Build APK → `latest`।
(পুরোনো `roktodan.apk` আর ২.৩৩.১ নয় — `latest` এখন ২.৩৪.০।)

## যাচাই (২০ অক্টোবর ২০২৬)

- **main = `761a230`** (fast-forward; আগে একই commit `cutover-main` শাখায় পরীক্ষা)।
- আগে শাখায়: Backend (Spring Boot) ৩৫২/৩৫২ (নতুন SseKeepAliveIT ৪/৪), Backend e2e Spring ১৭৫/১৭৫ = Node ১৭৫/১৭৫,
  Web (Vue) ✅, অ্যাপ কম্পাইল ✅ (Build APK (next) → `next` = 2.34.0-next, lifolo.com)।
- main-এ: একই সব সবুজ (ফল `ci-log-main` শাখায়); **Build APK** ✅ → `latest` রিলিজ: `lifolo-2.34.0-120.apk`, `roktodan.apk`,
  `version.json` (`versionCode 120`, `2.34.0`) — https://github.com/bspc-sdm/blood/releases/download/latest/lifolo-2.34.0-120.apk
- **Deploy backend** main-এর push থেকে নিজেই চলেছে ✅ (check → deploy; Flyway V601, ডেমো keep, lifolo.com + www + api.lifolo.com
  সব ✅, PUBLIC_BASE_URL `https://lifolo.com`; ফল `ci-log-deploy`-এ)।
- **Uptime check** (main, নতুন ঠিকানা `https://lifolo.com/api/health`) ✅।
- "Deploy server" (Vercel) — Actions-এ `disabled_manually`; Vercel প্রকল্প, Firestore, Firebase-এ কিছু বদলানো/মোছা হয়নি।
- জানা ঝুঁকি: SES সম্ভবত এখনো sandbox-এ (লগইন-কোডের ইমেইল শুধু যাচাই করা ঠিকানায়); R2 (ছবি) আর Sentry বন্ধ।
