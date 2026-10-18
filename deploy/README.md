# Lifolo সার্ভার নিজের VPS-এ (কোনো কমান্ড লিখতে হয় না)

সব কাজ GitHub-এর বোতাম চেপে হয়। আপনার কাজ শুধু:
**Contabo VPS নেওয়া → Vercel টোকেন বানানো → GitHub-এ ৩টা সিক্রেট দেওয়া → "Run workflow" চাপা।**

VPS: শুধু **Contabo**, Ubuntu 24.04, root পাসওয়ার্ড দিয়ে।

> ⚠️ পাসওয়ার্ড, চাবি বা টোকেন কখনো চ্যাটে (Claude-সহ কাউকে) পাঠাবেন না। শুধু GitHub-এর সিক্রেটে বসাবেন।

---

## ক. Contabo

1. **কেনা:** [contabo.com](https://contabo.com) → **Cloud VPS** → **Cloud VPS 6** (100 GB NVMe)।
   - Region: **Asia (Singapore)**
   - Image: **Ubuntu 24.04**
   - Password: root-এর জন্য একটা শক্ত পাসওয়ার্ড দিন। কাগজে বা পাসওয়ার্ড ম্যানেজারে লিখে রাখুন।
   - অর্ডার করে টাকা দিন। ১০ থেকে ৩০ মিনিটে VPS চালু হয়।
2. **IP খোঁজা:** [my.contabo.com](https://my.contabo.com) → বাঁ দিকে **VPS control** → আপনার VPS-এর সারিতে **IP Address**
   (যেমন `203.0.113.10`)। Contabo-র ইমেইলেও থাকে।
3. এরপর **অংশ খ**।

## খ. Vercel টোকেন

[vercel.com](https://vercel.com)-এ লগইন → ওপরে ডানে নিজের ছবি → **Account Settings** → বাঁ দিকে **Tokens**
→ Token Name: `lifolo-vps` → Scope: যে টিমে `blood-server` প্রজেক্ট আছে → Expiration: **30 days** → **Create**
→ যে লম্বা লেখাটা দেখায় সেটা কপি করুন (একবারই দেখায়)।

## গ. GitHub-এ সিক্রেট

[github.com/bspc-sdm/blood](https://github.com/bspc-sdm/blood) → ওপরে **Settings** → বাঁ দিকে
**Secrets and variables** → **Actions** → সবুজ **New repository secret** → Name আর Secret দিয়ে **Add secret**।
প্রতিটার জন্য আলাদা করে:

| Name (হুবহু) | মান |
|---|---|
| `VPS_HOST` | VPS-এর IP |
| `VPS_ROOT_PASSWORD` | Contabo-তে দেওয়া root পাসওয়ার্ড |
| `VERCEL_TOKEN` | খ অংশের টোকেন |

## ঘ. Run workflow

[github.com/bspc-sdm/blood](https://github.com/bspc-sdm/blood) → ওপরে **Actions** → বাঁ দিকে **Setup VPS**
→ ডানে **Run workflow**:
- Branch: `main`
- domain: **খালি রাখুন**
- সবুজ **Run workflow** চাপুন।

১০ থেকে ২০ মিনিট লাগে। পাতাটা রিফ্রেশ করলে হলুদ বৃত্ত ঘুরতে দেখবেন।

**সফল হলে:** সবুজ ✓ আসে। রানে চাপলে **Summary**-তে লেখা থাকে **✅ সার্ভার চালু: https://…sslip.io**।
সেই ঠিকানার শেষে `/api/health` লাগিয়ে খুললে `{"ok":true,...}` দেখাবে।

**ব্যর্থ হলে:** লাল ✗ আসে। রানে চাপুন → লাল ✗ দেওয়া ধাপে চাপুন → সেই লেখার **স্ক্রিনশট Claude-কে পাঠান**।
Summary-তে কোনো সিক্রেট যোগ করতে বললে (যেমন `GMAIL_APP_PASSWORD`), গ অংশের মতো সেই নামে যোগ করে আবার **Run workflow** চাপুন।
বারবার চালালেও কোনো ক্ষতি হয় না।

---

## এরপর যা নিজে নিজে চলে

- **ডেপ্লয়:** main-এ সার্ভারের কোড বদলালে GitHub নিজেই VPS-এ বসায় (Deploy server (VPS))।
- **ব্যাকআপ:** প্রতি রাতে ৩:৩০-এ ডাটাবেসের ব্যাকআপ হয়। কপি থাকে VPS-এ আর Firebase-এর Cloud Storage-এর `backups/` ফোল্ডারে, শেষ ১৪ দিনের।
  - Summary-তে "Cloud Storage কাজ করছে না" দেখালে: Firebase Console → **Build** → **Storage** → **Get started** চাপুন। তারপর Setup VPS আবার চালান।
- **সার্ভার বন্ধ হলে ইমেইল:** প্রতি ১০ মিনিটে GitHub দেখে। বন্ধ পেলে আপনার GitHub-এর ইমেইলে জানায়।

**যা করবেন না:**
- `VPS_ROOT_PASSWORD` সিক্রেট মুছবেন না, বদলাবেন না। GitHub এটা দিয়েই VPS-এ ঢোকে।
- Contabo প্যানেলে root পাসওয়ার্ড বদলাবেন না।

## পরে লাগবে (Claude বললে)

- **PostgreSQL-এ যাওয়া:** Actions → **Cutover to PostgreSQL** → mode `cutover` → Run workflow।
  - কিছু গোলমাল হলে একই জায়গা থেকে mode `rollback` দিন।
  - শুধু দেখতে mode `status` দিন।
- **ব্যাকআপ ফেরত আনা:** Actions → **Restore backup** → from `cloud`, backup `list` দিলে তালিকা দেখায়।
  - তারপর `latest` বা তালিকার একটা নাম দিয়ে আবার চালালে সেটা ফেরত আসে।
  - VPS পুরো নষ্ট হলে: নতুন VPS → Setup VPS → Restore backup (`cloud`, `latest`)।

---

## কারিগরি নোট (সাহায্যকারীর জন্য)

**ফাইল:**
- `.github/workflows/setup-vps.yml`: Vercel env (REST API) আনে, তারপর `deploy/ci/vps-connect.sh`, `deploy/setup-vps.sh`, `/etc/lifolo/env`, Firebase authorized domain, HTTPS পরীক্ষা।
- `deploy-vps.yml`, `cutover.yml` (`deploy/cutover.sh`), `restore-backup.yml` (`deploy/restore.sh`), `uptime.yml`।

**SSH:** GitHub workflow নিজে সিক্রেট লিখতে পারে না (PAT ছাড়া), আর রিপো public, তাই লগে চাবি ছাপানো যায় না।
তাই `deploy/ci/derive-keys.js` root পাসওয়ার্ড (`VPS_ROOT_PASSWORD`) থেকে scrypt দিয়ে
প্রতিবার একই দুটো ed25519 চাবি বানায়:
- VPS-এর **host key**: `/etc/ssh/lifolo_host_ed25519_key`, `sshd_config.d/00-lifolo.conf`-এ বসানো। GitHub এটাকে পিন করে চেনে।
- root-এর **লগইন চাবি**।

প্রথম সংযোগ trust-on-first-use; আসল host key-এর fingerprint লগে আর Summary-তে থাকে। এরপর
`PasswordAuthentication no` আর `PermitRootLogin prohibit-password` বসে। জরুরি পথ: Contabo প্যানেলের VNC কনসোল।

**ঝুঁকি (জেনে নেওয়া):**
- নিরাপত্তা এখন পুরোটা ওই একটা সিক্রেটের ওপর। পাসওয়ার্ড দুর্বল হলে চাবিও দুর্বল।
  তবে পাসওয়ার্ড লগইন বন্ধ, তাই অনুমান করে মেলাতে হলে প্রতিবার scrypt আর SSH-এর ধাপ পেরোতে হয়, আর fail2ban আছে।
- GitHub-এ যার Actions চালানোর অনুমতি আছে, সে root-এর সমান। আগের আলাদা "শুধু ডেপ্লয়" চাবি আর নেই,
  কারণ সেটা রাখলে বাড়তি সিক্রেট হাতে কপি করতে হতো।

**ফায়ারওয়াল:** ufw — শুধু SSH-এর পোর্ট, 80 আর 443 খোলা। সাথে fail2ban।

**সেটিং:**
- মান বাছাইয়ের ক্রম: GitHub secret (একই নাম) > Vercel (production) > VPS-এ আগে থেকে থাকা।
- `FIREBASE_SERVICE_ACCOUNT` base64 করে রাখা হয়।
- `CRON_SECRET` / `WEB_SESSION_SECRET` Vercel থেকে পড়া না গেলে VPS নিজে বানায়।
- `DATABASE_URL` `#` দিয়ে বন্ধ থাকে, Cutover চালু করে।

**হাতে (VPS-এর কনসোলে, root হিসেবে):**

```
systemctl status lifolo                         # সার্ভার
journalctl -u lifolo -n 100 --no-pager          # লগ
sudo -u lifolo -H /usr/local/sbin/lifolo-deploy # কোড আপডেট
lifolo-restore cloud list                       # Cloud Storage-এর ব্যাকআপ
lifolo-restore cloud latest                     # ফেরত আনা (আগে এখনকার অবস্থার ব্যাকআপ নেয়)
```

**ব্যাকআপ:**
- `lifolo-backup.timer` প্রতিদিন 21:30 UTC-তে চলে: `pg_dump` → `/var/backups/lifolo` (১৪ দিন)।
- সফল হলে `lifolo-backup-upload.service` চলে (`server/standalone/backup-upload.js`):
  `gs://<project>.firebasestorage.app/backups/` (বা `.appspot.com`, বা env-এ `BACKUP_BUCKET`), শেষ ১৪টা রাখে।
- হাতে ফেরত আনা: `node standalone/backup-upload.js download latest /tmp/x.dump` (lifolo হিসেবে, env লোড করে),
  তারপর `sudo -u postgres pg_restore --clean --if-exists --no-owner --role=lifolo -d lifolo < /tmp/x.dump`।
  `lifolo-restore` এই কাজটাই করে।

**অ্যাপ:** অ্যাপ এখনো `blood-server-ashy.vercel.app` ব্যবহার করে। নতুন ঠিকানায় নেওয়া আলাদা ধাপ (অ্যাপের `ServerApi.BASE_URL`)।
