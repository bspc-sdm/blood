# ডাটাবেস ব্যাকআপ — কীভাবে রাখা হয়, কীভাবে যাচাই হয়, কীভাবে ফেরত আনবেন

Lifolo-র সব তথ্য (অ্যাকাউন্ট, রক্তের অনুরোধ, ফার্মেসি, অ্যাম্বুলেন্স …) VPS-এর PostgreSQL ডাটাবেস `lifolo`-তে থাকে।
এই পাতায়: ব্যাকআপ কোথায় থাকে, প্রতি সপ্তাহে কীভাবে পরীক্ষা হয় যে সেটা সত্যিই ফেরত আনা যায়, দরকার হলে কীভাবে
ফেরত আনবেন, আর VPS-এর বাইরে (Cloudflare R2) কপি রাখতে কী লাগবে।

---

## এক নজরে

| কী | কোথায় / কখন |
|---|---|
| দৈনিক ব্যাকআপ | প্রতিদিন রাত 03:30 (ঢাকা) = 21:30 UTC, `pg_dump` → VPS-এর `/var/backups/lifolo/lifolo-<তারিখ>-<সময়>.dump` |
| কতদিন রাখা হয় | ১৪ দিন, তারপর নিজে মুছে যায় |
| সাপ্তাহিক যাচাই | **Backup check** workflow — প্রতি মঙ্গলবার ভোর 05:17 (ঢাকা), বা হাতে যেকোনো সময় |
| যাচাইয়ের টাটকা ব্যাকআপ | `/var/backups/lifolo/lifolo-<তারিখ>-<সময়>-check.dump` (এটাও ১৪ দিন রাখা) |
| বাইরের কপি (R2) | **এখন বন্ধ** — নিচের "বাইরের কপি চালু করা" অংশের ৪টা সিক্রেট দিলে চালু হয় |

দৈনিক ব্যাকআপ বসিয়েছে `deploy/setup-base.sh` (Setup VPS (base) workflow) — `lifolo-backup.timer` আর `/usr/local/sbin/lifolo-backup`।

> ⚠️ এখন সব ব্যাকআপ **একই VPS-এ**। VPS নষ্ট হলে বা Contabo অ্যাকাউন্টে সমস্যা হলে ব্যাকআপও যাবে।
> তাই বাইরের কপি (R2) চালু করা জরুরি — নিচে দেখুন।

---

## সাপ্তাহিক যাচাই — Backup check

শুধু ব্যাকআপ ফাইল থাকলেই হয় না — সেটা ফেরত আনা যায় কি না দেখতে হয়। এই workflow প্রতিবার:

1. চালু ডাটাবেস থেকে একটা **টাটকা ব্যাকআপ** নেয় (দৈনিকেরটা ছাড়াও)।
2. সেটা একটা **আলাদা অস্থায়ী ডাটাবেসে** (`lifolo_restore_test`) ফেরত আনে।
3. মিলিয়ে দেখে: Flyway-এর ইতিহাস (কোন migration চলেছে), টেবিলের সংখ্যা, index-এর সংখ্যা, আর **প্রতিটা টেবিলের সারির
   সংখ্যা** — চালু ডাটাবেসের সাথে হুবহু। (ব্যাকআপ আর গোনা একই মুহূর্তের ছবি থেকে, তাই অ্যাপ চলতে থাকলেও সংখ্যা মেলে।)
4. অস্থায়ী ডাটাবেস **মুছে ফেলে**।
5. দেখে: গত ৭ দিনের প্রতিদিন দৈনিক ব্যাকআপ আছে কি না, শেষটা ২৬ ঘণ্টার মধ্যে কি না, সব ব্যাকআপ ফাইল পড়া যায় কি না,
   দৈনিক timer চালু কি না, ডিস্কে কত জায়গা খালি।
6. R2 সিক্রেট থাকলে টাটকা ব্যাকআপের এনক্রিপ্ট করা কপি R2-তে তোলে।

**চালু ডাটাবেসে কিছু লেখা বা মোছা হয় না** — শুধু পড়া (READ ONLY) আর `pg_dump`।

### হাতে চালানো

1. github.com → **bspc-sdm/blood** → ওপরে **Actions**।
2. বাঁ দিকে **Backup check** → ডানে **Run workflow** → সবুজ **Run workflow**।
3. ২–৫ মিনিট পর সবুজ ✓ বা লাল ✗। লাইনে চাপলে নিচে **Summary**-তে বাংলা টেবিল: প্রতিটা যাচাইয়ের পাশে ✅ বা ❌।

লাল ✗ হলে Summary-র **সমস্যা** অংশটা দেখুন, বা তার স্ক্রিনশট Claude-কে পাঠান।
রিপো public, তাই Summary-তে কোনো সারির সংখ্যা বা তথ্য দেখানো হয় না — শুধু "মিলেছে / মেলেনি"।

> সাপ্তাহিক নিজে-চলা অংশটা GitHub শুধু রিপোর মূল branch (`main`) থেকে চালায় — workflow ফাইলটা `main`-এ গেলে তবেই।

---

## ফেরত আনা (restore)

SSH-এ পাসওয়ার্ড লগইন বন্ধ, তাই হাতে কাজ করতে হলে **Contabo প্যানেল → VPS → VNC কনসোল**-এ root হিসেবে ঢুকুন
(বা Claude-কে বলুন — একটা "Restore" workflow বানিয়ে দেওয়া যায়)। সব কমান্ড root হিসেবে।

### ১) কোন ব্যাকআপগুলো আছে

```bash
ls -lh /var/backups/lifolo/
```

`lifolo-20261020-213012.dump` = ২০ অক্টোবর ২০২৬, 21:30:12 UTC-র দৈনিক ব্যাকআপ। `-check.dump` = সাপ্তাহিক যাচাইয়ের।

### ২) আগে অস্থায়ী ডাটাবেসে খুলে দেখা (চালু ডাটাবেসে হাত না দিয়ে)

কিছু ভুল করে মুছে গেলে বেশিরভাগ সময় পুরো ডাটাবেস ফেরানোর দরকার নেই — পুরোনো ব্যাকআপ আলাদা ডাটাবেসে খুলে দরকারি
সারিগুলো দেখে নেওয়া যায়:

```bash
F=/var/backups/lifolo/lifolo-20261020-213012.dump      # যেটা দরকার
sudo -u postgres dropdb --if-exists lifolo_restore_test
sudo -u postgres createdb lifolo_restore_test
sudo -u postgres psql -c "REVOKE ALL ON DATABASE lifolo_restore_test FROM PUBLIC"
sudo -u postgres pg_restore --exit-on-error --single-transaction -d lifolo_restore_test "$F"
sudo -u postgres psql -d lifolo_restore_test          # দেখা শেষে \q
sudo -u postgres dropdb lifolo_restore_test            # কাজ শেষে মুছে ফেলুন
```

### ৩) পুরো ডাটাবেস ফেরত আনা (চালু ডাটাবেস বদলে যায়)

⚠️ এতে ব্যাকআপের পরের সব নতুন তথ্য হারায়। তাই আগে এখনকার অবস্থার একটা কপি রাখা হয় (ধাপ খ)।

```bash
F=/var/backups/lifolo/lifolo-20261020-213012.dump                       # (ক) কোন ব্যাকআপ
U=$(lifolo-api unit)                                                     # এখন যে instance live (lifolo-api@8080/8081)
systemctl stop "$U"                                                     # অ্যাপ বন্ধ (কিছুক্ষণ সাইট কাজ করবে না)
sudo -u postgres pg_dump -Fc -d lifolo \
  -f /var/backups/lifolo/lifolo-before-restore-$(date -u +%Y%m%d-%H%M%S).dump   # (খ) এখনকার অবস্থার কপি
sudo -u postgres dropdb --force lifolo                                   # (গ) পুরোনোটা মুছে
sudo -u postgres createdb -O lifolo lifolo                               #     নতুন ফাঁকা ডাটাবেস
sudo -u postgres psql -c "REVOKE ALL ON DATABASE lifolo FROM PUBLIC" \
  -c "GRANT CONNECT, TEMPORARY ON DATABASE lifolo TO lifolo"
sudo -u postgres pg_restore --exit-on-error --single-transaction -d lifolo "$F"   # (ঘ) ফেরত আনা
systemctl start "$U"                                                    # (ঙ) অ্যাপ চালু
curl -s "$(lifolo-api url)/api/health"; echo                             # "ok":true দেখালে ঠিক
```

`pg_restore` কোনো ত্রুটি দেখালে `--single-transaction`-এর জন্য কিছুই অর্ধেক বসে না — ডাটাবেস ফাঁকা থাকে। তখন (খ)-এর
কপিটা একইভাবে ফেরত আনুন, আর ত্রুটির লেখা Claude-কে পাঠান।

ফেরত আনার পর একবার **Backup check** চালান — সব ✅ হলে ঠিক আছে।

### ৪) VPS-ই নেই — নতুন VPS-এ ফেরত আনা

1. নতুন VPS কিনে `VPS_HOST` / `VPS_ROOT_PASSWORD` সিক্রেট বদলান → **Setup VPS (base)** চালান (deploy/BASE-SETUP.md)।
2. R2 থেকে শেষ কপিটা নামান (নিচে "R2 থেকে ফেরত আনা") → `/var/backups/lifolo/`-এ রাখুন।
3. ওপরের ধাপ ৩ (অ্যাপ তখনো বসেনি, তাই `systemctl stop/start` বাদ দিতে পারেন)।
4. **Deploy backend** চালান। অ্যাপ চালু হওয়ার সময় Flyway দেখে migration আগেই চলা আছে — নতুন কিছু থাকলে শুধু সেটুকু চালায়।

R2-তে কপি না থাকলে পুরোনো VPS নষ্ট হওয়া মানে **সব তথ্য হারানো** — তাই নিচের অংশটা।

---

## বাইরের কপি (Cloudflare R2) চালু করা

চালু থাকলে প্রতিটা **Backup check** (সপ্তাহে একবার) টাটকা ব্যাকআপ GitHub-এর মেশিনে এনে **এনক্রিপ্ট করে** R2-তে তোলে:
`db-backups/lifolo-<তারিখ>-<সময়>-check.dump.gpg`। তোলার আগে ডিক্রিপ্ট করে মিলিয়ে দেখা হয়, তোলার পর আকার মিলিয়ে দেখা হয়।

### যা লাগবে — GitHub সিক্রেট (Settings → Secrets and variables → Actions)

| সিক্রেট | কী | কোথায় পাবেন |
|---|---|---|
| `R2_ACCOUNT_ID` | Cloudflare অ্যাকাউন্ট ID (৩২ অক্ষর) | Cloudflare ড্যাশবোর্ড → R2 → ডানদিকে **Account ID** |
| `R2_ACCESS_KEY_ID` | R2 API token-এর Access Key ID | R2 → **Manage R2 API Tokens** → **Create API token** |
| `R2_SECRET_ACCESS_KEY` | ওই token-এর Secret Access Key | একই জায়গায় (একবারই দেখায় — তখনই কপি করুন) |
| `R2_BUCKET` | বাকেটের নাম | R2 → **Create bucket** |
| `R2_BACKUP_BUCKET` | *(ঐচ্ছিক)* ব্যাকআপের জন্য আলাদা বাকেট | না দিলে `R2_BUCKET`-এই, `db-backups/` ফোল্ডারে |

চারটা (প্রথম চারটা) থাকলেই চালু; একটাও না থাকলে ধাপটা চুপচাপ বাদ যায়, Summary-তে ⏸️ "বন্ধ" দেখায়।
এই নামগুলোই **Deploy backend** ছবি রাখার জন্য ব্যবহার করে — একবার দিলে দুটোতেই কাজ করবে।

পরামর্শ:
- ছবির বাকেট public হলে ব্যাকআপের জন্য **আলাদা private বাকেট** বানিয়ে `R2_BACKUP_BUCKET`-এ দিন
  (কপি এনক্রিপ্ট করা থাকে, তবু আলাদা রাখা ভালো)।
- API token-এ শুধু ওই বাকেট(গুলো)-র **Object Read & Write** অনুমতি দিন।
- ৯০ দিনের পুরোনো কপি **Backup check নিজেই মোছে** (প্রতি সপ্তাহে, এ সপ্তাহের কপি ওঠার পরে; সবচেয়ে নতুন ৪টা সবসময় থাকে;
  শুধু `db-backups/lifolo-…dump.gpg` নামের ফাইল) — R2-তে lifecycle rule বসাতে হয় না। গোপনীয়তা নীতির "বাইরের ব্যাকআপ সর্বোচ্চ
  ৯০ দিন" এটার ওপর নির্ভর করে — R2-র চাবিতে মোছার (Object Read & Write) অনুমতি থাকতে হবে।
- R2-র বিনামূল্যের সীমা (১০ GB) এখনকার ডাটাবেসের জন্য যথেষ্ট।

### এনক্রিপশনের চাবি — আলাদা কিছু রাখতে হয় না

চাবি বানানো হয় `VPS_ROOT_PASSWORD` থেকে (`deploy/ci/backup-key.js`; SSH চাবিগুলোও এভাবেই বানানো, কিন্তু আলাদা লেবেলে,
তাই একটা থেকে অন্যটা পাওয়া যায় না)। R2 বা Cloudflare-এর কেউ ফাইল খুলতে পারে না — পাসওয়ার্ড ছাড়া।

> ⚠️ **Contabo-র root পাসওয়ার্ডটা password manager-এ (বা কাগজে, নিরাপদ জায়গায়) রেখে দিন।** GitHub সিক্রেট পরে আর পড়া যায় না।
> পাসওয়ার্ড কখনো বদলালে পুরোনো কপিগুলো খুলতে **পুরোনো** পাসওয়ার্ড লাগবে — সেটাও রেখে দিন।

### R2 থেকে ফেরত আনা

যেকোনো কম্পিউটারে (Node.js আর gpg থাকলে; বা নতুন VPS-এই):

```bash
# ১) Cloudflare ড্যাশবোর্ড → R2 → বাকেট → db-backups/ → সবচেয়ে নতুন .gpg ফাইল → Download
# ২) চাবি বানানো (রিপোর ফোল্ডারে; পাসওয়ার্ড কমান্ড হিস্ট্রিতে না রাখতে read দিয়ে)
read -rs LIFOLO_MASTER; export LIFOLO_MASTER        # Contabo-র root পাসওয়ার্ড লিখে Enter
node deploy/ci/backup-key.js backup.key; unset LIFOLO_MASTER
# ৩) খোলা
gpg --batch --pinentry-mode loopback --passphrase-file backup.key \
  --decrypt lifolo-20261027-231912-check.dump.gpg > lifolo-restore.dump
rm backup.key
```

তারপর `lifolo-restore.dump` VPS-এর `/var/backups/lifolo/`-এ রেখে ওপরের "পুরো ডাটাবেস ফেরত আনা" (ধাপ ৩)।
`gpg: decryption failed: Bad session key` এলে পাসওয়ার্ড ভুল (বা পুরোনো পাসওয়ার্ড লাগবে)।

---

## ফাইলগুলো

| ফাইল | কাজ |
|---|---|
| `.github/workflows/backup-check.yml` | Backup check workflow (সাপ্তাহিক + হাতে), বাংলা Summary, log branch `ci-log-backup-check` |
| `deploy/backup-check.sh` | VPS-এ চলে: টাটকা dump, অস্থায়ী ডাটাবেসে ফেরত আনা, মেলানো, মোছা, ফাইল/timer/ডিস্ক দেখা |
| `deploy/ci/backup-key.js` | `VPS_ROOT_PASSWORD` থেকে বাইরের কপির এনক্রিপশন চাবি |
| `deploy/setup-base.sh` | দৈনিক ব্যাকআপ বসায় (`lifolo-backup.timer`, ১৪ দিন) |
