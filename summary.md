## ✅ ব্যাকএন্ড চালু হয়েছে

ঠিকানা: **https://169-58-58-176.sslip.io/api/health** · jar `68134af1a729` (152M) · commit `0fc0a44` (main)

| পরীক্ষা | ফল | বিস্তারিত |
|---|---|---|
| অ্যাপ চালু (/api/health, VPS-এর ভেতরে) | ✅ | up · app-68134af1a729.jar (previous app-18d3d2c54004.jar) |
| বিরতি ছাড়া switch (blue/green) | ✅ | bluegreen: 8080 → 8081 |
| switch-এর সময় 5xx (/api/health + API action, VPS থেকে) | ✅ | requests=98 2xx=98 5xx=0 conn_errors=0 |
| JobRunr শুধু নতুন instance-এ | ✅ | running on :8081 (old stopped at switch) |
| এখন একটাই instance চালু | ✅ | ok |
| ভেতরের স্থির ঠিকানা 127.0.0.1:8088 (cron, Sentry test, webhook) | ✅ | ok |
| LiveKit webhook → 127.0.0.1:8088 | ✅ | ok |
| ডাটাবেস migration (Flyway) | ✅ | 37 migrations, latest V693 |
| nginx: /api/ /r/ /rx/ /shop/ /.well-known/ → অ্যাপ | ✅ | ok |
| https://169-58-58-176.sslip.io/api/health (VPS থেকে) | ✅ | ok |
| https://169-58-58-176.sslip.io/api/health (ইন্টারনেট থেকে) | ✅ | yes |
| https://api.lifolo.com — সার্টিফিকেট + nginx | ✅ | ok |
| https://api.lifolo.com/api/health (VPS থেকে) | ✅ | ok |
| https://api.lifolo.com/api/health (ইন্টারনেট থেকে) | ✅ | yes |
| /healthz আগের মতোই | ✅ | ok |
| nginx → অ্যাপ: খোলা সংযোগ রাখা (upstream keepalive 64) | ✅ | ok |
| /api/-এ IP-প্রতি সীমা (২০/সে, burst ৪০) → 429 JSON | ✅ | 20r/s burst 40 per client IP · পরীক্ষা: 200=54 429=26 |
| /.well-known/assetlinks.json | ✅ | ok |
| /r/… শেয়ার পাতা অ্যাপ থেকে | ✅ | ok |
| অতিথির API (/api/community) | ✅ | ok |
| JobRunr dashboard বাইরে বন্ধ | ✅ | ok |
| /actuator বাইরে বন্ধ | ✅ | ok |
| /api/docs (OpenAPI) বাইরে বন্ধ | ✅ | ok |
| SES-এর চাবি (ইমেইল না পাঠিয়ে) | ✅ | ok |
| অ্যাপে SES client চালু | ✅ | ok |
| ডেমো বানানো (গাজীপুর, API দিয়ে) | ✅ | kept (আগে থেকেই চালু) |
| ডেমো লগইন + পড়া (https://api.lifolo.com, ইন্টারনেট থেকে) | ✅ | ok (10 রোল, patient লগইন + 4 পড়া, pharmacy লগইন + 2 পড়া, doctor লগইন + 1 পড়া) |
| Firebase-এর ডেমো অ্যাকাউন্ট রাখা (Vercel-এর ডেমোর জন্য) | ✅ | DEMO_KEEP_FIREBASE_USERS=true |

**ওয়েবসাইট — https://lifolo.com** (Vue ওয়েব /w/, অ্যাডমিন /admin/, /api → এই সার্ভার)

| পরীক্ষা | ফল | বিস্তারিত |
|---|---|---|
| ওয়েব বিল্ড (web-vue) | ✅ | 2.8M |
| https://lifolo.com — সার্টিফিকেট + nginx | ✅ | রিলিজ 20261023-231919-236f1533 (আগেরটা 20261023-213419-1c8df252) |
| https://www.lifolo.com → https://lifolo.com | ✅ | ok |
| VPS-এর ভেতরের পথ/হেডার পরীক্ষা (ডাউনলোড, /privacy, /terms সহ) | ✅ | ok |
| সরাসরি VPS-এ (আসল নাম + সার্টিফিকেট): পাতা (/) | ✅ | ok |
| ইন্টারনেট থেকে: /api/health একই ঠিকানায় | ✅ | ok |
| ইন্টারনেট থেকে: /w/assets/ এক বছর cache | ✅ | ok |
| ইন্টারনেট থেকে: /admin/ কড়া CSP | ✅ | ok |
| ইন্টারনেট থেকে: /privacy আর /terms | ✅ | ok |
| আসল পথে (Cloudflare দিয়ে) /api/health | ✅ | ok (cloudflare) |
| ইন্টারনেট থেকে: www → lifolo.com | ✅ | ok |
| Cloudflare-এর আসল IP (CF-Connecting-IP) | ✅ | ok (tune-vps.sh) |
| Cloudflare-এর proxy চালু (কমলা মেঘ) | ✅ | সার্টিফিকেট (VPS) মেয়াদ: Jan 18 00:00:16 2027 GMT |
| Let's Encrypt-এর নবায়নের পথ (Cloudflare দিয়ে /.well-known/acme-challenge/) | ✅ | ok |

**VPS-এর সেটিং (tune-vps.sh):**

| কী | ফল | বিস্তারিত |
|---|---|---|
| JVM (-Xms1g -Xmx3g, G1) | ✅ | same |
| PostgreSQL | ✅ | effective_cache_size=6GB max_connections=100 shared_buffers=2GB work_mem=8MB (ok) |
| Redis | ✅ | 536870912 volatile-lru |
| nginx (worker_connections 4096, nofile 16384) | ✅ | ok (conf=no unit=no) |
| origin: 80/443 শুধু Cloudflare (ufw), 22 খোলা | ✅ | cloudflare-only (22 rules, v6=yes; changed=no) · তালিকা: fetched (22 nets) · guard timer: active |
| Cloudflare → origin (VPS থেকে যাচাই) | ✅ | ok |

শেয়ার লিংক/QR-এর ঠিকানা (PUBLIC_BASE_URL): `https://lifolo.com`

**সেটিং:**
- ✅ Firebase (লগইন আর পুশ) চালু।
- ✅ ইমেইল (Amazon SES) চালু।
- ⚠️ SES সম্ভবত এখনো sandbox-এ — শুধু যাচাই করা ঠিকানায় ইমেইল যায়। AWS-এ production access অনুমোদনের পর সবার কাছে যাবে।
- R2 (ছবি): চালু · Sentry: চালু
- JobRunr dashboard সবসময় বন্ধ; অ্যাপ শুধু 127.0.0.1:8080/8081-এ শোনে (blue/green, একটাই live), বাইরে থেকে nginx দিয়েই।
- ২.৩৪.০ থেকে ফোনের অ্যাপ এই সার্ভারে (https://lifolo.com)। পুরোনো সংস্করণের অ্যাপ এখনো Vercel-এ যায় (Vercel চালু রাখা) — বাকি ধাপ: deploy/CUTOVER.md
