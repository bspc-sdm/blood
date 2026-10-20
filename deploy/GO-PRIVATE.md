# রিপো private করা (bspc-sdm/blood)

রিপো private হলে github.com-এর সব পাবলিক লিংক সবার জন্য বন্ধ হয় (লগইন ছাড়া ৪০৪): রিলিজের APK, `version.json`,
`PRIVACY.md` / `TERMS.md`-এর পাতা। তাই ২.৩৪.১ থেকে এগুলো নিজের সার্ভারে (lifolo.com)। Visibility বদলানো **মালিক নিজে করবেন** —
নিচের ধাপ ১-এর পরে।

## নতুন ঠিকানা

| কী | ঠিকানা | আগে (GitHub — private হলে বন্ধ) |
|---|---|---|
| সর্বশেষ APK | **https://lifolo.com/download/lifolo.apk** | `…/releases/download/latest/roktodan.apk` |
| এই ভার্সনের APK | `https://lifolo.com/download/lifolo-<ভার্সন>-<কোড>.apk` (শেষ ৩টা থাকে) | `…/releases/download/latest/lifolo-….apk` |
| আপডেটের খবর | `https://lifolo.com/download/version.json` | `…/releases/download/latest/version.json` |
| পরীক্ষার APK (next) | `https://lifolo.com/download/lifolo-next.apk` | `…/releases/download/next/lifolo-next.apk` |
| প্রাইভেসি পলিসি | **https://lifolo.com/privacy** | `…/blob/main/PRIVACY.md` |
| ব্যবহারের শর্ত | **https://lifolo.com/terms** | `…/blob/main/TERMS.md` |

- **Build APK** (main-এ versionCode বদলালে, বা হাতে) APK আগে VPS-এ পাঠায় (`/var/www/lifolo-dl/download/`, ক্রম: ভার্সনের APK →
  `lifolo.apk` → সবশেষে `version.json`), তারপর আগের মতো GitHub-এর `latest` রিলিজেও। ফল `ci-log-apk` শাখায়।
- nginx (`deploy/deploy-web.sh`, Deploy backend বসায়): APK-র MIME `application/vnd.android.package-archive` আর
  `Content-Disposition: attachment`; `version.json` → `Cache-Control: no-store` (Cloudflare-এর proxy চালু হলেও কখনো জমা নয় —
  Cloudflare `.apk` নিজে থেকে জমা রাখে, কিন্তু origin-এর `no-store`/`no-cache` মানে); `lifolo.apk` → `no-cache`;
  ভার্সনের নামের APK → এক বছর `immutable` (নাম কখনো বদলায় না)।
- `/privacy`, `/terms`: Deploy backend ওয়েবসাইট বানানোর সময় `PRIVACY.md` / `TERMS.md` থেকে পাতা বানায়
  (`deploy/ci/render-legal.py`) — ফাইল বদলে main-এ push করলেই পাতা বদলায়।
- অ্যাপ ২.৩৪.১ (১২১): আপডেটের খবর, "আপডেট করুন", প্রাইভেসি/শর্ত — সব lifolo.com; GitHub-এর APK লিংকও আপাতত মানে।
  ওয়েব (Vue) আর সার্ভারের শেয়ার পাতা (`/r/…` → "অ্যাপ নামান") একই।

## Actions-এর মিনিট — আগে আর পরে

Private রিপোতে GitHub Free প্ল্যানে মাসে **২,০০০ Linux মিনিট** আর **৫০০ MB** artifact-জায়গা (public-এ সীমা নেই)।
প্রতিটা job কমপক্ষে ১ মিনিট ধরা হয় (৫ সেকেন্ডের কাজও ১ মিনিট)। মাপা হয়েছে REST `actions/runs` আর প্রতিটা run-এর jobs-এর
সময় থেকে: ১৪–২০ অক্টোবর (৫.৪ দিন) ৬০৪টা run, **২,৯৩৭ মিনিট** — মানে এই গতিতে মাসে ~১৬,০০০ (Spring-এ সরানোর সপ্তাহ,
৫০টার বেশি ভার্সন; এর মধ্যে Vercel-এর Deploy server ৪৭০ মিনিট — এখন বন্ধ; হাতে চালানো UI screenshots ৬৭৩ মিনিট)।

গড় (মাপা, প্রতি run): Build APK ২.৪ · Backend (Spring Boot) ৪.০ · Deploy backend ৩.৬ · Web (Vue) check ৪.৯ ·
Backend e2e ২৯ (Spring + Node + publish) · E2E Android ১১ · E2E Android cutover ৩২ (দুই দিক) · UI screenshots ৭.৫।

**নিজে থেকে চলা কাজ (push + schedule), মাসে মিনিট:**

| workflow | আগে (এখনকার main) | পরে (এই বদলের পর) | কী বদলাল |
|---|---|---|---|
| Uptime check | ৪,৩২০ (প্রতি ১০ মিনিট) | ২৪০ (প্রতি ৩ ঘণ্টা) | দ্রুত খবরের জন্য নিচে বাইরের মনিটর |
| Build APK | ~৪৩০ (অ্যাপের প্রতিটা push, দিনে ~৬) | ~২২০ (শুধু versionCode বদলালে, দিনে ~৩) | push-এ ভার্সন না বদলালে কয়েক সেকেন্ডে শেষ |
| Backend e2e | ~২,৪০০ (main-এ backend-এর প্রতিটা push, দিনে ~৩ × ২৭) | ~৭০ (সপ্তাহে একবার, বদল থাকলে; শুধু Spring) | হাতে চালানো যায় |
| E2E Android cutover | ০ (শুধু হাতে) | ~৮০ (সপ্তাহে একবার, বদল থাকলে; শুধু Spring) | হাতে চালানো যায় |
| Backend (Spring Boot) | ~৩৬০ (দিনে ~৩) | ~৩৬০ | আগে থেকেই path ছাঁকনি + cancel-in-progress |
| Deploy backend | ~২২০ (দিনে ~২) | ~২২০ | চলমান deploy বাতিল করা হয় না (ইচ্ছা করে) |
| Web (Vue) check | ~১৫০ (দিনে ~১) | ~১৫০ | cancel-in-progress যোগ |
| Web build check (পুরোনো React) | ~০–১৬০ | ~০–১৬০ | cancel-in-progress যোগ; `web/`-এ কাজ প্রায় নেই |
| **মোট** | **~৭,৯০০** | **~১,৩৫০** | |

ধরে নেওয়া স্বাভাবিক মাস: দিনে ~৩টা অ্যাপ ভার্সন, ~৩টা backend push, ~২টা deploy, ~১টা web-vue push। গত সপ্তাহের মতো
তীব্র গতিতে (দিনে ১০+ ভার্সন, ১০+ backend push) "পরে"-ও মাসে ~৪,০০০ — তখন সীমা পেরোবে।

**সাবধান:**
- Private-এ সাধারণ runner ২-core (public-এ ৪-core) — Gradle/টেস্ট/এমুলেটর ~১.৩–১.৫ গুণ ধীর হতে পারে: ওপরের "পরে" বাস্তবে
  **~১,৭০০–১,৮০০**। হাতে চালানোর জন্য বাকি থাকে মাসে ~২০০–৩০০ মিনিট: UI screenshots (~৮–১১ মিনিট), E2E Android (~১১–১৬),
  E2E Android cutover (~১৬–২৫ শুধু Spring), Backend e2e (~১৫–২০ শুধু Spring) — মাসে মোট ১৫–২০টার মতো।
- মিনিট শেষ হলে মাসের বাকি দিন **কোনো workflow চলে না — Deploy backend-ও না**।
- **পরামর্শ:** Settings → Billing and plans → **Spending limit → Actions: $5** (কার্ড লাগবে)। সীমার ভেতরে থাকলে খরচ $0; পেরোলে
  বাড়তি মিনিট $0.008 করে, সর্বোচ্চ $5 (~৬০০ মিনিট) — deploy আটকায় না।
  বাদ দেওয়া: GitHub Pro ($4/মাস, ৩,০০০ মিনিট) — শান্ত মাসেও খরচ; VPS-এ self-hosted runner — Gradle/এমুলেটর প্রোডাকশনের
  একই মেশিনে চালাবে (ধীর সাইট, আর CI-র কোড প্রোডাকশন সার্ভারে)।
- **দ্রুত বিভ্রাটের খবর:** UptimeRobot (বিনা পয়সায়, প্রতি ৫ মিনিট, ইমেইল) — Monitor type HTTP(s), URL
  `https://lifolo.com/api/health`, Keyword `"ok":true`। GitHub-এর Uptime check প্রতি ৩ ঘণ্টায় পেছনের পাহারা।
- Artifact-জায়গা: এখন ~৩ GB (Build APK-এর `blood-debug-apk` ৯০ দিন রাখা হতো — ২.৭ GB)। ওগুলো মোছা হয়েছে আর Build APK আর
  artifact রাখে না (APK তো lifolo.com আর রিলিজে); বাকি e2e-র artifact ৭ দিনে নিজেই যায়। private-এ ৫০০ MB পেরোলে artifact
  upload ব্যর্থ হয় (তাতে workflow-ও)।

## মালিকের ধাপ

**১) আগে (রিপো এখনো public):**
- ফোনে https://lifolo.com/download/lifolo.apk নামিয়ে ২.৩৪.১ বসান (বর্তমান অ্যাপের ওপরেই; ডেটা থাকে)। ২.৩৪.০ বা পুরোনো অ্যাপ
  শুধু GitHub থেকে আপডেটের খবর নেয় — private হলে আর কখনো নতুন ভার্সনের খবর পাবে না। এখন ব্যবহারকারী শুধু আপনি,
  তাই আপনার ফোন(গুলো) ২.৩৪.১-এ থাকলেই চলবে।
- অ্যাপে Settings → প্রাইভেসি পলিসি / ব্যবহারের শর্ত চাপুন → lifolo.com-এর পাতা খোলে কি না দেখুন।
- (ঐচ্ছিক, পরামর্শ) Billing-এ Actions spending limit $5, আর UptimeRobot — ওপরে।

**২) private করা:** github.com/bspc-sdm/blood → **Settings** → **General** → একদম নিচে **Danger Zone** →
**Change visibility** → **Change to private** → সতর্কবার্তাগুলো পড়ে "I want to make this repository private" → রিপোর নাম
`bspc-sdm/blood` লিখে নিশ্চিত করুন (পাসওয়ার্ড/2FA চাইলে দিন)।

**৩) পরে যাচাই (১০ মিনিট):**
- Actions → **Download check** → Run workflow (main) → সবুজ ✅ হলে: version.json, lifolo.apk, ভার্সনের APK, /privacy, /terms,
  /api/health ঠিক; শেষ সারিতে GitHub-এর পুরোনো লিংক এখন ৪০৪ (প্রত্যাশিত)। ফল `ci-log-download` শাখাতেও।
- ফোনের অ্যাপ: হোমে "নতুন ভার্সন" কার্ড আসে কি না দেখা যাবে পরের ভার্সনে (২.৩৪.২); এখন অন্তত অ্যাপ খুলে কোনো ভুল নেই।
- ব্রাউজারে https://lifolo.com/download/lifolo.apk চাপলে APK নামে (zip নয়)।
- **Deploy backend** তখনো চলে কি না: Actions → Deploy backend → Run workflow (main) — অথবা পরের push-এ নিজে; সবুজ হলে VPS-এর
  SSH, সিক্রেট সব ঠিক (সিক্রেট private হলে বদলায় না)।
- **Actions চলে কি না:** Uptime check ৩ ঘণ্টার মধ্যে একবার সবুজ; Settings → Billing-এ "Actions minutes used" বাড়ছে।

## private হলে যা আর চলবে না (জেনেশুনে)

- ২.৩৪.০ বা পুরোনো অ্যাপ: আপডেটের খবর, "আপডেট করুন", প্রাইভেসি/শর্তের লিংক (সব GitHub-এ) — ৪০৪। সমাধান: ধাপ ১ (২.৩৪.১)।
- Vercel-এর পুরোনো সার্ভারের শেয়ার পাতার "অ্যাপ নামান" (GitHub-এর লিংক) — Vercel বন্ধ হচ্ছে, তাই আর ঠিক করা হয়নি।
- কোথাও আগে পাঠানো/লেখা GitHub-এর APK বা PRIVACY/TERMS লিংক (চ্যাট, ফেসবুক পোস্ট, QR) — lifolo.com-এর লিংক দিন।
- GitHub-এর রিলিজ পাতা আর কোড শুধু আপনি (আর যাকে Collaborator করবেন) দেখবেন।
- যা চলতে থাকবে: Actions আর সিক্রেট, VPS-এ deploy, GitHub রিলিজে APK তোলা (শুধু আপনার জন্য), log শাখাগুলো
  (`ci-log-*`; Claude-এর পড়ার অনুমতি একই থাকে)।

## Cloudflare-এর proxy (কমলা মেঘ) চালুর পর — CI-তে যা বদলাল (২০ অক্টোবর)

- DNS এখন Cloudflare-এর ঠিকানা দেখায় (188.114.x)। `deploy-backend.sh` / `deploy-web.sh` এটাকে ঠিক ধরে ("cloudflare" — সব ঠিকানা
  Cloudflare-এর তালিকায়), তাই ওয়েব বসানো আর `PUBLIC_BASE_URL = https://lifolo.com` আর বাদ পড়ে না (run 39361255368-এ ওয়েবের ধাপ
  বাদ পড়েছিল আর শেয়ার লিংকের ঠিকানা sslip.io-তে চলে গিয়েছিল — পরের deploy-এ ঠিক হয়)।
- সার্টিফিকেট: VPS-এর Let's Encrypt সার্টিফিকেট থাকে (Cloudflare "Full (strict)" এটাই দেখে)। নবায়ন HTTP-01 — Cloudflare দিয়েই আসে;
  প্রতিটা deploy VPS থেকে Cloudflare হয়ে `/.well-known/acme-challenge/`-এ একটা ফাইল পড়ে দেখে (সারাংশে "Let's Encrypt-এর নবায়নের
  পথ")। ❌ হলে: Cloudflare → Security → WAF → Custom rule: URI Path starts with `/.well-known/acme-challenge/` → Skip (সব)।
- পরীক্ষা: Deploy backend, Build APK, Download check, Uptime — আসল পথে (Cloudflare দিয়ে) একবার, Cloudflare GitHub-এর মেশিনকে
  challenge/403 দিলে শুধু ⏭️/ℹ️; বাকি সব সরাসরি VPS-এ (আসল নাম আর সার্টিফিকেট, `--resolve` / `/etc/hosts`)।
- ডেমো লগইনের পরীক্ষা ৪০৩ পেয়েছিল Cloudflare-এর Browser Integrity Check-এ ("Python-urllib" নাম) — এখন নিজের নাম পাঠায় আর
  সরাসরি VPS-এ যায়।
- অ্যাপ (Android, নিজের নামে) আর ব্রাউজার সাধারণত আটকায় না। Download check-এর "আসল পথে" সারিতে `cf-cache` দেখায় —
  `version.json`-এ কখনো `HIT` হওয়া উচিত নয় (`no-store`)।
- Cloudflare-এর **Browser Cache TTL** এখন `lifolo.apk`-এর `no-cache`-কে ব্রাউজারের জন্য `max-age=14400` (৪ ঘণ্টা) করে দেয় —
  একই ফোনের ব্রাউজার ৪ ঘণ্টা পুরোনো APK দিতে পারে (অ্যাপের "আপডেট করুন" ভার্সনের নামের লিংক নেয়, তাতে সমস্যা নেই)।
  ঠিক করতে: Cloudflare → Caching → Configuration → Browser Cache TTL → **Respect Existing Headers**।

