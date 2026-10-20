# অপারেশনের মহড়া আর পরীক্ষা (VPS)

Lifolo-র production VPS (Contabo, ৬ vCPU / ১২ GB, Ubuntu 24.04; nginx → Spring Boot `lifolo-api`, PostgreSQL 16 আর
Redis একই মেশিনে; সামনে Cloudflare) কতটা চাপ নিতে পারে, পড়ে গেলে নিজে ওঠে কি না, আর ডিস্ক/সার্টিফিকেট/নির্ধারিত কাজ
ঠিক আছে কি না — ছয়টা workflow দিয়ে মাপা হয়; সপ্তমটা লগ কতদিন থাকে তা ঠিক করে। সবগুলো **হাতে চালানো** (Actions → নাম → Run workflow → branch `main`)।
ফল প্রতিবার: workflow-এর পাতায় বাংলা সারাংশ, আর log branch **`ci-log-ops-drills`**-এ (`load/`, `restart/`, `crash/`,
`disk/`, `ssl/`, `timers/`, `retention/` — প্রতিটা ফোল্ডারে `summary.md`)। VPS-এর IP কোথাও লেখা হয় না (`<vps-ip>`)।

## টিউনিং — ২০ অক্টোবর ২০২৬ (প্রয়োগ করা; ফল log branch `ci-log-ops-tuning`)

সব সেটিং সাধারণ deploy/setup-এর অংশ — **Deploy backend** প্রতিবার `deploy/tune-vps.sh` চালায় (deploy-backend.sh-এর আগে),
**Setup VPS (base)** setup-base.sh-এর পরে; বারবার চালালেও একই (যা বদলায়নি তা ছোঁয়া হয় না)। তাই নতুন VPS (যেমন সিঙ্গাপুরে
সরানো) Setup + Deploy-এই সব পায় — আলাদা কিছু চালাতে হয় না (লগ কতদিন থাকে — **Ops: log retention** — আগের মতোই একবার)।

| কী | আগে | এখন | কোথায় |
|---|---|---|---|
| JVM | `-Xmx1g` | `-Xms1g -Xmx3g`, G1 (`MaxGCPauseMillis=200`, `ParallelRefProcEnabled`, `UseStringDeduplication`), `ExitOnOutOfMemoryError`, GC লগ `/var/log/lifolo/gc.log` (৫ × ১০ MB), `LimitNOFILE=65536` | `lifolo-api.service.d/20-tuning.conf` (tune-vps.sh) |
| PostgreSQL | shared_buffers 128MB, effective_cache_size 4GB, work_mem 4MB | 2GB, 6GB, 8MB; max_connections 100 (যেমন ছিল) | `conf.d/lifolo-tuning.conf` |
| Redis | maxmemory 0 | `512mb`, `volatile-lru` (চালু অবস্থায় CONFIG SET, restart নয়) | `/etc/redis/lifolo-tuning.conf` |
| nginx | worker_connections 768, open files 1024, প্রতি অনুরোধে অ্যাপে নতুন TCP | 4096, `worker_rlimit_nofile 16384` + systemd `LimitNOFILE=16384`; `upstream lifolo_api` keepalive 64 (১৫ s — Tomcat-এর চেয়ে কম) | nginx.conf, `conf.d/lifolo-api-upstream.conf` (deploy-backend.sh) |
| `/api/`-এর IP-সীমা | নেই | আসল দর্শকের IP-প্রতি ২০/সে, burst ৪০ → 429 + JSON; এই মেশিন আর CI-র সাময়িক ঠিকানা বাদ | deploy-backend.sh |
| অ্যাপ বন্ধের সময় (deploy/restart) | nginx-এর HTML 502 | ছোট JSON (কোড একই 502/503/504): `{"error":"সার্ভার এই মুহূর্তে চালু হচ্ছে — … (Server is restarting — …)","code":"server_unavailable","retry":true}` + `Retry-After: 15`; সীমায় 429 `rate_limited` | deploy-backend.sh |
| origin | 80/443 সবার জন্য খোলা | **80/443 শুধু Cloudflare-এর ঠিকানা থেকে** (ufw, v4+v6; তালিকা প্রতি deploy-এ cloudflare.com থেকে, না পেলে লেখা তালিকা; সপ্তাহে একবার timer-ও), 22 সবার জন্য। আসল দর্শকের IP: `CF-Connecting-IP` (শুধু Cloudflare থেকে এলে) | tune-vps.sh, `lifolo-origin-guard.timer` |

- **সার্টিফিকেট নবায়ন**: lifolo.com/api.lifolo.com-এর HTTP-01 Cloudflare দিয়ে আসে (Cloudflare-এর ঠিকানা খোলা); sslip.io-র নাম
  Cloudflare-এর পেছনে নয় — তাই certbot-এর pre/post hook (`/etc/letsencrypt/renewal-hooks/`) নবায়নের সময়টুকু পোর্ট 80 সবার
  জন্য খোলে, পরে বন্ধ করে (post-hook না চললে guard ২ ঘণ্টা পরে বন্ধ করে)। `certbot renew --dry-run`: তিনটাই সফল।
- **CI**: সরাসরি-VPS পরীক্ষা (deploy, download, ssl, crash drill, Build APK-এর ডাউনলোড-যাচাই, Setup) SSH tunnel দিয়ে
  (`deploy/ci/origin-access.sh tunnel` — GitHub-এর মেশিনে iptables DNAT, পুরোনো `--resolve`/`/etc/hosts` যেমন ছিল)। load test আর
  restart drill: সেই মেশিনের ঠিকানার জন্য সাময়িক ছিদ্র (৬০/৪০ মিনিটে নিজে বন্ধ)। Uptime-এর Cloudflare-challenge fallback VPS-এর
  ভেতর থেকে। Deploy backend VPS থেকে Cloudflare হয়ে নিজের কাছে যাচাই করে — 521/522/523 হলে (Cloudflare ঢুকতে পারছে না)
  জরুরি দরজা খোলে (`/etc/lifolo/origin-open`, 80/443 সবার জন্য) আর ব্যর্থ হয় (ইমেইল)।
- **সতর্কতা (Uptime, প্রতি ৩ ঘণ্টা)**: `/api/health`-এর সাথে ssh দিয়ে `vps-ops.sh health` — ❌ (workflow ব্যর্থ → GitHub মালিককে ইমেইল):
  ডিস্ক ≥ ৮০%, inode ≥ ৮০%, খালি RAM < ১৫%, swap > ৫১২ MB, কোনো সার্টিফিকেট < ২১ দিন, শেষ ব্যাকআপ বা `daily-maintenance` > ২৬ ঘণ্টা,
  গত ৩ ঘণ্টায় lifolo-api নিজে restart (crash/OOM) বা অপ্রত্যাশিত রিবুট, অ্যাপ ভেতর থেকে বন্ধ। ⚠️ (শুধু সারাংশে): origin খোলা,
  Redis ≥ ৮০% বা সীমা fail-open, timer বন্ধ। deploy আর মহড়া নিজের সময় `/var/lib/lifolo/ops-expected`-এ লেখে — সেই ১৫ মিনিটের
  restart/রিবুট "অপ্রত্যাশিত" নয়। (অন্য branch-এর পুরোনো deploy-backend.sh এটা লেখে না — rebase না হওয়া পর্যন্ত সেগুলোর
  ভাঙা jar-এর crash-loop সতর্কতা দেবে।) বাংলা সারাংশ workflow-এর পাতায় আর `ci-log-uptime`-এ।

### লোড টেস্ট — টিউনিং আর backend-এর `6ce3da9`-এর পরে

লগইন-করা অনুরোধে প্রতি k6 VU আলাদা "দর্শক" (`CF-Connecting-IP` 100.64.x.y, শুধু টেস্টের সময় GitHub-এর মেশিন থেকে মানা) —
নতুন অ্যাপ-সীমা (অ্যাকাউন্ট+IP-প্রতি মিনিটে ৬০০) সব এক IP থেকে এলে ১০টা ডেমো অ্যাকাউন্টে ~১০০ rps-এই আটকায়।

| লক্ষ্য (লগইন-করা rps) | পাওয়া rps | p95 সব | p95 পড়া | ত্রুটি | VPS CPU গড়/সর্বোচ্চ | Java RSS | DB active/পুল | আগে (একই লক্ষ্যে) |
|---|---|---|---|---|---|---|---|---|
| 300 | 303.6 | 179 ms | 215 ms | 0% | 38% / 74% | 1,230 MB | 2/20 | p95 602 ms |
| 500 | 503.6 | 208 ms | 268 ms | 0% | 45% / 55% | 1,275 MB | 2/20 | 704 ms |
| **650** | 653.6 | **245 ms** | 268 ms | **0%** | 53% / 65% | 1,286 MB | 1/20 | 581 ms |
| 800 | 803.6 | 370 ms | 380 ms | 0% | 59% / 74% | 1,302 MB | 3/20 | ❌ 2,241 ms, 28% ত্রুটি |
| 1000 | 999.8 | 403 ms | 407 ms | 0% | 62% / 76% | 1,315 MB | 2/20 | — |
| **1200** | 1194.8 | **429 ms** | 437 ms | **0%** | 68% / 82% | 1,363 MB | 3/20 | — |

- **নিরাপদ ক্ষমতা: অন্তত ~১,২০০ লগইন-করা অনুরোধ/সেকেন্ড** (p95 < 0.45 s, ত্রুটি 0%) — দেওয়া সর্বোচ্চ ধাপেও ভাঙেনি; আগে ~৬৫০,
  ভাঙন ~৮০০। ১,০০০-এর ওপরে k6 কিছু অনুরোধ সময়মতো পাঠাতে পারেনি (dropped 227/525 — GitHub-এর একটা মেশিনের সীমা), তাই
  আসল সীমা আরও ওপরে; পরের বার `rates` `1200 1500 1800`। CPU ≤ ৮২%, RAM ৯.৮ GB খালি, DB-তে ≤ ৩টা কাজ — বোতলের গলা এখনো নেই।
- nginx-এ ২,৬৮,১৪৯ অনুরোধে 5xx ০, 429 ০, error.log নতুন ০; অ্যাপে ERROR ০; লেখা (রক্তের অনুরোধ ৬, অ্যাম্বুলেন্স ২) সব পরিষ্কার।
- নেটওয়ার্কের ভিত্তি এবার ~৯৫ ms (আগে ৯৩–১৫৫ ms) — তুলনা মোটামুটি সমান।
- ⚠️ শিক্ষা (backend-এর জন্য): এক অ্যাকাউন্ট+IP থেকে একসাথে অনেক অনুরোধ এলে (সীমা তুলে দিয়ে চালানো একটা চেষ্টায়) Bucket4j-এর
  Redis CAS-সীমা একই key-তে বারবার ব্যর্থ-আবার-চেষ্টা করে অ্যাপ ~১২০ rps-এ আটকে গিয়েছিল (DB অলস, অনুরোধ ৩০ s)। আসল
  ব্যবহারকারীর key আলাদা, তাই সাধারণত সমস্যা নয় — কিন্তু একই NAT-এর পেছনের অনেক অতিথি (`guest:<ip>`) একসাথে এলে একই রকম
  হতে পারে। শেষ টেস্টে অতিথির সীমা-পরীক্ষায় ৩০০-র সব 200 এসেছে (আগের চালানোয় একই অ্যাপে 240/60) — সীমার মিনিট-সীমানায়
  পড়ে থাকতে পারে, আবার দেখার মতো।

রিবুট মহড়া (নতুন সেটিং-এ) এখনো চালানো হয়নি — `deploy/OPS-TUNING-STATUS.md` দেখুন।

## ফলাফল — ২০ অক্টোবর ২০২৬ (প্রথম চালানো, টিউনিংয়ের আগে)

### ১. লোড টেস্ট (Ops: load test)

দুবার চালানো হয়েছে (প্রতি ধাপ ৬০ সেকেন্ড; প্রতিবার GitHub-এর আলাদা মেশিন, তাই নেটওয়ার্কের ভিত্তি আলাদা —
`/api/health`-এর মাঝামাঝি প্রথমবার ~৯৩ ms, দ্বিতীয়বার ~১৫৫ ms):

| লক্ষ্য (লগইন-করা rps) | পাওয়া rps (সব মিলিয়ে) | p95 সব | ত্রুটি | VPS CPU সর্বোচ্চ | Java RSS | DB সংযোগ active/পুল |
|---|---|---|---|---|---|---|
| 5 → 300 (প্রথম চালানো, ১১ ধাপ) | 8.6 → 303.6 | 292–503 ms | 0% | 52% | 583 → 691 MB | ≤4/20 |
| 300 | 303.6 | 602 ms | 0% | 54% | 743 MB | 1/20 |
| 400 | 403.6 | 559 ms | 0% | 60% | 755 MB | 1/20 |
| 500 | 503.6 | 704 ms | 0% | 67% | 828 MB | 1/20 |
| **650** | **653.6** | **581 ms** | **0%** | 69% | 841 MB | 2/20 |
| 800 ❌ | 780.7 | 2,241 ms | **28.3%** | 77% | 1,579 MB | 1/20 |

- **নিরাপদ ক্ষমতা: ~৬৫০ অনুরোধ/সেকেন্ড** (৯৫% পড়া, ৫% লেখা; p95 < 0.7 s, ত্রুটি 0%)। **ভাঙন: ~৮০০ rps**।
- ভাঙনের কারণ CPU, RAM বা ডাটাবেস নয় (CPU ≤ ৭৭%, DB-তে একসাথে ≤ ৪টা কাজ, Hikari টাইমআউট ০, nginx-এ 5xx ০) —
  **সব ত্রুটি HTTP 401 "লগইনের মেয়াদ শেষ"**: প্রতিটা লগইন-করা অনুরোধে সার্ভার Firebase-কে জিজ্ঞেস করে টোকেন বাতিল হয়েছে কি না
  (`verifyIdToken(token, checkRevoked = true)` → Google-এর কাছে একটা HTTP কল); ~৮০০/সে-তে সেই কল ব্যর্থ হয় আর অ্যাপ
  সেটাকে "মেয়াদ শেষ" বলে। ~১০ মিনিট পরেও (১০ rps-এ) ৩৩% অনুরোধ 401 পেয়েছে, ~১৩ মিনিট পরে ০% — Firebase/Google-এর
  দিকের সীমা (throttling) কিছুক্ষণ থেকে যায় বলে মনে হয় (অ্যাপ কারণটা লগে লেখে না, তাই নিশ্চিত নয়)। আসল ব্যবহারকারীর
  ক্ষেত্রে মানে: খুব বেশি চাপে সবাই "আবার লগইন করুন" দেখবে, আর সেটা চাপ কমার পরেও কয়েক মিনিট চলতে পারে।
- লগইন-করা পড়ার মাঝামাঝি সময় health-এর চেয়ে ~১৩৫ ms বেশি, অথচ ডাটাবেস-ছোঁয়া লগইন-কোডের পথ (টোকেন ছাড়া) health-এর
  সমান — অর্থাৎ সার্ভারের সময়ের বড় অংশ এই Firebase-কল।
- অতিথির সীমা কাজ করে: একই IP থেকে দ্রুত ৩০০টা → ২৪০টা 200, ৬০টা 429।
- লেখা: প্রতি ধাপে রক্তের অনুরোধ বানানো→দেখা→মোছা, আর অ্যাম্বুলেন্স ডাকা→অবস্থা→বাতিল ২ বার — সব চলেছে; সব পরিষ্কার
  (`LOADTEST` সারি ০, ডেমোর কোটা আগের মতো)। প্রথম চালানোয় snapshot হারিয়েছিল (দুটো workflow একসাথে একই স্ক্রিপ্ট
  লিখছিল — এখন ঠিক করা), তখন `cleanup_since` দিয়ে পরিষ্কার: শুধু ডেমো রোগী/ডোনারের ঘণ্টির "দেখা হয়েছে" সময় আগের মানে
  ফেরানো যায়নি (ক্ষতি নেই)।

### ২. রিবুট মহড়া (Ops: restart drill) — ⚠️ VPS রিবুট করা হয়েছিল, ০৫:২৭:৩৬ UTC

- রিবুট কমান্ড থেকে **৪৬ সেকেন্ডে https `/api/health` সবুজ** (সাইট বন্ধ ছিল ~৩৫ সেকেন্ড; বুট ১২.৭ s, Spring ১১.৯ s);
  Cloudflare দিয়েও একই সময়ে ঠিক।
- নিজে চালু হয়েছে: lifolo-api, nginx, postgresql (16-main), redis, ssh, fail2ban, ufw, unattended-upgrades, cron, আর timer
  lifolo-backup, certbot, logrotate — সব ✅। swap চালু, ফায়ারওয়াল চালু।
- `cloud-init.service` ব্যর্থ — রিবুটের আগেও ব্যর্থ ছিল (Contabo-র ইমেজ), Lifolo-র কিছু নয়।

### ৩. প্রসেস-ক্র্যাশ মহড়া (Ops: crash drill)

- `kill -9` থেকে **৬ s-এ systemd নতুন প্রসেস** (RestartSec=5), **১৯–২২ s-এ health সবুজ**। মাঝে nginx 502 দেয় (HTML)।
- restart গোনা 0 → 1; JobRunr-এর প্রতি-মিনিটের কাজ (storage-trash, ambulance-timers) restart-এর পরে আবার চলেছে, PROCESSING-এ আটকে কিছু নেই।

### ৪. ডিস্ক / RAM / লগ (Ops: disk and RAM check)

- ডিস্ক ৬% (৯৬ GB-এর ৫.৫ GB), inode ১%, খালি RAM ~৯০% (১২ GB-এর ~১০.৭ GB), swap ০। ডাটাবেস ১৭ MB, ব্যাকআপ ০.৪ MB/দিন।
- লগ: অ্যাপ stdout → journald (কোনো .log ফাইল নেই); journald ~৬০ MB। `/var/cache/apt` ৪৭৯ MB (মাঝে মাঝে `apt-get clean`)।
- গত ২৪ ঘণ্টায় অ্যাপের ERROR-এর প্রায় সব (৭৩+৭৩) চালু হতে না-পারা jar (`UnsatisfiedDependencyException` → "Application run
  failed") — অন্য branch-এর deploy-এর সময়, deploy-backend.sh আগের jar-এ ফিরিয়েছে। বাকি ১টা: লগইন-কোডের ইমেইল পাঠানো যায়নি (SES)।
- **লগ কতদিন থাকে** (নতুন: `deploy/log-retention.sh`, workflow **Ops: log retention**; গোপনীয়তা নীতির "কয়েক সপ্তাহ পরে মোছে"):

  | লগ | এখন |
  |---|---|
  | journald (অ্যাপ lifolo-api আর সব সার্ভিস) | `MaxRetentionSec=21day`, `MaxFileSec=1day`, `SystemMaxUse=1G`, `SystemKeepFree=2G` → ≤২১ দিন |
  | nginx access/error (দর্শকের IP) | daily, rotate 13, maxage 14 → ≤১৪ দিন |
  | syslog/auth.log (rsyslog — journald-এর কপি, অ্যাপের লগও), PostgreSQL, Redis, fail2ban, ufw | weekly, rotate 2, maxage 21 → ≤২১ দিন |
  | btmp/wtmp (SSH লগইন) | monthly → weekly, rotate 2, maxage 21 → ≤২১ দিন |

  প্যাকেজের `/etc/logrotate.d/*`-এ শুধু rotate/maxage (আর btmp/wtmp-এ monthly→weekly) বদলায়; unattended-upgrades বদলানো
  conffile রেখে দেয়। নতুন VPS বা Setup VPS (base) আবার চালালে একবার **Ops: log retention** চালাতে হবে। (ব্যাকআপ ১৪ দিন
  রাখা হয় — সেটা লগ নয়, ডাটাবেস।)

### ৫. SSL / Cloudflare (Ops: SSL and Cloudflare check)

- Let's Encrypt সার্টিফিকেট: lifolo.com + www (মেয়াদ ২০২৭-০১-১৮), api.lifolo.com আর sslip.io-র নাম (২০২৭-০১-১৭) — ৮৯ দিন বাকি;
  nginx প্রতিটা নামে ঠিকটা দেখায়, যাচাই `0 ok` (নাম + chain)। অচেনা নাম বা SNI ছাড়া → handshake বাতিল (IP স্ক্যানে নাম ফাঁস হয় না)।
  TLS 1.1 বন্ধ, 1.2/1.3 চালু।
- `certbot renew --dry-run` (Cloudflare-এর পেছনে HTTP-01): তিনটাই সফল (৩৫ s)। certbot.timer দিনে দুবার।
- Cloudflare: DNS Cloudflare-এর ঠিকানা (proxy চালু); origin-এর পোর্ট 80 শুধু https-এ পাঠায় (301), তবু Cloudflare দিয়ে
  `/api/health` JSON 200 → **Cloudflare origin-এ HTTPS-এ আসে = Full বা Full (strict), Flexible নয়**; origin-এর সার্টিফিকেট বৈধ,
  তাই Full (strict) কাজ করে। (ঠিক কোনটা বাছা, শুধু ড্যাশবোর্ডে দেখা যায় — Cloudflare API চাবি নেই।)
- HSTS `max-age=31536000` সব পাতায় (Cloudflare দিয়ে আর সরাসরি)। ওয়েবপাতা (`/`, `/admin/`, `/privacy`): HSTS, nosniff,
  X-Frame-Options/frame-ancestors, Referrer-Policy, CSP সব আছে। `/api/health` (JSON): CSP নেই (দরকারও নেই); lifolo.com/api-তে
  Referrer-Policy নেই (api.lifolo.com-এ আছে) — ছোট অসঙ্গতি। http → https আর www → lifolo.com 301 ঠিক।
- ⚠️ origin সরাসরি খোলা: GitHub-এর মেশিন Cloudflare ছাড়াই `https://api.lifolo.com` (VPS-এর IP দিয়ে) পায় — Cloudflare-এর
  WAF/সীমা এড়ানো যায় (নিচে পরামর্শ)।

### ৬. নির্ধারিত কাজ (Ops: timers and jobs check)

- systemd: lifolo-backup (শেষ ১৯ অক্টো ২১:৩২ UTC, সফল; পরেরটা আজ ২১:৩০), certbot, logrotate, apt-daily, man-db, dpkg-db-backup,
  sysstat — গত ২৪ ঘণ্টায় চলেছে, সব সফল। apt-daily-upgrade, fstrim, e2scrub এখনো চলার সময় হয়নি (VPS নতুন)।
- JobRunr: `daily-maintenance` (রোজ 03:00 UTC) আজ 03:00-এ সফল (ব্যর্থ ০); `ambulance-timers` আর `storage-trash` প্রতি মিনিটে
  সফল (ব্যর্থ ০); JobRunr সার্ভার ১টা, সাড়া দিচ্ছে। ঘড়ি NTP-তে মেলানো, Google-এর সাথে পার্থক্য ০ s।
- GitHub: uptime (প্রতি ৩ ঘণ্টা) সফল; backup-check (সাপ্তাহিক, সোমবার 23:17 UTC) এখনো schedule-এ চলেনি — নতুন।

## পরামর্শ (প্রথম চালানোর পরে) — ১-৭ প্রয়োগ করা হয়েছে

১: backend-এ (`6ce3da9`, টোকেন স্থানীয়ভাবে যাচাই, অ্যাকাউন্টের অবস্থা uid ধরে ৯০ s cache, Firebase-এর সমস্যায় 503)। ২-৭: `deploy/tune-vps.sh`,
`deploy/deploy-backend.sh` আর Uptime workflow-এ — ওপরে "টিউনিং" অংশ। ৮ এখনো বাকি।

গুরুত্ব অনুযায়ী (তখনকার লেখা):

1. **Firebase-এর revocation-কল কমানো (backend)** — ক্ষমতার আসল সীমা এটাই। প্রতি অনুরোধে `checkRevoked = true`-র বদলে
   টোকেন স্থানীয়ভাবে যাচাই (Google-এর public key, cache হয়) আর revocation-এর ফল uid ধরে ৬০–১২০ সেকেন্ড Redis/মেমরিতে রাখা
   (অ্যাকাউন্ট বন্ধ/মোছার সময় সেই cache মুছে দেওয়া)। আর Firebase-এর নিজের সমস্যায় (`IdentityException.Kind.OTHER`,
   429/5xx) **401 নয়, 503** ফেরানো আর কারণ লগে লেখা — নইলে Google-এর সাময়িক সমস্যায় অ্যাপ সবাইকে "আবার লগইন করুন" দেখায়।
2. **nginx**: `upstream lifolo_api { server 127.0.0.1:8080; keepalive 64; }` আর `proxy_pass http://lifolo_api;` (এখন প্রতি
   অনুরোধে নতুন TCP — ৮০০ rps-এ ১,৬০০+ সংযোগ); `worker_connections 4096` + `worker_rlimit_nofile 16384` (এখন ৭৬৮, nginx-এর
   open files ১০২৪ — ৮০০ rps-এ :443-এ ১,৮০০+ সংযোগ, সীমার কাছে); `/api/`-এ IP-প্রতি `limit_req` (যেমন ২০ r/s, burst ৪০) —
   origin সরাসরি খোলা বলে; `/api/`-এর জন্য `error_page 502 503 504` → ছোট JSON (`{"error":"সার্ভার চালু হচ্ছে…"}`,
   `Retry-After: 15`) — restart/deploy-এর ~২০ s-এ অ্যাপ HTML 502 পায়।
3. **origin শুধু Cloudflare-এর জন্য**: ufw-এ 80/443 শুধু Cloudflare-এর ঠিকানা থেকে (বা Cloudflare Authenticated Origin Pulls)।
   তখন CI-র সরাসরি-VPS পরীক্ষাগুলো (deploy-এর /etc/hosts, uptime-এর fallback, এই load test) SSH tunnel দিয়ে করতে হবে।
4. **JVM**: `-Xmx1g` → `-Xms1g -Xmx3g` (১২ GB-এর ~১০ GB খালি; ৮০০ rps-এ RSS ১.৬ GB, heap সীমায় ছিল); G1 (ডিফল্ট) রাখা,
   `-XX:+ExitOnOutOfMemoryError` রাখা।
5. **PostgreSQL**: `shared_buffers 128MB` → `2GB`, `effective_cache_size 4GB` → `6GB`, `work_mem 4MB` → `8MB`। ডাটাবেস এখন
   ১৭ MB আর পুল ২০-এর ≤৪ ব্যবহার — তাই তাড়া নেই; Hikari পুল ২০ আর `max_connections 100` যেমন আছে ঠিক।
6. **Redis**: `maxmemory 0` (সীমা নেই) → `maxmemory 512mb` + `maxmemory-policy volatile-lru`।
7. **সতর্কতার সীমা** (uptime workflow-এ যোগ করা হয়নি, প্রস্তাব): ডিস্ক ৮০% সতর্ক / ৯০% জরুরি, inode ৮০/৯০%, খালি RAM <১৫% / <৫%,
   swap >৫১২ MB / >১.৫ GB, সার্টিফিকেট <২১ দিন বাকি, শেষ ব্যাকআপ >২৬ ঘণ্টা পুরোনো, JobRunr-এর daily-maintenance >২৬ ঘণ্টা।
   সস্তা পথ: **Ops: disk and RAM check**-কে সাপ্তাহিক schedule (এখন সব হাতে)।
8. ছোট: `apt-get clean` (৪৭৯ MB); `cloud-init.service`-এর ব্যর্থতা দেখা (`cloud-init status --long`) — নেটওয়ার্ক না বুঝে বন্ধ নয়;
   lifolo.com/api-তে Referrer-Policy যোগ।

## কীভাবে আবার চালাবেন

| workflow | কী করে | সাইটে প্রভাব | কতক্ষণ |
|---|---|---|---|
| **Ops: load test** | GitHub-এর মেশিন থেকে সরাসরি VPS-এ (api.lifolo.com → VPS-এর IP; Cloudflare-এর পাশ কাটিয়ে) ধাপে ধাপে চাপ বাড়ায়; p95 > 1 s বা ত্রুটি > 1% হলে থামে; VPS-এ প্রতি ২ সেকেন্ডে CPU/RAM/Java/DB-সংযোগ মাপে | চলার সময় সাইট ধীর হতে পারে; শেষে ডেমোর বানানো সব সারি মোছে | ১০-১৫ মিনিট |
| **Ops: restart drill** | VPS **রিবুট** (confirm-এ `REBOOT` লিখতে হয়), তারপর https health কত সেকেন্ডে সবুজ আর সব সার্ভিস নিজে চালু হলো কি না | ~৩৫-৪৫ সেকেন্ড সাইট বন্ধ | ~৫ মিনিট |
| **Ops: crash drill** | অ্যাপের Java প্রসেস `kill -9` (confirm-এ `KILL`), systemd আবার চালায় কি না, কত সেকেন্ডে health সবুজ | ~২০ সেকেন্ড API বন্ধ | ~৩ মিনিট |
| **Ops: disk and RAM check** | ডিস্ক/inode/RAM/swap, কোন ফোল্ডারে কত, ডাটাবেস আর ব্যাকআপের আকার, journald/logrotate | নেই (শুধু দেখা) | ১ মিনিট |
| **Ops: SSL and Cloudflare check** | সার্টিফিকেটের মেয়াদ, nginx প্রতিটা নামে কোনটা দেখায় আর যাচাই হয় কি না, `certbot renew --dry-run`, Cloudflare-এর মোড, HSTS আর নিরাপত্তা-হেডার | নেই (dry-run Let's Encrypt-এর staging-এ) | ২ মিনিট |
| **Ops: timers and jobs check** | systemd timer, দৈনিক ব্যাকআপ, cron, JobRunr-এর কাজ গত ২৪ ঘণ্টায় চলেছে কি না; ঘড়ি; GitHub-এর schedule | নেই | ১ মিনিট |
| **Ops: log retention** | `deploy/log-retention.sh`: journald ≤২১ দিন/১ GB, nginx ≤১৪ দিন, বাকি লগ ≤২১ দিন (বারবার চালালেও একই) | নেই | ১ মিনিট |

- **load test**-এর ইনপুট: `rates` (ধাপ, লগইন-করা অনুরোধ/সেকেন্ড; ডিফল্ট `5 10 20 40 60 80 120 160 200 250 300`),
  `stage_seconds` (ডিফল্ট ৬০), `amb_cycles` (অ্যাম্বুলেন্স ডাকা → বাতিল কতবার, ০-৩; ডেমো চালকদের ফোনে নোটিফিকেশন যায়),
  `limiter_check` (শেষে অতিথির সীমা পরীক্ষা)। উদ্ধার: `cleanup_since` = `snapshot` দিলে শেষ চালানোর snapshot দিয়ে আবার
  পরিষ্কার; snapshot হারালে সেই চালানোর শুরুর সময় (UTC) আর `quota_fix` (`role:n,…`, লগের `WROTE blood` গুনে) — তখন টেস্ট চলে না।
- load test origin-এ সরাসরি যায়, অথচ origin শুধু Cloudflare-এর জন্য খোলা — তাই চলার সময়টুকু GitHub-এর মেশিনের ঠিকানার জন্য
  সাময়িক ছিদ্র (`deploy/ci/origin-access.sh open 60`: ufw + nginx-এর IP-সীমার বাইরে; শেষ ধাপে বন্ধ, না হলে ৬০ মিনিট পরে VPS
  নিজে বন্ধ করে)। restart drill-ও তাই (রিবুটে ssh tunnel ছিঁড়ে যায়)। বাকি সব সরাসরি-VPS পরীক্ষা SSH tunnel দিয়ে।
- ধাপ বাড়ানোর সময় সাবধান: আগে ~৮০০ rps-এ Firebase-এর revocation-কল ব্যর্থ হতো (আসল ব্যবহারকারীরাও 401 পেত); backend-এর
  `6ce3da9`-এর পরে প্রতি অনুরোধে Google-এ যাওয়া নেই — নতুন সীমা ওপরের "টিউনিং" অংশে।
- `log_branch` ইনপুট (load test, restart, crash, ssl, uptime): ফল অন্য log branch-এ (যেমন `ci-log-ops-tuning`)।
- load test, restart আর crash `vps-admin` concurrency group-এ — Deploy backend চলার সময় অপেক্ষা করে (deploy অ্যাপ restart
  করে, মাপ নষ্ট হতো)। একই group-এ দুটো অপেক্ষায় থাকলে GitHub আগেরটা বাতিল করে — তাই deploy-এর ঠিক আগে-পরে চালাবেন না।
- কখন: load test আর drill-গুলো বড় বদলের পরে (নতুন JVM/পুল/nginx সেটিং, বড় feature) আর মাসে একবার; disk/ssl/timers যেকোনো সময়।
- হাতে VPS-এ: `bash /root/lifolo-ops/vps-ops.sh disk|ssl|timers|services|config|health` (workflow প্রতিবার ফাইলটা নতুন করে পাঠায়);
  `lifolo-tune status` (সেটিং আর ফায়ারওয়ালের অবস্থা)।

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
| `deploy/log-retention.sh` | লগ কতদিন থাকে (journald, logrotate) — VPS-এ, idempotent |
| `deploy/tune-vps.sh` | ক্ষমতার সেটিং (JVM, PostgreSQL, Redis, nginx) আর origin-এর পাহারা (Cloudflare-এর ঠিকানা → realip + ufw, certbot hook, guard timer) — Setup VPS আর প্রতিটা Deploy backend চালায়; VPS-এ `/usr/local/sbin/lifolo-tune` |
| `deploy/ci/origin-access.sh` | GitHub-এর মেশিন থেকে origin: SSH tunnel (iptables DNAT) বা সাময়িক ছিদ্র (load test, restart drill) |
| `.github/workflows/uptime.yml` | প্রতি ৩ ঘণ্টা: /api/health + `vps-ops.sh health` (সতর্কতার সীমা) → ব্যর্থ হলে মালিককে ইমেইল |
