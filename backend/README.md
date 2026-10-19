# Lifolo ব্যাকএন্ড (Kotlin + Spring Boot)

Node.js সার্ভারের (`server/`) জায়গায় নতুন সার্ভার। লক্ষ্য: **API হুবহু একই থাকবে** — একই path, একই `action`,
একই JSON আকার, একই status code আর একই বাংলা/ইংরেজি ত্রুটির লেখা। তাই Android অ্যাপ আর React ওয়েব শুধু
BASE_URL বদলালেই নতুন সার্ভারে চলবে, আর পুরোনো e2e টেস্ট দিয়েই মিলিয়ে দেখা যাবে।

| | |
|---|---|
| ভাষা ও ফ্রেমওয়ার্ক | Kotlin 2.3, Spring Boot 3.5, Java 21 |
| ডাটাবেস | PostgreSQL 16 (Flyway migration), Redis 7 (cache, rate limit) |
| লগইন | Firebase Auth (শুধু টোকেন যাচাই আর custom token), পুশ: FCM |
| ফাইল / ইমেইল | Cloudflare R2 (S3 API) / Amazon SES |
| ব্যাকগ্রাউন্ড কাজ | JobRunr (PostgreSQL-এ রাখে) |
| ত্রুটি দেখা | Sentry (`SENTRY_DSN` না দিলে বন্ধ) |

**অবস্থা (ধাপ ১ + P6-kernel):** ভিত্তি তৈরি — `/api/health`, `/api/auth/send-code`, `/api/auth/verify-code` (ডেমো লগইন আর ওয়েব প্যানেলের
কুকিসহ), `/api/community`-র dispatcher (অতিথি, ওয়েব প্যানেল, কেয়ারগিভার, অ্যাডমিন লগ — Node-এর মতো একই ক্রমে),
`profile_get`, `profile_update`, `web_logout`, পুরো ডাটাবেস স্কিমা, আর সবার ব্যবহারের kernel ও contracts (§৮)। বাকি ২২২টা action ধাপ ২-এ —
[docs/PHASE2-PLAN.md](docs/PHASE2-PLAN.md)।

দরকারি কাগজ: [docs/API-INVENTORY.md](docs/API-INVENTORY.md) (প্রতিটা endpoint আর action-এর তালিকা),
[docs/SCHEMA.md](docs/SCHEMA.md) (টেবিল আর Firestore → টেবিলের মিল), [docs/PHASE2-PLAN.md](docs/PHASE2-PLAN.md)।

---

## ১. ফোল্ডার ম্যাপ (Laravel-এর সাথে তুলনা)

```
backend/
├── build.gradle.kts            ← composer.json-এর মতো: dependency আর build-এর নিয়ম
├── settings.gradle.kts
├── gradlew, gradle/wrapper/    ← Gradle নিজে নামিয়ে নেয় (আলাদা install লাগে না)
├── src/main/resources/
│   ├── application.yml         ← config/*.php + .env-এর নাম (মানগুলো env var থেকে আসে)
│   ├── db/migration/V1__…sql   ← database/migrations (Flyway)
│   └── data/*.json             ← জেলা/উপজেলা, হাসপাতালের তালিকা, ওষুধের তালিকা (Node থেকে কপি)
├── src/main/kotlin/com/lifolo/
│   ├── LifoloApplication.kt    ← public/index.php + bootstrap/app.php: এখান থেকে অ্যাপ চালু হয়
│   ├── common/                 ← সবার ব্যবহারের জিনিস (app/Support-এর মতো)
│   │   ├── action/             ← /api/community: CommunityController, ActionDispatcher, ActionRegistry, ActionPolicy
│   │   ├── ApiException.kt     ← abort(403, '…')-এর মতো
│   │   ├── ApiErrorHandler.kt  ← app/Exceptions/Handler.php: Node-এর {error, code} আকারে উত্তর
│   │   ├── Lang.kt, I18n.kt    ← বাংলা/ইংরেজি বার্তা (lang/ ফোল্ডারের কাজ)
│   │   ├── Body.kt             ← $request->input()-এর মতো, কিন্তু JavaScript-এর নিয়মে (typeof, Number.isInteger)
│   │   ├── QuotaService.kt     ← দিনে কয়বার করা যায় (RateLimiter::attempt-এর মতো, কিন্তু DB-তে)
│   │   ├── RateLimiter.kt      ← Bucket4j + Redis (অতিথির IP-প্রতি সীমা)
│   │   ├── Places.kt, Geo.kt, DhakaTime.kt, Ids.kt, Sql.kt, …
│   │   └── HealthController.kt
│   ├── auth/                   ← Firebase টোকেন যাচাই (Spring Security filter), ইমেইল-কোড লগইন, ওয়েব সেশন, অ্যাডমিন
│   ├── users/                  ← accounts, প্রোফাইল (UserProfile = Eloquent model-এর মতো JPA entity), মোছা বাতিল
│   ├── notify/                 ← পুশ + inbox (PushService), ইমেইল (SES), SSE hub
│   ├── care/                   ← কেয়ারগিভার (রোগীর হয়ে কাজ)
│   ├── admin/                  ← অ্যাডমিন লগ; admin/kernel/: রিপোর্ট, নিষেধাজ্ঞা, কাগজ যাচাই, অনুমোদন, ফোনের হিসাব, তালিকা (সবার জন্য)
│   ├── contracts/              ← এক প্যাকেজ আরেকটার যা ব্যবহার করে তার interface (+ অস্থায়ী class, TODO(Pn))
│   ├── demo/                   ← ডেমো জগৎ (demo- uid), ডেমো লগইন
│   ├── jobs/                   ← দৈনিক কাজ (app/Console/Kernel.php-এর schedule) — JobRunr + /api/cron/daily
│   └── blood/ appointments/ rx/ pharmacy/ books/ lab/ ambulance/ homecare/ content/
│                               ← ধাপ ২-এর মডিউল (এখন শুধু কী আসবে তার নোট)
└── src/test/kotlin/…           ← tests/ (Feature = *IT.kt, Unit = unit/)
```

Laravel ↔ Spring শব্দকোষ:

| Laravel | এখানে |
|---|---|
| Route + Controller | `@RestController` ক্লাস (`CommunityController`, `AuthController`) |
| একটা route group-এর অনেক route | `ActionModule` — একটা মডিউলের সব `action` (নিচে দেখুন) |
| Service class, `app()->make()` | `@Service` / `@Component` ক্লাস; constructor-এ চাইলেই Spring দিয়ে দেয় (dependency injection) |
| Eloquent model | JPA `@Entity` (যেমন `users/UserProfile.kt`) অথবা সরাসরি SQL (`JdbcTemplate`) |
| `DB::transaction()` | মেথডের ওপর `@Transactional`; লক: `SELECT … FOR UPDATE` |
| Migration | Flyway `V{n}__name.sql` |
| Middleware | Spring Security filter (`auth/SecurityConfig.kt`) |
| `.env` / `config()` | env var → `application.yml` → `LifoloProperties` |
| Cache facade | Spring Cache (`@Cacheable`) — Redis-এ |
| Scheduler / Queue | JobRunr (`@Recurring`), পরে delayed job |
| PHPUnit + RefreshDatabase | JUnit 5 + Testcontainers (আসল PostgreSQL আর Redis Docker-এ) |

## ২. একটা অনুরোধ কীভাবে চলে

অ্যাপ প্রায় সব কাজ একটা endpoint-এ পাঠায়: `POST /api/community`, body-তে `{"action": "profile_get", "lang": "bn", …}`।

```
অ্যাপ ──► nginx ──► Spring Boot
            1. FirebaseTokenFilter (auth/SecurityConfig.kt)
               "Authorization: Bearer <টোকেন>" থাকলে Firebase দিয়ে যাচাই করে ফল request-এ রেখে দেয়
               (নিজে কাউকে আটকায় না — কোন কাজে লগইন লাগে তা action ঠিক করে)
            2. CommunityController → ActionDispatcher (common/action/CommunityController.kt), Node-এর ক্রমেই:
               ক) টোকেন থাকলে → ব্যবহারকারী (ভুল/বন্ধ/মোছার অপেক্ষায় হলে 401/403, ঠিক Node-এর বার্তায়)
               খ) টোকেন নেই কিন্তু অ্যাডমিন প্যানেলের কুকি → শুধু admin_* কাজ
               গ) কিছুই নেই → অতিথি: শুধু ১৫টা পড়ার কাজ, IP-প্রতি মিনিটে ২৪০
               ঘ) action না চিনলে → 400 "অজানা অনুরোধ। অ্যাপ আপডেট করুন।"
               ঙ) body.asUid → কেয়ারগিভার রোগীর হয়ে (অনুমোদিত কাজে, চালু সংযোগে)
               চ) ActionRegistry থেকে handler খুঁজে চালানো → উত্তর JSON
               ছ) admin_* কাজ হলে admin_log-এ লেখা
            3. ভুল হলে ApiException → ApiErrorHandler → {"error": "…", "code": "…"} (lang অনুযায়ী)
```

অন্য endpoint (`/api/auth/*`, `/api/health`, `/api/cron/daily`, ধাপ ২-এ `/api/respond` ইত্যাদি) সাধারণ controller।

**ভাষার নিয়ম (খুব জরুরি):** Node-এ দুই রকম নিয়ম আছে, দুটোই রাখা হয়েছে —
`Lang.tr(lang, en, bn)` বাংলা দেয় শুধু `lang == "bn"` হলে (action-এর ত্রুটি); `Lang.pick(lang, en, bn)` ইংরেজি দেয় শুধু
`lang == "en"` হলে (পুশের লেখা)। পুরোনো endpoint-এর বাংলা বার্তা `lang == "en"` হলে `I18n.toEnglish` ইংরেজি করে।
বাংলা লেখা **Node কোড থেকে কপি করুন** — "য়" দুইভাবে লেখা যায় (একটা অক্ষর বা য + ়), দেখতে একই কিন্তু মেলে না।

## ৩. নতুন action যোগ করা

ধরুন `blood` মডিউলে `blood_requests_mine` বসাবেন:

```kotlin
package com.lifolo.blood

@Component
class BloodActions(private val requests: BloodRequestService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "blood_requests_mine" to ActionHandler { ctx -> requests.mine(ctx.user.id) },
    )
}

@Service
class BloodRequestService(private val jdbc: JdbcTemplate) {
    @Transactional(readOnly = true)
    fun mine(uid: String): Map<String, Any?> = mapOf("items" to jdbc.query(…))
}
```

নিয়ম:
1. `@Component` + `ActionModule` — ব্যস, `ActionRegistry` নিজেই খুঁজে নেয়। একই নাম দুবার হলে অ্যাপ চালুই হবে না।
2. `ctx.user` = যার হয়ে কাজ (কেয়ারগিভার হলে রোগী), `ctx.caller` = আসলে কে ডাকছেন। অতিথিদের `uid` নেই
   (শুধু `ActionPolicy.PUBLIC_ACTIONS`-এর কাজে)।
3. body পড়তে `ctx.body.string("x")`, `ctx.body.int("x")`, `ctx.body.isTrue("x")` — এগুলো JavaScript-এর মতো উত্তর দেয়
   (Node-এর `typeof`, `Number.isInteger`, `=== true`)। নিজে `asText()` ব্যবহার করবেন না, ফল আলাদা হবে।
4. ত্রুটি: `throw ctx.err(404, "Not found", "পাওয়া যায়নি")` (code লাগলে চতুর্থ প্যারামিটার)।
5. উত্তর: `Map`/data class। সময় **মিলিসেকেন্ডে সংখ্যা** (`instant.toEpochMilli()`), কারণ অ্যাপ সংখ্যা পড়ে।
   `null` ফেরত দিলে `{ok: true}` যায়।
6. দিনের সীমা: `quotas.use(ctx.lang, uid, "ask")`। পুশ: `push.send(…)`, `push.pushUsers(…)`, `push.pushPatient(…)`।
7. ডেমো আর আসল আলাদা: `DemoWorld.sameWorld(a, b)` বা টেবিলের `is_demo` কলাম।
8. Node টেস্ট (server/test/*.test.js) থেকে টেস্ট পোর্ট করুন, `IntegrationTest` থেকে extend করে
   (`call(uid, "action", mapOf(...))` — Node-এর `call()`-এর মতোই)।
9. [docs/API-INVENTORY.md](docs/API-INVENTORY.md)-এ ওই action-এ টিক দিন।

## ৪. Migration (Laravel-এর সাথে পার্থক্য)

* ফাইল: `src/main/resources/db/migration/V8__add_something.sql` — নামের সংখ্যা ক্রমে বাড়বে, দুটো `_` (ডাবল আন্ডারস্কোর)।
* লেখা হয় **সাধারণ SQL-এ** (PostgreSQL), PHP-তে নয়।
* অ্যাপ চালু হওয়ার সময় Flyway নিজেই না-চালানো migration চালায় (`php artisan migrate` আলাদা করে লাগে না);
  টেস্টও একই migration দিয়ে খালি ডাটাবেস বানায়।
* **`down()` নেই।** ভুল হলে নতুন migration লিখে ঠিক করুন। একবার চালানো (main-এ যাওয়া) migration ফাইল কখনো বদলাবেন না —
  Flyway checksum মিলিয়ে দেখে, বদলালে অ্যাপ চালু হবে না।
* কোন migration চলেছে: ডাটাবেসের `flyway_schema_history` টেবিল (Laravel-এর `migrations` টেবিলের মতো)।
* `ddl-auto: none` — Hibernate নিজে কোনো টেবিল বানায়/বদলায় না; সব বদল migration দিয়েই।
* নিয়ম: id `TEXT`, সময় `TIMESTAMPTZ`, অবস্থা `TEXT + CHECK`, ডেমোর জন্য `is_demo` — বিস্তারিত [docs/SCHEMA.md](docs/SCHEMA.md)।

## ৫. চালানো আর টেস্ট

দরকার: Java 21, Docker (টেস্ট আর লোকাল ডাটাবেসের জন্য)।

```bash
cd backend

# সব টেস্ট (Testcontainers নিজে PostgreSQL 16 আর Redis 7 চালায় — Docker চালু থাকতে হবে)
./gradlew test
./gradlew test --tests '*ProfileIT'          # একটা ক্লাস
./gradlew build                              # compile + test + jar (build/libs/lifolo-backend-0.1.0.jar)

# লোকালে চালানো
docker run -d --name lifolo-pg -e POSTGRES_USER=lifolo -e POSTGRES_PASSWORD=lifolo -e POSTGRES_DB=lifolo -p 5432:5432 postgres:16-alpine
docker run -d --name lifolo-redis -p 6379:6379 redis:7-alpine
FIREBASE_SERVICE_ACCOUNT="$(base64 -w0 service-account.json)" ADMIN_EMAILS=you@gmail.com ./gradlew bootRun
curl localhost:8080/api/health
```

* টেস্টে Firebase, SES আর FCM নকল (`src/test/kotlin/com/lifolo/support/TestInfra.kt`): টোকেন = uid (Node-এর fakefb.js-এর মতো),
  ইমেইলের কোড `mail.lastCode(email)` দিয়ে পড়া যায়।
* প্রতিটা টেস্টের আগে সব টেবিল খালি আর Redis খালি হয়।
* GitHub Actions: `.github/workflows/backend.yml` — `spring` আর `spring-p*` branch-এ push করলে আর `backend/` বদলানো PR-এ
  আগে "য়" পরীক্ষা (`scripts/check-bangla.py`), তারপর `./gradlew build` চলে। লগ আর টেস্টের সারাংশ আলাদা branch-এ যায়
  (`status.txt`, `build.log`, `tests.txt`, `bangla.log`): `spring` → `spring-ci-log`, `spring-p3` → `ci-log-spring-p3` (নিচে §৮)।
* API-র তালিকা (OpenAPI): `/api/docs` (JSON), `/api/docs/ui`। স্বাস্থ্য: `/api/health`, `/actuator/health`।

## ৬. Env vars

| নাম | কী | Node-এ |
|---|---|---|
| `DATABASE_URL` | `postgres://user:pass@host:5432/lifolo` (অথবা নিচের তিনটা) | standalone-server-এ একই |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | JDBC রূপে ডাটাবেস (`jdbc:postgresql://…`) | — |
| `REDIS_URL` | `redis://[:pass@]host:6379[/db]` (ডিফল্ট `redis://localhost:6379`) | — |
| `FIREBASE_SERVICE_ACCOUNT` | service account JSON, অথবা তার base64 | একই |
| `ADMIN_EMAILS` | মালিকদের ইমেইল, কমা দিয়ে (খালি = `ekapkbd@gmail.com`) | একই |
| `CRON_SECRET` | `/api/cron/daily`-র চাবি (`Authorization: Bearer …`); ফোন-কোডের হ্যাশেও লাগবে | একই |
| `WEB_SESSION_SECRET` | অ্যাডমিন প্যানেলের কুকি সই করার চাবি (খালি হলে service account থেকে) | একই |
| `EMAIL_CODE_SECRET` | লগইন-কোডের HMAC চাবি (খালি হলে service account-এর private_key, Node-এর মতো) | নতুন |
| `SES_REGION`, `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`, `SES_FROM` | Amazon SES (না দিলে ইমেইল যায় না, শুধু লগ — production-এ অবশ্যই দিন) | `GMAIL_USER`, `GMAIL_APP_PASSWORD` |
| `R2_ACCOUNT_ID` বা `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET` | Cloudflare R2 (ছবি/কাগজ, ধাপ ২) | — |
| `SMS_URL_TEMPLATE` | SMS gateway-র GET লিংক (`{to}`, `{msg}`), খালি = ফোন যাচাই বন্ধ | একই |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT` | ত্রুটি পাঠানো (খালি = বন্ধ) | — |
| `PUBLIC_BASE_URL` | শেয়ার লিংক/QR-এর ঠিকানা | কোডে লেখা ছিল |
| `PORT` (৮০৮০), `DB_POOL_SIZE` (২০), `GUEST_RATE_PER_MINUTE` (২৪০), `JOBRUNR_SERVER_ENABLED` (true) | সার্ভারের সেটিং | `PORT` |

nginx-কে অবশ্যই `X-Forwarded-For` বসাতে হবে (`proxy_set_header X-Forwarded-For $remote_addr;`) — অতিথি আর লগইন-কোডের
IP-সীমা এটা দেখেই হয়।

## ৭. যা মনে রাখবেন

* **কোনো আচরণ "ভালো করার" আগে Node-এর সাথে মিল রাখুন।** উদ্দেশ্য: পুরোনো অ্যাপ বদল ছাড়াই চলবে। উন্নতি পরে, আলাদা ধাপে।
* Firestore-এর একটা transaction = এখানে একটা `@Transactional` মেথড + যে সারি বদলাবেন তাতে `FOR UPDATE`।
* Node-এর মেমোরি cache (memcache) → এখানে Redis/Spring Cache, কারণ একাধিক সার্ভার চলতে পারে।
* ডেমো (`demo-…` uid) আর আসল ব্যবহারকারী কখনো একে অপরকে দেখবেন না/নোটিফিকেশন পাবেন না।
* `server/`, `app/`, `web/` এই branch-এ বদলানো হয় না; cutover পর্যন্ত Vercel-এর Node সার্ভারই চালু।

## ৮. প্যাকেজ ভাগ করে একসাথে কাজ (ধাপ ২)

ধাপ ২-এর কাজ ছয় ভাগে (P1 রক্ত, P2 ডাক্তার/কন্টেন্ট/কেয়ারগিভার, P3 প্রেসক্রিপশন/ল্যাব, P4 ফার্মেসি/হিসাবের খাতা, P5 অ্যাম্বুলেন্স,
P6 প্ল্যাটফর্ম/অ্যাডমিন) — বিস্তারিত [docs/PHASE2-PLAN.md](docs/PHASE2-PLAN.md)। সবার লাগে এমন জিনিস (রিপোর্ট, নিষেধাজ্ঞা,
কাগজ যাচাই, অ্যাডমিনের অনুমোদন, ফোনের হিসাব, বাছাইয়ের তালিকা) আগেই তৈরি — `com.lifolo.admin.kernel`। এক প্যাকেজ আরেক
প্যাকেজের যা ব্যবহার করে তার interface `com.lifolo.contracts`-এ; পাশে একটা অস্থায়ী class (`TODO(Pn)`), যেটা মালিক প্যাকেজ
নিজের class দিয়ে বদলাবে। তালিকা: PHASE2-PLAN.md-এর "Contracts ready for parallel work"।

প্রতিটা প্যাকেজ নিজের branch-এ:

1. `spring` থেকে নিজের branch: `git fetch origin && git checkout -b spring-p3 origin/spring` (P1 হলে `spring-p1` …)।
2. শুধু নিজের প্যাকেজ, নিজের টেস্ট আর নিজের migration (`V300__p3_….sql` — P3 হলে ৩০০ থেকে) বদলান। অন্যের ফাইল নয়।
3. push করার আগে: `python3 backend/scripts/check-bangla.py` (বাংলা লেখা Node-এর ফাইল থেকে হুবহু কপি কি না)।
4. `git push origin spring-p3` — GitHub Actions নিজেই build আর সব টেস্ট চালায় (নিজের কম্পিউটারে Gradle/Docker লাগে না)।
   ফল পড়ুন `ci-log-spring-p3` branch থেকে:
   ```bash
   git fetch -q origin ci-log-spring-p3 && git show FETCH_HEAD:status.txt   # "<commit> success|failure spring-p3"
   git show FETCH_HEAD:tests.txt | head -40                                  # কতগুলো টেস্ট, কোনটা ভাঙল
   ```
   `status.txt`-এ নিজের শেষ commit না আসা পর্যন্ত অপেক্ষা করুন (`gh run list --branch spring-p3`)।
   `backend/`-এ কোনো বদল ছাড়া push করলে (যেমন নতুন branch যা `spring`-এর সমান) build চলে না — তখন হাতে চালান:
   `gh workflow run backend.yml --ref spring-p3`।
5. সবুজ (success) হলে: `git fetch origin && git rebase origin/spring` → আবার push (`--force-with-lease`) → আবার সবুজ হলে
   `git push origin spring-p3:spring` (শুধু fast-forward; আটকালে আবার rebase)। শেষে `spring-ci-log` সবুজ কি না দেখুন।
6. অস্থায়ী class বদলানোর সময় একই commit-এ সেটা মুছে নিজেরটা বসান (একই interface-এর দুটো class থাকলে অ্যাপ চালু হয় না),
   আর `ContractsIT`-এর টেস্টগুলো সবুজ রাখুন। interface-এ শুধু নতুন কিছু যোগ করা চলে; বদলাতে হলে যারা ব্যবহার করে তাদের সাথে
   ঠিক করে PHASE2-PLAN.md-এ লিখুন।

