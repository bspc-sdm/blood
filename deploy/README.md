# Lifolo সার্ভার নিজের VPS-এ (কোনো কমান্ড লিখতে হয় না)

সব কাজ GitHub-এর বোতাম চেপে হয়। আপনার কাজ শুধু:
**VPS নেওয়া → Vercel টোকেন বানানো → GitHub-এ ৩টা সিক্রেট দেওয়া → "Run workflow" চাপা।**

VPS দুই জায়গার যেকোনো একটা:
- **Contabo** (মাসে টাকা লাগে, ঝামেলা কম) → অংশ ক
- **Oracle Cloud** (বিনামূল্যে, তবে কার্ড লাগে আর কয়েকটা বাড়তি ধাপ) → অংশ খ

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
3. এরপর **অংশ গ**।

## খ. Oracle Cloud (Always Free)

1. **অ্যাকাউন্ট:** [cloud.oracle.com](https://cloud.oracle.com) → **Sign up**।
   - **Home Region: Singapore** বাছুন। এটা পরে আর বদলানো যায় না।
   - কার্ড চায় শুধু যাচাইয়ের জন্য। অল্প টাকা আটকে রেখে আবার ফেরত দেয়।
2. **Pay As You Go করে নিন (জরুরি):** Oracle কিছু দিন অলস পড়ে থাকা বিনামূল্যের মেশিন ফেরত নিয়ে নিতে পারে।
   আমাদের অ্যাপ বেশিরভাগ সময় অলসই থাকে। Pay As You Go-তে এটা হয় না।
   ☰ মেনু → **Billing & Cost Management** → **Upgrade and Manage Payment** → **Upgrade to Pay As You Go**।
   - Always Free সীমার ভেতরে থাকলে বিল **০ টাকা**ই থাকে।
   - নিচের মাপের বাইরে কিছু বানাবেন না।
3. **মেশিন বানানো:** ☰ → **Compute** → **Instances** → **Create instance**।
   - Name: `lifolo`
   - **Image:** Change image → **Ubuntu** → **Canonical Ubuntu 24.04**
   - **Shape:** Change shape → **Ampere** → **VM.Standard.A1.Flex**
     - OCPU: **2**, Memory: **12 GB** (পাশে "Always Free-eligible" লেখা থাকবে)
   - **Networking:** যেমন আছে থাক। **Assign a public IPv4 address = Yes** থাকা চাই।
   - **Add SSH keys:** **Generate a key pair for me** → **Save private key**।
     একটা `.key` ফাইল নামবে। এটা হারাবেন না, কাউকে দেবেন না।
   - **Create** চাপুন।
   - "**Out of host capacity**" দেখালে Singapore-এ এখন খালি মেশিন নেই।
     কয়েক ঘণ্টা পর আবার চেষ্টা করুন, বা **Placement**-এ অন্য availability domain / fault domain বেছে আবার Create চাপুন।
4. **পোর্ট 80 আর 443 খোলা** (না খুললে ওয়েবসাইট খুলবে না):
   instance-এর পাতা → **Networking** (বা Primary VNIC) → **Subnet**-এর নামে চাপুন → **Security** (বা Security Lists)
   → **Default Security List** → **Security rules** / Ingress Rules → **Add Ingress Rules**:
   - Source CIDR: `0.0.0.0/0`, IP Protocol: **TCP**, Destination Port Range: `80` → **Add Ingress Rules**
   - আবার **Add Ingress Rules**: একই, শুধু Destination Port Range: `443`

   (মেশিনের ভেতরের ফায়ারওয়াল GitHub নিজেই খুলে দেয়।)
5. **IP খোঁজা:** Compute → Instances → `lifolo` → **Public IP address** (যেমন `140.245.x.x`)।
6. এরপর **অংশ গ**।

## গ. Vercel টোকেন

[vercel.com](https://vercel.com)-এ লগইন → ওপরে ডানে নিজের ছবি → **Account Settings** → বাঁ দিকে **Tokens**
→ Token Name: `lifolo-vps` → Scope: যে টিমে `blood-server` প্রজেক্ট আছে → Expiration: **30 days** → **Create**
→ যে লম্বা লেখাটা দেখায় সেটা কপি করুন (একবারই দেখায়)।

## ঘ. GitHub-এ সিক্রেট

[github.com/bspc-sdm/blood](https://github.com/bspc-sdm/blood) → ওপরে **Settings** → বাঁ দিকে
**Secrets and variables** → **Actions** → সবুজ **New repository secret** → Name আর Secret দিয়ে **Add secret**।
প্রতিটার জন্য আলাদা করে:

| Name (হুবহু) | Contabo হলে | Oracle হলে |
|---|---|---|
| `VPS_HOST` | VPS-এর IP | VPS-এর IP |
| `VPS_ROOT_PASSWORD` | root পাসওয়ার্ড | (দেবেন না) |
| `VPS_SSH_KEY` | (দেবেন না) | `.key` ফাইলটা Notepad-এ খুলে **পুরো** লেখা (`-----BEGIN` থেকে `-----END ... KEY-----` পর্যন্ত) |
| `VERCEL_TOKEN` | গ অংশের টোকেন | গ অংশের টোকেন |

## ঙ. Run workflow

[github.com/bspc-sdm/blood](https://github.com/bspc-sdm/blood) → ওপরে **Actions** → বাঁ দিকে **Setup VPS**
→ ডানে **Run workflow**:
- Branch: `main`
- provider: `contabo` বা `oracle`
- domain: **খালি রাখুন**
- সবুজ **Run workflow** চাপুন।

১০ থেকে ২০ মিনিট লাগে। পাতাটা রিফ্রেশ করলে হলুদ বৃত্ত ঘুরতে দেখবেন।

**সফল হলে:** সবুজ ✓ আসে। রানে চাপলে **Summary**-তে লেখা থাকে **✅ সার্ভার চালু: https://…sslip.io**।
সেই ঠিকানার শেষে `/api/health` লাগিয়ে খুললে `{"ok":true,...}` দেখাবে।

**ব্যর্থ হলে:** লাল ✗ আসে। রানে চাপুন → লাল ✗ দেওয়া ধাপে চাপুন → সেই লেখার **স্ক্রিনশট Claude-কে পাঠান**।
Summary-তে কোনো সিক্রেট যোগ করতে বললে (যেমন `GMAIL_APP_PASSWORD`), ঘ অংশের মতো সেই নামে যোগ করে আবার **Run workflow** চাপুন।
বারবার চালালেও কোনো ক্ষতি হয় না।

---

## এরপর যা নিজে নিজে চলে

- **ডেপ্লয়:** main-এ সার্ভারের কোড বদলালে GitHub নিজেই VPS-এ বসায় (Deploy server (VPS))।
- **ব্যাকআপ:** প্রতি রাতে ৩:৩০-এ ডাটাবেসের ব্যাকআপ হয়। কপি থাকে VPS-এ আর Firebase-এর Cloud Storage-এর `backups/` ফোল্ডারে, শেষ ১৪ দিনের।
  - Summary-তে "Cloud Storage কাজ করছে না" দেখালে: Firebase Console → **Build** → **Storage** → **Get started** চাপুন। তারপর Setup VPS আবার চালান।
- **সার্ভার বন্ধ হলে ইমেইল:** প্রতি ১০ মিনিটে GitHub দেখে। বন্ধ পেলে আপনার GitHub-এর ইমেইলে জানায়।

**যা করবেন না:**
- `VPS_ROOT_PASSWORD` / `VPS_SSH_KEY` সিক্রেট মুছবেন না, বদলাবেন না। GitHub এটা দিয়েই VPS-এ ঢোকে।
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
তাই `deploy/ci/derive-keys.js` গোপন মান থেকে (Contabo: root পাসওয়ার্ড; Oracle: ubuntu-র প্রাইভেট চাবি) scrypt দিয়ে
প্রতিবার একই দুটো ed25519 চাবি বানায়:
- VPS-এর **host key**: `/etc/ssh/lifolo_host_ed25519_key`, `sshd_config.d/00-lifolo.conf`-এ বসানো। GitHub এটাকে পিন করে চেনে।
- Contabo-তে root-এর **লগইন চাবি**।

প্রথম সংযোগ trust-on-first-use; আসল host key-এর fingerprint লগে আর Summary-তে থাকে। এরপর
`PasswordAuthentication no` আর `PermitRootLogin prohibit-password` বসে। জরুরি পথ: Contabo-র VNC, বা Oracle-এ instance-এর **Console connection**।

**ঝুঁকি (জেনে নেওয়া):**
- নিরাপত্তা এখন পুরোটা ওই একটা সিক্রেটের ওপর। Contabo-তে পাসওয়ার্ড দুর্বল হলে চাবিও দুর্বল।
  তবে পাসওয়ার্ড লগইন বন্ধ, তাই অনুমান করে মেলাতে হলে প্রতিবার scrypt আর SSH-এর ধাপ পেরোতে হয়, আর fail2ban আছে।
- GitHub-এ যার Actions চালানোর অনুমতি আছে, সে root-এর সমান। আগের আলাদা "শুধু ডেপ্লয়" চাবি আর নেই,
  কারণ সেটা রাখলে বাড়তি সিক্রেট হাতে কপি করতে হতো।

**Oracle:**
- ছবির `/etc/iptables/rules.v4` (শেষে REJECT, InstanceServices নিয়ম) রাখা হয়।
- REJECT-এর আগে 80/443 যোগ হয়। `netfilter-persistent` বুটে এটা বসায়।
- ufw বসানো হয় না, কারণ ufw প্যাকেজ netfilter-persistent সরিয়ে দেয়।

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
