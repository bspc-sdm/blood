# Sentry — ভুলের খবর (২.৩৪.১ থেকে)

Sentry org `lifolo`, তিনটা প্রকল্প। শুধু ভুল (error/crash, অ্যাপে ANR-ও) — কোনো tracing, performance, profiling, replay বা
session নয়, তাই Free প্ল্যানের ভুলের কোটার বাইরে কিছু খরচ হয় না (sampleRate 1.0 শুধু ভুলের জন্য)।

| প্রকল্প | কোথা থেকে | GitHub সিক্রেট | কীভাবে ঢোকে |
|---|---|---|---|
| lifolo-server | Spring Boot (VPS) | `SENTRY_DSN_BACKEND` (না থাকলে পুরোনো `SENTRY_DSN`) | Deploy backend → `/etc/lifolo/app.env`-এ `SENTRY_DSN`, `SENTRY_RELEASE` |
| lifolo-web | Vue ওয়েব (lifolo.com) | `SENTRY_DSN_WEB` | Deploy backend-এর ওয়েব বিল্ডে `VITE_SENTRY_DSN`; CSP-র connect-src-এ শুধু ওই Sentry ঠিকানা |
| lifolo-android | Android অ্যাপ | `SENTRY_DSN_ANDROID` | Build APK / Build APK (next)-এ `BuildConfig.SENTRY_DSN` |

সিক্রেট খালি বা ভুল হলে সেই জায়গায় Sentry বন্ধ — অন্য কিছু ভাঙে না। অ্যাডমিন প্যানেল (/admin) Sentry-তে কিছু পাঠায় না
(তার কড়া CSP বদলায়নি)। release: `lifolo-server@<ভার্সন>+<commit>`, `lifolo-web@<ভার্সন>+<commit>`,
`lifolo-android@<ভার্সন>+<versionCode>`; environment `production` (next APK-এ `next`)।

**ব্যক্তিগত তথ্য নয়:** user, cookie, request body আর বেশিরভাগ header বাদ; বার্তা, ভুলের লেখা আর breadcrumb থেকে ইমেইল,
ফোন নম্বর (০১…, +৮৮০…, অন্য +নম্বর), Bearer/JWT টোকেন আর `token=`/`code=`/`key=`… ধরনের মান মুছে `[email]`, `[phone]`,
`[token]`, `[filtered]`। অ্যাপে স্ক্রিনশট আর view hierarchy বন্ধ। (backend/…/common/SentrySetup.kt,
web-vue/src/lib/sentry.ts, app/…/SentrySetup.kt — তিনটাতেই একই নিয়ম, টেস্টসহ।)

**যাচাই:** Actions → **Sentry test** → Run workflow (main)। প্রতিটা DSN-এ সরাসরি একটা পরীক্ষার ঘটনা (HTTP 200 = Sentry নিয়েছে),
সার্ভারের লগে `sentry initialised: enabled=true …` আর সার্ভারের নিজের SDK দিয়ে একটা ঘটনা, ওয়েবে Chromium দিয়ে
`https://lifolo.com/#sentry-test` (console-এ `[sentry] initialised`, Sentry-তে POST 200)। ফল `ci-log-sentry` শাখায়।
ফোনে: সেটিংস → "ভার্সন"-এ ৭ বার চাপুন → Toast-এ ঘটনার id। Sentry-র Issues-এ "test event" দেখা যাবে — দেখে মুছে (Resolve) দিন।
