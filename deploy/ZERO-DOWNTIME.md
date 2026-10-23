# বিরতি ছাড়া deploy (blue/green)

আগে "Deploy backend" একটাই JVM `systemctl restart` করত — প্রতি deploy-এ ১–২ মিনিট ব্যবহারকারীরা 502/503 পেতেন।
এখন দুটো জায়গা: **`lifolo-api@8080`** আর **`lifolo-api@8081`** (একই template `lifolo-api@.service`, instance = পোর্ট)।
একসময়ে একটাই live; deploy নতুন release চালায় যেটা চলছে না সেখানে, তৈরি হলে nginx ওদিকে ঘোরায়, তারপর আগেরটা বন্ধ।

স্ক্রিপ্ট: `deploy/deploy-backend.sh` (Deploy backend workflow চালায়)। পরীক্ষা: `.github/workflows/deploy-bluegreen-test.yml`।

## ধাপগুলো (প্রতি deploy)

1. **jar** → `/opt/lifolo/api/releases/app-<sha12>.jar` (version-সহ; শেষ ৫টা থাকে)। প্রতিটা instance চালায়
   `/opt/lifolo/api/app-<port>.jar` (একটা release-এ symlink); `app.jar` = live release (পুরোনো অভ্যাসের জন্য)।
2. **নতুন instance চালু** (যে পোর্ট খালি) — আগেরটা চলতেই থাকে। চালুর সময় **Flyway migration** চলে, তারপর
   `/api/health`-এর অপেক্ষা (প্রথমবার ৪ মিনিট পর্যন্ত)।
3. **health না এলে**: নতুনটা বন্ধ, nginx-এ কিছুই বদলায় না, আগের release যেমন ছিল চলে — deploy লাল (আজকের "আগের jar-এ
   ফেরা"-র মতোই ফল, কিন্তু কোনো বিরতি নেই)। Summary-তে `app_error`-এ প্রথম কারণ।
4. **switch**: `/etc/nginx/conf.d/lifolo-api-upstream.conf`-এ `upstream lifolo_api` → নতুন পোর্ট, `systemctl reload nginx`
   (graceful — পুরোনো worker হাতের অনুরোধ শেষ করে তবেই বেরোয়)। `/etc/lifolo/api-port` = নতুন পোর্ট; রিবুটে শুধু ওটাই চালু
   (`systemctl enable`)।
5. **JobRunr হাতবদল** (নিচে) → **drain**: আগেরটা আরও ২০ s চলমান অনুরোধ শেষ করে → `systemctl stop` (Spring-এর graceful
   shutdown, ২০ s পর্যন্ত) → disable।
6. switch-এর পুরো সময়টা deploy নিজেই এই মেশিন থেকে `/api/health` আর একটা আসল API action (অতিথির `features`) বারবার
   চালায় — Summary-র **"switch-এর সময় 5xx"** সারি (`switch_probe`, `switch_5xx` — লক্ষ্য **০**)। ফল `ci-log-deploy`
   শাখার `deploy-status.txt`-এও।

প্রথম blue/green deploy পুরোনো এক-instance `lifolo-api.service` (8080) থেকেই একই ভাবে সরে আসে (নতুনটা 8081-এ), তারপর
পুরোনো unit `/etc/lifolo/legacy/`-এ সরিয়ে রাখে।

মেমরি কম থাকলে (`MemAvailable` < ১৬০০ MB — দুটো JVM একসাথে চালানো নিরাপদ নয়) বা আগেরটা অসুস্থ হলে deploy পুরোনো
ধাঁচে চলে (আগেরটা আগে বন্ধ) — Summary-তে `switch` সারিতে কারণসহ লেখা থাকে।

## ⚠️ নিয়ম: migration আগের release-এর সাথেও চলতে হবে

**Migration must be backward compatible with the previous release.**

নতুন release চালুর সময় Flyway migration চালায়, কিন্তু **আগের release তখনো চলছে** (switch আর drain পর্যন্ত, ~৩০ s–৪ মিনিট)
আর একই ডাটাবেস ব্যবহার করছে। health ব্যর্থ হলে আগের release-ই থেকে যায় — migration কিন্তু থেকে যায়। তাই:

- ✅ চলবে: নতুন টেবিল; নতুন column যেটা `NULL` হতে পারে বা যার `DEFAULT` আছে; নতুন index (বড় টেবিলে
  `CREATE INDEX CONCURRENTLY` আলাদা migration-এ); নতুন enum/CHECK মান **যোগ** (আগের মানগুলো থাকে); নতুন সারি/ডেটা।
- ❌ এক release-এ নয়: column/টেবিল **মোছা বা নাম বদলানো**; `NOT NULL` column default ছাড়া; column-এর type বদলানো;
  CHECK-এ আগের কোনো মান বাদ দেওয়া; আগের কোড যে মান লেখে তা আর না মানা।
- এসবের জন্য দুই ধাপ (expand → contract): **release ১** নতুন column যোগ করে আর কোড দুটোই সামলায় (নতুনটায় লেখে, পুরোনোটা
  পড়তে পারে); **release ২** (আগেরটা আর কোথাও চলছে না) তারপর পুরোনোটা মোছে।
- migration ছোট রাখুন — বড় টেবিলে লম্বা lock মানে আগের release-এর অনুরোধও আটকে থাকে।

## JobRunr: একসময়ে একটাই instance কাজ চালায়

সব background কাজ JobRunr-এর `@Recurring` (Spring-এর `@Scheduled` নেই): ambulance-timers (প্রতি মিনিট), call-timeouts
(১৫ s), storage-trash, demo-refresh, pay-jobs, online-unserved, দৈনিক কাজ, রসিদের সারাংশ।

**সিদ্ধান্ত: নতুন instance কাজ বন্ধ রেখে চালু হয়, switch-এর পরে deploy হাতবদল করে** (`backend/.../jobs/JobsGate.kt`):

1. নতুন instance চালু হয় `LIFOLO_JOBS_HOLD=true` নিয়ে (`/run/lifolo/api-<port>.env`, শুধু এই চালুর জন্য) — তার
   JobRunr server বানানো হয় কিন্তু চালু নয় (কোনো কাজ নেয় না, master হয় না); HTTP স্বাভাবিক।
2. switch-এর পরে deploy `POST /api/cron/jobs?do=stop` আগেরটায় (তার JobRunr থামে, servers টেবিল থেকে সরে) →
   `POST /api/cron/jobs?do=start` নতুনটায়। মাঝে কয়েক সেকেন্ড কেউ কাজ চালায় না — sweep-গুলো ১৫ s–১ মিনিট পরপর, পরের বার
   ধরে ফেলে। `/run/lifolo/api-<port>.env` মুছে দেওয়া হয় — পরে crash/রিবুটে instance নিজেই কাজ চালু করে।
3. আগেরটা (পুরোনো এক-instance release-এর এই endpoint নেই) হলে: নতুনটার কাজ চালু আগেরটা বন্ধ হওয়ার পরে।

কেন JobRunr-এর নিজের ডাটাবেস-lock-এর ভরসায় নয়? দুটো server একই ডাটাবেসে চালানো JobRunr-এ চলে (একটা কাজ একবারই চলে) —
দুবার চলার ভয় নেই। কিন্তু পাশাপাশি চলার সময় **পুরোনো release (পুরোনো কোড)** নতুন release-এর রাখা কাজ ধরে ফেলতে পারে,
পুরোনোটা master থেকে পুরোনো সংজ্ঞায় recurring কাজ বানাতে পারে, আর বন্ধ হওয়ার ঠিক আগে নেওয়া কাজ মাঝপথে কেটে গিয়ে
JobRunr-এর orphan timeout (~১ মিনিট) পরে আবার চলে। হাতবদলে সবসময় একটাই instance — আর সেটা বর্তমান release।

দেখা: `curl -s -X POST -H "Authorization: Bearer $(sed -n 's/^CRON_SECRET=//p' /etc/lifolo/app.env)" "$(lifolo-api url)/api/cron/jobs"`
→ `{"ok":true,"server":"running","master":true}` (Summary-র "JobRunr" সারি)।

## nginx

- `upstream lifolo_api { server 127.0.0.1:<live> max_fails=0; server 127.0.0.1:<অন্যটা> backup max_fails=0; keepalive 64; }`
  আর প্রতিটা অ্যাপ-পথে `proxy_next_upstream error timeout http_502` (২ বার, ১০ s-এর মধ্যে)। live-এ **সংযোগই না হলে**
  অনুরোধ অন্যটায় যায় — drain-এর ২০ s আগেরটা তখনো চলে, তাই reload-এর মুহূর্তেও 502 নয়। পরে ওটা বন্ধ: backup-এ গেলেও
  সাথে সাথে "refused" → নিচের JSON 503।
  - নিরাপদ কেন: POST একবার অ্যাপে পৌঁছে গেলে nginx আর দ্বিতীয়বার পাঠায় না (`non_idempotent` নেই) — দুবার লেখা হয় না;
    `max_fails=0` তাই একটা ধীর অনুরোধের timeout-এ live পোর্টকে "অচল" ধরে সব অনুরোধ backup-এ পাঠানো হয় না; backup =
    আগের release, যা নতুন schema-তেও চলে (ওপরের migration-নিয়ম)।
- দুটো instance-ই অচল হলে (crash-এর পরে restart, মেমরি কম): nginx-এর নিজের 502/503/504 → **JSON 503 + `Retry-After: 15`**
  (`{"error":"সার্ভার এই মুহূর্তে চালু হচ্ছে — কয়েক সেকেন্ড পর আবার চেষ্টা করুন। …","code":"server_unavailable","retry":true}`)
  — অ্যাপ আর ওয়েব "আবার চেষ্টা করুন" দেখায়।
- **`127.0.0.1:8088`** = VPS-এর ভেতরের স্থির ঠিকানা (`/etc/nginx/conf.d/lifolo-api-local.conf`) → যেটা live, `/api/cron/*` সহ।
  cron/R2/Sentry test আর LiveKit-এর webhook (এই VPS-এ চললে) এটা ব্যবহার করে — পোর্ট বদলালেও একই। বাইরের সাইটে
  `/api/cron/` আগের মতোই 404।

## SSE (`/api/events`)

আগের instance বন্ধ হওয়ার শুরুতেই (`ContextClosedEvent`) সব খোলা ধারা বন্ধ করে — অ্যাপ/ওয়েব নিজেই আবার সংযোগ করে, তখন
নতুনটায় (nginx ততক্ষণে ঘুরে গেছে)। Tomcat-এর graceful shutdown ৩০ মিনিটের ধারার জন্য অপেক্ষা করে না।

## RAM (১২ GB VPS)

- JVM: `-Xms1g -Xmx3g` (tune-vps.sh, `lifolo-api@.service.d/20-tuning.conf`), প্রতি instance `MemoryMax=4608M`। লোড-টেস্টে
  RSS ~৫৫০ MB ছিল (তখন `-Xmx1g`)।
- দুটো JVM একসাথে শুধু নতুনটা চালু থেকে আগেরটা বন্ধ পর্যন্ত (~১–৪ মিনিট); নতুনটা ১ GB heap দিয়ে শুরু। deploy আগে দেখে
  `MemAvailable` ≥ ১৬০০ MB; না হলে পুরোনো ধাঁচে (আগেরটা আগে বন্ধ)।
- বাকিরা: PostgreSQL `shared_buffers` 2 GB, Redis 512 MB, nginx/LiveKit অল্প। দুটো Hikari pool (২০ + ২০) < PostgreSQL-এর
  `max_connections` 100।
- GC লগ আলাদা: `/var/log/lifolo/gc-8080.log`, `gc-8081.log`।

## হাতে কাজ (VPS-এ root)

```bash
lifolo-api unit                     # এখন কোনটা live: lifolo-api@8080.service বা lifolo-api@8081.service
lifolo-api port                     # 8080 / 8081
curl -s "$(lifolo-api url)/api/health"; echo
journalctl -u 'lifolo-api@*' -f      # দুটোরই লগ
systemctl restart "$(lifolo-api unit)"   # এক-instance-এর মতো restart (বিরতি হয়!) — সাধারণত লাগে না
ls -lt /opt/lifolo/api/releases/     # রাখা release-গুলো
```

### হাতে আগের release-এ ফেরা

সবচেয়ে সহজ: GitHub → Actions → **Deploy backend** → Run workflow → আগের commit-এর শাখা/ট্যাগ — এটাও blue/green, বিরতি নেই।

জরুরি হলে VPS-এ (live ধরা যাক 8081, আগের release-এর jar `releases/app-<আগের>.jar`):

```bash
ln -sfn releases/app-<আগের>.jar /opt/lifolo/api/app-8080.jar
systemctl start lifolo-api@8080
until curl -fs http://127.0.0.1:8080/api/health; do sleep 2; done
sed -i 's/127.0.0.1:8081 max_fails=0;/127.0.0.1:8080 max_fails=0;/; s/127.0.0.1:8080 backup/127.0.0.1:8081 backup/' /etc/nginx/conf.d/lifolo-api-upstream.conf
nginx -t && systemctl reload nginx
echo 8080 > /etc/lifolo/api-port && systemctl enable lifolo-api@8080 && sleep 20
systemctl disable --now lifolo-api@8081
```

(এখানে JobRunr-এর কোনো হাতবদল নেই — ২০ s দুটোই কাজ চালাতে পারে; JobRunr-এর ডাটাবেস-lock দুবার চলা আটকায়।)

## CI-তে পরীক্ষা

`.github/workflows/deploy-bluegreen-test.yml` (deploy/ বা এই স্ক্রিপ্ট বদলালে, আর হাতে): GitHub-এর মেশিনে PostgreSQL,
Redis, nginx (https) আর systemd দিয়ে VPS-এর মতো সাজিয়ে —

1. আজকের পুরোনো এক-instance deploy (`main`-এর আগের deploy-backend.sh) → চালু অবস্থা
2. load চলার মধ্যে নতুন deploy-backend.sh: এক-instance → blue/green (8081), তারপর আবার (8081 → 8080), তারপর ভাঙা jar
   (ব্যর্থ হতে হবে, আগেরটা চলতেই থাকে)
3. পুরো সময় `/api/health` আর একটা আসল API action (POST `/api/community`) বারবার — **একটাও 5xx নয়**; প্রতি ধাপের পরে
   ঠিক একটা instance চালু আর তাতে JobRunr `running`।
