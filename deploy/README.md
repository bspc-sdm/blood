# Lifolo সার্ভার VPS-এ চালানো (Vercel ছাড়া)

এই গাইড মেনে চললে Lifolo-র সার্ভার (API, ওয়েব, অ্যাডমিন প্যানেল, দৈনিক ক্রন) একটা Contabo VPS-এ চলবে।
Firebase লগইন আর নোটিফিকেশন (FCM) আগের মতোই থাকবে। প্রতিটা কমান্ড হুবহু কপি করে চালান।
`<...>` চিহ্নের জায়গায় নিজের মান বসান।

> `deploy/setup-vps.sh` আর `server/standalone/` main শাখায় আসার (merge) পরেই নিচের ধাপগুলো করুন।

---

## ১. VPS কেনা

- **কোথায়:** [contabo.com](https://contabo.com) → **Cloud VPS** → **Cloud VPS 10** (সবচেয়ে ছোট প্ল্যান)।
  এই সার্ভার আর ডাটাবেসের জন্য এটাই যথেষ্ট। বড় প্ল্যান এখন শুধু বেশি খরচ, পরে এক ক্লিকে বাড়ানো যায়।
- **Region:** **Asia (Singapore)**। বাংলাদেশ থেকে সবচেয়ে কাছে, তাই অ্যাপ দ্রুত চলবে।
  ইউরোপ/আমেরিকা সস্তা হলেও সেখানে প্রতিটা অনুরোধে প্রায় ২০০ ms বেশি সময় লাগে।
- **Image (OS):** **Ubuntu 24.04**। স্ক্রিপ্টটা এই ভার্সনেই পরীক্ষা করা।
- **Login:** "Password" বেছে root-এর জন্য শক্ত একটা পাসওয়ার্ড দিন, আর সেটা লিখে রাখুন।
- কেনার পর ইমেইলে VPS-এর **IP ঠিকানা** আসবে (যেমন `203.0.113.10`)।

## ২. ডোমেইনকে VPS-এর দিকে পাঠানো

যেখান থেকে ডোমেইন কিনেছেন, সেখানকার DNS সেটিংয়ে একটা রেকর্ড যোগ করুন:

| Type | Name (Host) | Value | TTL |
|---|---|---|---|
| A | `api` (বা যে সাবডোমেইন চান; পুরো ডোমেইনের জন্য `@`) | VPS-এর IP | 300 |

এতে ঠিকানা হবে `api.<আপনার-ডোমেইন>`। ৫ থেকে ৩০ মিনিট অপেক্ষা করুন, তারপর নিজের কম্পিউটারে দেখুন:

```
nslookup api.<আপনার-ডোমেইন>
```

উত্তরে VPS-এর IP দেখালে পরের ধাপে যান।

## ৩. প্রথমবার VPS-এ লগইন (SSH)

Windows-এ **PowerShell** খুলুন (Mac/Linux হলে Terminal), তারপর চালান:

```
ssh root@<VPS-এর-IP>
```

প্রথমবার `yes` লিখে Enter দিন, তারপর root-এর পাসওয়ার্ড দিন। পাসওয়ার্ড লেখার সময় স্ক্রিনে কিছু দেখা যায় না, এটাই স্বাভাবিক।

## ৪. সেটআপ স্ক্রিপ্ট চালানো

VPS-এ লগইন করা অবস্থায় চালান:

```
curl -fsSLO https://raw.githubusercontent.com/bspc-sdm/blood/main/deploy/setup-vps.sh
bash setup-vps.sh api.<আপনার-ডোমেইন> <আপনার-ইমেইল>
```

এতে ৫ থেকে ১০ মিনিট লাগে। স্ক্রিপ্ট যা যা করে:

- Node, PostgreSQL, nginx আর ফায়ারওয়াল বসায়
- নিরাপত্তা আপডেট নিজে নিজে চালু করে
- কোড `/opt/lifolo`-এ রাখে
- সার্ভার, দৈনিক ক্রন (সকাল ৯টা, ঢাকা) আর দৈনিক ব্যাকআপ চালু করে
- HTTPS সার্টিফিকেট নেয়

শেষে একটা বক্সে GitHub-এর জন্য কিছু মান দেখায়। ওই লেখাটা রেখে দিন (৬ নম্বর ধাপে লাগবে)।

যদি "সার্টিফিকেট পাওয়া যায়নি" দেখায়, তাহলে DNS এখনো পৌঁছায়নি। ১৫ মিনিট পর একই কমান্ড আবার চালান।
স্ক্রিপ্টটা যতবার খুশি চালানো যায়, এতে কোনো ক্ষতি হয় না।

## ৫. গোপন সেটিং বসানো (`/etc/lifolo/env`)

**মানগুলো কোথায় পাবেন:** Vercel → [vercel.com/dashboard](https://vercel.com/dashboard) → সার্ভারের প্রজেক্ট
(`blood-server`) → **Settings** → **Environment Variables**। প্রতিটা নামের পাশের চোখের চিহ্নে চাপলে মান দেখা যায়।

VPS-এ ফাইলটা খুলুন:

```
nano /etc/lifolo/env
```

প্রতিটা লাইনে `=` এর পরে মান বসান:

| নাম | কী বসাবেন |
|---|---|
| `FIREBASE_SERVICE_ACCOUNT` | নিচের নোট দেখুন |
| `ADMIN_EMAILS` | Vercel-এর মান হুবহু |
| `GMAIL_USER`, `GMAIL_APP_PASSWORD` | Vercel-এর মান হুবহু |
| `SMS_URL_TEMPLATE` | Vercel-এর মান, দুপাশে `'` দিয়ে, যেমন `SMS_URL_TEMPLATE='https://...&number={to}&message={msg}'` |
| `CRON_SECRET`, `WEB_SESSION_SECRET` | স্ক্রিপ্ট নিজেই বসিয়ে দিয়েছে। Vercel-এ এগুলো থাকলে Vercel-এর মান দিয়ে বদলে দিন। |

**FIREBASE_SERVICE_ACCOUNT:** এই মানটা এক লাইনে থাকতে হবে।
1. Vercel-এর মান কপি করে নিজের কম্পিউটারে `key.json` নামে সেভ করুন।
2. Vercel-এ মান লুকানো ("Sensitive") থাকলে নতুন চাবি নিন: Firebase Console → ⚙️ Project settings → **Service accounts** → **Generate new private key**।
3. তারপর PowerShell-এ চালান:
   ```
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("key.json"))
   ```
   (Mac/Linux-এ চালান `base64 -w0 key.json`)
4. যে লম্বা লেখাটা আসে, সেটা `FIREBASE_SERVICE_ACCOUNT=` এর পরে পেস্ট করুন।

অন্য কোনো মান লুকানো থাকলে মূল জায়গা থেকে নতুন করে নিন:
- Gmail App Password: Google Account → Security → App passwords
- SMS লিংক: SMS গেটওয়ের ড্যাশবোর্ড

`DATABASE_URL` লাইনটা যেমন আছে (`#` সহ) তেমনই রাখুন। ডাটা সরানোর পরের ধাপে এটা চালু হবে।

সেভ করে বেরোতে `Ctrl+O`, তারপর `Enter`, তারপর `Ctrl+X` চাপুন। এরপর সার্ভার আবার চালু করুন:

```
systemctl restart lifolo
```

## ৬. GitHub-এ সিক্রেট যোগ করা (নিজে নিজে ডেপ্লয়)

GitHub → `bspc-sdm/blood` → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**।
৪ নম্বর ধাপের শেষে যে মানগুলো দেখিয়েছিল, সেগুলো দিন:

| Name | Value |
|---|---|
| `VPS_HOST` | VPS-এর IP |
| `VPS_USER` | `lifolo` |
| `VPS_SSH_KEY` | VPS-এ `cat /root/lifolo-deploy-key` চালিয়ে পুরো লেখাটা (`-----BEGIN` থেকে `END ... KEY-----` পর্যন্ত) |
| `VPS_KNOWN_HOSTS` | স্ক্রিপ্টের দেখানো লাইন (`<IP> ssh-ed25519 AAAA...`) |

কপি করা হয়ে গেলে VPS থেকে চাবিটা মুছে দিন:

```
rm /root/lifolo-deploy-key
```

এরপর থেকে main-এ `server/` বদলালে GitHub নিজেই টেস্ট চালায়, তারপর VPS-এ ডেপ্লয় করে।
হাতে চালাতে চাইলে: GitHub → **Actions** → **Deploy server (VPS)** → **Run workflow**।

## ৭. Firebase-এ নতুন ডোমেইনের অনুমতি

ওয়েবে লগইন কাজ করার জন্য এটা দরকার।
Firebase Console → **Authentication** → **Settings** → **Authorized domains** → **Add domain** → `api.<আপনার-ডোমেইন>` লিখে যোগ করুন।

## ৮. চলছে কি না দেখা

ব্রাউজারে খুলুন:
- `https://api.<আপনার-ডোমেইন>/healthz` → `{"ok":true,...}` দেখালে সার্ভার চালু আছে।
- `https://api.<আপনার-ডোমেইন>/api/health` → `{"ok":true,"firebase":true}` দেখালে Firebase-এর সাথেও যুক্ত।
- `https://api.<আপনার-ডোমেইন>/` → Lifolo ওয়েব, আর `/admin` → অ্যাডমিন প্যানেল।

VPS-এ:

```
systemctl status lifolo --no-pager          # active (running) দেখাবে
journalctl -u lifolo -n 100 --no-pager      # শেষ ১০০ লাইন লগ (ভুল থাকলে এখানে)
systemctl list-timers 'lifolo*' --no-pager  # পরের ক্রন আর ব্যাকআপ কখন
journalctl -u lifolo-cron -n 20 --no-pager  # শেষ দৈনিক ক্রনের ফল
```

ক্রন এখনই একবার চালিয়ে দেখতে চাইলে: `systemctl start lifolo-cron`

## ৯. ব্যাকআপ আর ফেরত আনা (restore)

প্রতিদিন রাত ৩:৩০-এ (ঢাকা) PostgreSQL-এর ব্যাকআপ `/var/backups/lifolo`-এ জমা হয়। ১৪ দিনের পুরোনোগুলো নিজে মুছে যায়।
(ব্যাকআপ শুধু PostgreSQL-এর। ডাটা Firestore থেকে PostgreSQL-এ সরানোর পর থেকে এটা কাজে লাগবে।)

```
ls -lh /var/backups/lifolo                 # কোন কোন ব্যাকআপ আছে
systemctl start lifolo-backup              # এখনই একটা নতুন ব্যাকআপ
```

একটা ব্যাকআপ ফেরত আনতে:

```
systemctl start lifolo-backup              # ১) এখনকার অবস্থারও একটা ব্যাকআপ (ভুল হলে ফেরার পথ)
systemctl stop lifolo                      # ২) সার্ভার থামানো
sudo -u postgres pg_restore --clean --if-exists --no-owner --role=lifolo \
  -d lifolo /var/backups/lifolo/lifolo-<তারিখ-সময়>.dump
systemctl start lifolo                     # ৩) আবার চালু
```

`<তারিখ-সময়>` বসাবেন `ls`-এ দেখা ফাইলের নাম থেকে, যেমন `lifolo-20261020-213000.dump`।

ব্যাকআপ নিজের কম্পিউটারেও রাখতে চাইলে PowerShell-এ চালান:

```
scp root@<VPS-এর-IP>:/var/backups/lifolo/lifolo-<তারিখ-সময়>.dump .
```

---

**কোড হাতে আপডেট করা** (GitHub ছাড়া):

```
sudo -u lifolo git -C /opt/lifolo pull
systemctl restart lifolo
```
