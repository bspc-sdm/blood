## অ্যান্ড্রয়েড অ্যাপ: নতুন সার্ভারে যাওয়া

### অ্যাপ কোন ঠিকানা ব্যবহার করে
অ্যাপ সব কাজে **একটাই ঠিকানা** নেয় (`ServerApi.BASE_URL`): `/api/…` (সব কাজ), `/r/{id}` (রিকোয়েস্ট শেয়ারের লিংক),
`/rx/{কোড}` আর `/shop/{কোড}` (QR), `/admin`, `/work/…` (ওয়েবের পাতা, ব্রাউজারে খোলে)। তাই নতুন ঠিকানা
**https://lifolo.com** (ওয়েব আর `/api` একসাথে)। `https://api.lifolo.com`-এ শুধু `/api`, `/r`, `/rx`, `/shop`,
`/.well-known` আছে — ওয়েবের পাতা (অ্যাডমিন, ল্যাবের দাম, ফার্মেসির হিসাব) নেই, তাই ওটা শুধু lifolo.com চালু
হওয়ার আগের পরীক্ষার জন্য।

ঠিকানা এখন বিল্ডের সময় ঠিক হয় (`app/build.gradle.kts`):
`gradle assembleDebug -PlifoloServer=https://lifolo.com` — না দিলে আগের মতোই `https://blood-server-ashy.vercel.app`।
শেয়ার-লিংকের নাম (AndroidManifest-এর App Link) একই ঠিকানা থেকে আসে।

### পরীক্ষার APK (lifolo-next.apk) ইনস্টল
1. ফোনে খুলুন: **https://github.com/bspc-sdm/blood/releases/download/next/lifolo-next.apk** → ডাউনলোড → ইনস্টল।
   (বানায় GitHub Actions-এর **Build APK (next)** — Run workflow; `cutover-android` শাখায় অ্যাপ বদলালে নিজেই চলে।)
2. বর্তমান Lifolo-র ওপরেই বসে (একই অ্যাপ, একই সাইন) — আনইনস্টল লাগে না, লগইন থাকে।
3. সেটিংসে ভার্সন দেখাবে **২.৩৩.১-next** — বুঝবেন নতুন সার্ভারে আছেন। কোন সার্ভার, তা রিলিজের নোটে লেখা থাকে।
   এখনকারটা **https://api.lifolo.com**-এ (lifolo.com-এর সার্টিফিকেট এখনো হয়নি)। lifolo.com চালু হলে
   Build APK (next) আবার চালালেই (server = auto) নিজে lifolo.com নেবে।
4. ডেমো অ্যাকাউন্টে বা নিজের অ্যাকাউন্টে স্বাভাবিকভাবে ব্যবহার করে দেখুন।
5. যন্ত্রে পরীক্ষা: **E2E Android cutover (Node vs Spring)** একই ৮টা যাত্রা দুই সার্ভারে চালায়; ফল
   `ci-log-cutover-android` শাখার `summary.md`-এ (২০ অক্টোবর: সমান — Spring-এ এমন কিছু ব্যর্থ হয়নি যা Node-এ পাস করে)।

### আগের অবস্থায় ফেরা
- **https://github.com/bspc-sdm/blood/releases/download/latest/roktodan.apk** ডাউনলোড করে আবার ইনস্টল করুন —
  ভার্সন একই (versionCode এক), তাই ওপরেই বসে আর অ্যাপ আবার Vercel-এর (Node) সার্ভারে কথা বলে।
- main-এ নতুন ভার্সন (যেমন ২.৩৪.০) এলে next-এ থাকা ফোনেও "আপডেট আছে" দেখাবে; আপডেট করলে সেটাও পুরোনো
  সার্ভারে ফেরা। নতুন সার্ভারে থাকতে চাইলে Build APK (next) আবার চালান (main-এর ভার্সন নিয়ে বানাতে হলে
  আগে main থেকে cutover-android-এ মিলিয়ে নিন)।
- সাবধান: next দিয়ে যা লেখা হয় (রিকোয়েস্ট, অর্ডার …) তা নতুন সার্ভারের ডেটাবেসে যায়, পুরোনোটায় নয়।

### কাটওভারের দিন আসল রিলিজে যা বদলায়
1. `app/build.gradle.kts`-এ ডিফল্ট ঠিকানা এক লাইনে বদল: `?: "https://blood-server-ashy.vercel.app"` →
   `?: "https://lifolo.com"` (BASE_URL, শেয়ার-লিংক, QR, App Link-এর নাম — সব এখান থেকেই)।
2. পুরোনো শেয়ার-লিংক (`blood-server-ashy.vercel.app/r/…`) যেন অ্যাপে খোলে, AndroidManifest-এ ওই নামের
   intent-filter-টা আলাদা করে রেখে দিন (দুটো নামেই `/.well-known/assetlinks.json` চলতে হবে — Vercel আর VPS)।
3. সাধারণ নিয়মে ভার্সন বাড়ানো (versionCode +১, versionName নতুন) — main-এ push → **Build APK** → `latest` →
   অ্যাপের ভেতরের "আপডেট আছে" সবাইকে নতুন APK দেয়।
4. যারা আপডেট করবে না তাদের পুরোনো অ্যাপ Vercel-এর ঠিকানাতেই ডাকবে — তাই Vercel-কে কিছুদিন চালু রাখুন
   (বা তার `/api` নতুন সার্ভারে পাঠান), নইলে তাদের অ্যাপ কাজ করবে না বা ডেটা দুই জায়গায় ভাগ হবে।
5. ফেরা: আগের ভার্সনের ঠিকানায় নতুন ভার্সন (আবার versionCode +১) — Android ছোট versionCode-এ নামতে দেয় না।
