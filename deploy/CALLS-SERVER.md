# অ্যাপের ভেতরের কল — LiveKit সার্ভার

অ্যাপের "অ্যাপে কল" (অডিও/ভিডিও) আর ডাক্তারের অনলাইন চেম্বারের ভিডিও কল চলে **LiveKit**-এ — নিজেদের সার্ভারে বসানো
(self-hosted, বিনা পয়সার সফটওয়্যার)। কোনো রেকর্ডিং নেই। ফোনের সাধারণ কল-বোতাম যেমন ছিল তেমনই থাকে (জরুরি অবস্থায় সেটাই আগে)।

দুটো পর্ব:

| পর্ব | কোথায় | কখন |
|---|---|---|
| **এখন — পরীক্ষার পর্ব** | মূল VPS-এই (API-র পাশে) | এখনই — নতুন কিছু কিনতে বা সেট করতে হয় না |
| **পরে — আলাদা কল-সার্ভার** | ছোট আলাদা Contabo VPS, Singapore | public release-এর আগে, Singapore-এ সরার সময় |

---

## ১) এখন: মূল VPS-এ (পরীক্ষার পর্ব)

### আপনার কাজ (২টা ধাপ, ৫-১০ মিনিট)

নতুন **কোনো সিক্রেট লাগবে না, DNS-এ কিছু করতে হবে না**।

1. GitHub → **Actions** → বাঁ দিকে **Setup calls (LiveKit)** → **Run workflow** → `target` = **main** রেখে সবুজ **Run workflow**।
   - শেষে পাতার নিচে বাংলা সারাংশ: ✅ হলে কল-সার্ভার চালু, আর দুটো "নকল ফোন" (headless Chromium) সত্যিকারের কল করে দেখেছে —
     সরাসরি, আর একবার শুধু TURN দিয়ে।
   - ❌ হলে লাল ✗ ধাপটা খুলে স্ক্রিনশট Claude-কে পাঠান। আবার চালালে ক্ষতি নেই।
2. তারপর Actions → **Deploy backend** একবার চালান (নতুন অ্যাপ-কোড এলে যেমন চালান) — তখন অ্যাপ কল-সার্ভারের ঠিকানা আর চাবি পায়।

Cloudflare-এ কিছু বদলাতে হবে না। (Network → **WebSockets** চালু থাকতে হয় — Cloudflare-এ এটা আগে থেকেই চালু থাকে।)

> ⚠️ **VPS_ROOT_PASSWORD সিক্রেট বদলাবেন না** — কল-সার্ভারের চাবিও ওই পাসওয়ার্ড থেকে বানানো (SSH আর ব্যাকআপের চাবির মতোই)।
> বদলালে আবার "Setup calls" আর "Deploy backend" দুটোই চালাতে হবে।

### কী বসানো হয়

| কী | বিবরণ |
|---|---|
| LiveKit | সংস্করণ পিন করা (`deploy/setup-call-server.sh`-এর শুরুতে), GitHub release থেকে sha256 মিলিয়ে; systemd সার্ভিস `livekit-server` |
| কথাবার্তার পথ (signaling) | `wss://lifolo.com/rtc` → Cloudflare → nginx → LiveKit (`127.0.0.1:7880`), WebSocket, ১ ঘণ্টা পর্যন্ত খোলা; টোকেন nginx-এর লগে যায় না |
| ছবি/শব্দের পথ (media) | সরাসরি VPS-এর IP-তে — **7881/tcp** (UDP বন্ধ থাকলে), **3478/udp** (TURN), **50000-60000/udp** (মিডিয়া) |
| ফায়ারওয়াল | ওপরের তিনটা সবার জন্য খোলা; **80/443 আগের মতোই শুধু Cloudflare-এর জন্য** |
| সীমা | LiveKit সর্বোচ্চ CPU-র এক-চতুর্থাংশ (কমপক্ষে আধা কোর, বেশি হলে ২ কোর) আর **512 MB RAM**; ভিড়ে API আগে CPU পায় — কল কখনো অ্যাপকে ধীর করবে না। প্রতি ঘরে (কলে) **সর্বোচ্চ ২ জন** |
| চাবি | LiveKit-এর API key/secret `VPS_ROOT_PASSWORD` থেকে বানানো (`deploy/ci/livekit-keys.js`); VPS-এ `/etc/livekit/keys.yaml` আর অ্যাপের জন্য `/etc/livekit/backend.env` |
| অ্যাপের সেটিং | `LIVEKIT_URL=wss://lifolo.com`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` — Deploy backend এগুলো app.env-এ বসায় (অ্যাপ নিজে `/rtc` যোগ করে) |
| পাহারা | প্রতি ৩ ঘণ্টার **Uptime check**: `wss://lifolo.com/rtc` টোকেন দিয়ে, বাইরে থেকে TURN/UDP 3478 আর 7881 — সমস্যা হলে ইমেইল |

### সীমাবদ্ধতা (পরীক্ষার পর্বে জেনেশুনে মেনে নেওয়া)

- **দেরি বেশি:** মূল VPS ইউরোপে। বাংলাদেশ থেকে যাওয়া-আসায় ~১৫০-২৫০ ms — কথায় সামান্য দেরি টের পাওয়া যেতে পারে।
  Singapore-এ গেলে এটা অনেক কমবে (~৫০-৮০ ms)।
- **সার্ভারের IP দেখা যায়:** মিডিয়া সরাসরি IP-তে যায়, তাই কলে থাকা কেউ চাইলে মূল VPS-এর IP জানতে পারে (Cloudflare সেটা লুকায় না)।
  এটা মেনে নেওয়া হয়েছে কারণ Singapore-এ সরার সময় এই VPS বাদ যাবে। (80/443 তবু শুধু Cloudflare-এর জন্য খোলা।)
- **TURN/TLS নেই:** খুব কড়া নেটওয়ার্কে (যেমন কিছু অফিস/হোটেলের Wi-Fi, যেখানে UDP আর 443 ছাড়া সব বন্ধ) কল না-ও লাগতে পারে।
  সাধারণ মোবাইল ডেটা আর বাসার Wi-Fi-তে সমস্যা হওয়ার কথা নয় (TURN/UDP আর 7881/tcp আছে)। না লাগলে অ্যাপ ফোন-কলের বোতাম দেখায়।
  আলাদা কল-সার্ভারে TURN/TLS থাকবে (নিচে)।
- **ঘরে ২ জন:** ১:১ কল। ডাক্তারের সহকারী একই কলে ৩য় জন হিসেবে ঢুকতে পারবেন না (সহকারী নিজে কল করলে সেটা আলাদা ১:১ কল)।
  দরকার হলে `setup-call-server.sh`-এ `max_participants` বাড়ানো যায়।

---

## ২) পরে: আলাদা কল-সার্ভার (Singapore, public release-এর আগে)

সব কিছু তৈরি আছে (`--mode dedicated`, CI-তে প্রতিবার পরীক্ষা হয়) — শুধু নিচের ধাপগুলো।

### আপনার কাজ

1. **Contabo-তে VPS কিনুন:** সবচেয়ে ছোট **Cloud VPS** (এখনকার নাম "Cloud VPS 10") যথেষ্ট — শুরুতে কয়েক ডজন একসাথে ভিডিও কলের জন্য।
   - Region: **Singapore** (না থাকলে মূল VPS যেখানে, সেখানে)। Asia region-এ মাসিক দাম কিছুটা বেশি হতে পারে।
   - Image: **Ubuntu 24.04**। একটা শক্ত root পাসওয়ার্ড দিন আর password manager-এ রাখুন।
2. **GitHub সিক্রেট দুটো** (Settings → Secrets and variables → Actions → New repository secret):
   - `CALL_VPS_HOST` — নতুন VPS-এর IP
   - `CALL_VPS_ROOT_PASSWORD` — ওই root পাসওয়ার্ড, হুবহু
3. **Cloudflare DNS** (lifolo.com → DNS → Add record) — **দুটো** A রেকর্ড, দুটোই **DNS only (ধূসর মেঘ)**:
   - `call` → নতুন VPS-এর IP
   - `turn` → নতুন VPS-এর IP
   > কমলা মেঘ (Proxied) দিলে কল চলবে না — WebRTC-র মিডিয়া Cloudflare-এর proxy দিয়ে যায় না।
4. Actions → **Setup calls (LiveKit)** → Run workflow → `target` = **dedicated**। DNS ছড়াতে কয়েক মিনিট লাগে — সার্টিফিকেট
   না পেলে ১৫ মিনিট পর আবার চালান।
5. Actions → **Deploy backend** — `CALL_VPS_ROOT_PASSWORD` থাকলে অ্যাপ নিজে থেকেই `wss://call.lifolo.com`-এ চলে যায়।
6. পরে মূল VPS বদলানোর সময় পুরোনো LiveKit-ও চলে যাবে; তার আগে বন্ধ করতে চাইলে Claude-কে বলুন।

### আলাদা কল-সার্ভারে যা আলাদা

| কী | বিবরণ |
|---|---|
| ভিত্তি | unattended-upgrades, fail2ban, ufw, journald-সীমা; প্রথম সংযোগে root পাসওয়ার্ড → GitHub-এর চাবি → পাসওয়ার্ড লগইন বন্ধ (মূল VPS-এর মতোই) |
| নাম | `wss://call.lifolo.com` (signaling), `turns:turn.lifolo.com:443` (TURN/TLS) |
| 443 | nginx নাম দেখে (SNI) ভাগ করে: `turn.lifolo.com` → LiveKit-এর TURN/TLS, বাকি → signaling |
| সার্টিফিকেট | Let's Encrypt (দুটো নামেই) — শুধু DNS ঠিক এই VPS-এ এলে; নিজে নবায়ন; নতুন সার্টিফিকেট LiveKit রাতে (ঢাকা ~৩:৩০) নেয় |
| ফায়ারওয়াল | 22, 80, 443/tcp; 7881/tcp; 3478/udp; 50000-60000/udp |
| সীমা | পুরো VPS কলের — CPU/RAM-সীমা নেই; ঘরে ২ জন |
| পাহারা | Uptime check: call.lifolo.com/healthz, টোকেন দিয়ে /rtc, দুটো নামের সার্টিফিকেট (< ২১ দিন বাকি হলে ❌), DNS, TURN/UDP |

### ব্যান্ডউইথ (মোটামুটি হিসাব)

সার্ভার প্রতিটা কলে দুজনের কাছ থেকে নেয় আর অন্যজনকে পাঠায়:

| কল | সার্ভারের মোট ট্রাফিক | ৩০ মিনিটে |
|---|---|---|
| শুধু অডিও | ~০.২ Mbit/s | ~৪০-৫০ MB |
| ভিডিও (মোবাইলের মাঝারি মান) | ~২-৪ Mbit/s | ~০.৫-১ GB |

- ছোট Contabo VPS-এর পোর্ট সাধারণত ~২০০ Mbit/s আর মাসে কয়েক TB ট্রাফিক (প্ল্যানের পাতায় দেখে নিন) — মানে একসাথে
  ~৪০-৮০টা ভিডিও কল বা কয়েকশো অডিও কল; মাসের ট্রাফিক-সীমায় পৌঁছাতে দিনে হাজারের বেশি ভিডিও-মিনিট লাগবে।
- পরীক্ষার পর্বে (মূল VPS) একই হিসাব, তবে CPU/RAM-সীমার কারণে আর API-র ট্রাফিকের সাথে ভাগ — পরীক্ষার জন্য যথেষ্ট।

### বড় করা (scale)

1. **আগে বড় প্ল্যান:** Contabo-তে একই VPS-এর প্ল্যান বাড়ানো (বেশি CPU/RAM/পোর্ট) — কিছু বদলাতে হয় না, আবার Setup চালানোও লাগে না।
2. **তারপর একাধিক সার্ভার:** কয়েকশো একসাথে কল হলে কয়েকটা LiveKit সার্ভার + Redis (একই ঘর এক সার্ভারে রাখতে) — তখন আলাদা কাজ;
   Claude-কে বলুন। এক সার্ভারে Redis লাগে না।

---

## যাচাই আর সমস্যা হলে

- **শুধু পরীক্ষা:** Actions → Setup calls (LiveKit) → `check_only` টিক দিয়ে Run — কিছু বদলায় না, বাইরে থেকে পোর্ট আর আসল কল পরীক্ষা করে।
- **প্রতি ৩ ঘণ্টা:** Uptime check-এর সারাংশে "অ্যাপের কল (LiveKit)" অংশ।
- **CI:** `Calls server CI` — `deploy/setup-call-server.sh` বা কলের পরীক্ষা বদলালে GitHub-এর মেশিনে দুই মোডেই বসিয়ে আসল কল করে দেখে।
- VPS-এ হাতে (Contabo VNC কনসোল, root): `systemctl status livekit-server`, `journalctl -u livekit-server -n 100`,
  `cat /var/lib/lifolo/call-status`।

| সমস্যা | কারণ / কী করবেন |
|---|---|
| কল লাগে না, "সংযোগ হচ্ছে…" | Setup calls (check_only) চালান — signaling ❌ হলে nginx/Cloudflare, TURN ❌ হলে ফায়ারওয়াল |
| কল লাগে কিন্তু ছবি/শব্দ নেই | ব্যবহারকারীর নেটওয়ার্কে UDP বন্ধ — পরীক্ষার পর্বে TURN/TLS নেই (ওপরে সীমাবদ্ধতা); আলাদা কল-সার্ভারে ঠিক হবে |
| অ্যাপ বলে কল বন্ধ / LIVEKIT_URL নেই | Setup calls-এর পর Deploy backend চালানো হয়নি |
| আলাদা সার্ভারে সার্টিফিকেট পাওয়া যায়নি | DNS: `call`/`turn` → ঠিক IP, **ধূসর মেঘ**; ১৫ মিনিট পর আবার চালান |

---

## কারিগরি (Claude/ডেভেলপারের জন্য)

- ফাইল: `deploy/setup-call-server.sh` (দুই মোড), `.github/workflows/setup-call-server.yml` (Setup calls), `.github/workflows/call-server-ci.yml`,
  `deploy/ci/livekit-keys.js` (key/secret/টোকেন, scrypt লেবেল `livekit-api`), `deploy/ci/stun-check.py`, `deploy/ci/call-smoke/` (Playwright +
  livekit-client; `iceTransportPolicy=relay` আর TURN-এর ধরন ছেঁকে relay জোর করা), uptime.yml-এর "Calls (LiveKit)" ধাপ।
- Deploy backend-এর সাথে যোগ (`deploy/deploy-backend.sh` + `deploy-backend.yml`, ছোট আলাদা commit):
  - `deploy-backend.sh`: অ্যাপের `/etc/nginx/snippets/lifolo-api.conf`-এ এক লাইন `include /etc/nginx/snippets/lifolo-calls*.conf;`
    (ফাইল না থাকলে কিছু হয় না — glob), আর app.env-এ LIVEKIT_URL/LIVEKIT_API_KEY/LIVEKIT_API_SECRET: workflow দিলে সেটা
    (আলাদা কল-সার্ভার), নইলে VPS-এর `/etc/livekit/backend.env` থাকলে সেখান থেকে (মূল VPS), কোনোটাই না থাকলে কিছু না।
  - `deploy-backend.yml`: `CALL_VPS_ROOT_PASSWORD` সিক্রেট থাকলেই
    `CALL_DOMAIN=call.lifolo.com LIFOLO_MASTER="$CALL_VPS_ROOT_PASSWORD" node deploy/ci/livekit-keys.js --mask --env provided.env`।
  - তাই কল-সার্ভার বসানোর আগে deploy আগের মতোই চলে (LIVEKIT_* থাকে না)।
- ক্লায়েন্ট `LIVEKIT_URL`-এর সাথে নিজে `/rtc` (নতুন SDK-তে `/rtc/v1`, `/rtc/validate`) যোগ করে — তাই URL-এ পথ নেই, nginx `/rtc` আর `/rtc/…` LiveKit-এ পাঠায়।
- LiveKit TURN/TLS-এর ঠিকানা সবসময় `turns:<turn.domain>:443` ঘোষণা করে — তাই আলাদা সার্ভারে TLS 443-এ (nginx stream, SNI), ভেতরে 5349।
- মূল VPS-এ TURN/UDP-এর ঠিকানা `turn:<VPS-IP>:3478` (node_ip); relay-র পোর্ট (30000-40000) শুধু মেশিনের ভেতরে, ফায়ারওয়ালে খোলা নয়।
- LiveKit সংস্করণ বদলাতে: `LK_VERSION` আর দুটো `LK_SHA256_*` (release-এর `checksums.txt`) একসাথে → CI সবুজ → Setup calls চালান।
