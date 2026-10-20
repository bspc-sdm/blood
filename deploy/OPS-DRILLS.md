# অপারেশনের মহড়া আর পরীক্ষা (VPS)

Lifolo-র production VPS (Contabo, ৬ vCPU / ১২ GB, Ubuntu 24.04; nginx → Spring Boot `lifolo-api`, PostgreSQL 16 আর
Redis একই মেশিনে; সামনে Cloudflare) কতটা চাপ নিতে পারে, পড়ে গেলে নিজে ওঠে কি না, আর ডিস্ক/সার্টিফিকেট/নির্ধারিত কাজ
ঠিক আছে কি না — ছয়টা workflow দিয়ে মাপা হয়। সবগুলো **হাতে চালানো** (Actions → নাম → Run workflow → branch `main`)।
ফল প্রতিবার: workflow-এর পাতায় বাংলা সারাংশ, আর log branch **`ci-log-ops-drills`**-এ (`load/`, `restart/`, `crash/`,
`disk/`, `ssl/`, `timers/` — প্রতিটা ফোল্ডারে `summary.md`)। VPS-এর IP কোথাও লেখা হয় না (`<vps-ip>`)।

<!-- RESULTS -->

## কীভাবে আবার চালাবেন

| workflow | কী করে | সাইটে প্রভাব | কতক্ষণ |
|---|---|---|---|
| **Ops: load test** | GitHub-এর মেশিন থেকে সরাসরি VPS-এ (api.lifolo.com → VPS-এর IP; Cloudflare-এর পাশ কাটিয়ে) ধাপে ধাপে চাপ বাড়ায়; p95 > 1 s বা ত্রুটি > 1% হলে থামে; VPS-এ প্রতি ২ সেকেন্ডে CPU/RAM/Java/DB-সংযোগ মাপে | চলার সময় সাইট ধীর হতে পারে; শেষে ডেমোর বানানো সব সারি মোছে | ১০-১৫ মিনিট |
| **Ops: restart drill** | VPS **রিবুট** (confirm-এ `REBOOT` লিখতে হয়), তারপর https health কত সেকেন্ডে সবুজ আর সব সার্ভিস নিজে চালু হলো কি না | ১-২ মিনিট সাইট বন্ধ | ~৫ মিনিট |
| **Ops: crash drill** | অ্যাপের Java প্রসেস `kill -9` (confirm-এ `KILL`), systemd আবার চালায় কি না, কত সেকেন্ডে health সবুজ | ~৩০-৪০ সেকেন্ড API বন্ধ | ~৩ মিনিট |
| **Ops: disk and RAM check** | ডিস্ক/inode/RAM/swap, কোন ফোল্ডারে কত, ডাটাবেস আর ব্যাকআপের আকার, journald/logrotate | নেই (শুধু দেখা) | ১ মিনিট |
| **Ops: SSL and Cloudflare check** | সার্টিফিকেটের মেয়াদ, nginx প্রতিটা নামে কোনটা দেখায় আর যাচাই হয় কি না, `certbot renew --dry-run`, Cloudflare-এর মোড, HSTS আর নিরাপত্তা-হেডার | নেই (dry-run Let's Encrypt-এর staging-এ) | ২ মিনিট |
| **Ops: timers and jobs check** | systemd timer, দৈনিক ব্যাকআপ, cron, JobRunr-এর কাজ গত ২৪ ঘণ্টায় চলেছে কি না; GitHub-এর schedule | নেই | ১ মিনিট |

- **load test**-এর ইনপুট: `rates` (ধাপ, লগইন-করা অনুরোধ/সেকেন্ড; ডিফল্ট `5 10 20 40 60 80 120 160 200 250 300`),
  `stage_seconds` (ডিফল্ট ৬০), `amb_cycles` (অ্যাম্বুলেন্স ডাকা → বাতিল কতবার, ০-৩; ডেমো চালকদের ফোনে নোটিফিকেশন যায়),
  `limiter_check` (শেষে অতিথির সীমা পরীক্ষা)।
- load test, restart আর crash `vps-admin` concurrency group-এ — Deploy backend চলার সময় অপেক্ষা করে (deploy অ্যাপ restart
  করে, মাপ নষ্ট হতো)। একই group-এ দুটো অপেক্ষায় থাকলে GitHub আগেরটা বাতিল করে — তাই deploy-এর ঠিক আগে-পরে চালাবেন না।
- কখন: load test আর drill-গুলো বড় বদলের পরে (নতুন JVM/পুল/nginx সেটিং, বড় feature) আর মাসে একবার; disk/ssl/timers যেকোনো সময়।
- হাতে VPS-এ: `bash /root/lifolo-ops/vps-ops.sh disk|ssl|timers|services|config` (workflow প্রতিবার ফাইলটা নতুন করে পাঠায়)।

### load test কী পাঠায় (শুধু ডেমো)

- **পড়া (৯৫%)** — ডেমো রোগী/ডোনার/ফার্মেসি/ডাক্তার/চালক/কেয়ারগিভার হিসেবে: প্রোফাইল, নিজের রক্তের অনুরোধ, কাছের অনুরোধ,
  অ্যাপয়েন্টমেন্ট, প্রেসক্রিপশন, ঘণ্টি, ডাক্তার/ফার্মেসি/অ্যাম্বুলেন্স/হাসপাতাল/ব্লাড ব্যাংক/ল্যাবের তালিকা, জরুরি অনুরোধ,
  অ্যাম্বুলেন্সের ভাড়া-গোনা (`ambreq_count`), ফার্মেসির আজকের হিসাব, চালকের কল-তালিকা।
- **লেখা (৫% + অল্প)** — `inbox_read` (upsert); প্রতি ধাপে একবার রক্তের অনুরোধ বানানো → দেখা → মোছা (প্রতিবার আলাদা ডেমো
  অ্যাকাউন্ট, দিনে ৩টার সীমা); ধাপ ১ আর ৩-এ অ্যাম্বুলেন্স ডাকা → অবস্থা → বাতিল।
- **অতিথি** — স্থির ২/সে (হোমের তালিকা; সার্ভারের সীমা প্রতি IP-তে মিনিটে ২৪০, GitHub-এর মেশিন একটাই IP)।
- **লগইন-কোড** — ০.৫/সে, কোনো ইমেইল না পাঠিয়ে: ভুল ইমেইলে `send-code` (400) আর কখনো-না-চাওয়া কোড `verify-code` (400)।
  আসল কোড পাঠানো হয় না — SES-এ ডেমো ঠিকানায় (`lifolo-demo.test`) মেইল গেলে bounce হয়ে পাঠানোর সুনাম নষ্ট হতো।
- টোকেন: ডেমো লগইন (`verify-code {"demo": role}`) → Firebase ID token; uid `demo-…` না হলে থামে। মালিকের অ্যাকাউন্ট ছোঁয়া হয় না।
- **পরিষ্কার** (`vps-ops.sh cleanup`, সবসময় চলে): টেস্টের সময় থেকে `LOADTEST` চিহ্নের রক্ত/অ্যাম্বুলেন্স অনুরোধ, তাদের
  ঘণ্টির সারি, `ambreq_create_log` মোছা; ডেমোর `user_quotas`, `user_state.inbox_seen_at`, `ambreq_patient_state` আগের
  snapshot-এ ফেরত। সব SQL `uid LIKE 'demo-%'` দিয়ে বাঁধা।

## ফাইল

| ফাইল | কাজ |
|---|---|
| `.github/workflows/ops-*.yml` | ছয়টা workflow |
| `deploy/ops/vps-ops.sh` | VPS-এর অংশ: config, services, sampler, snapshot/cleanup, window, disk, ssl, cfports, timers |
| `deploy/ops/k6-lifolo.js` | k6-এর একটা ধাপ (workflow প্রতি ধাপে আলাদা চালায়) |
| `deploy/ops/demo-tokens.py` | ডেমো রোলগুলোর Firebase ID token |
| `deploy/ops/load-report.py` | ধাপের ফল + VPS-এর মাপ → বাংলা সারাংশ |
