# Lifolo — Feature switches (ধাপে ধাপে চালু) — shared contract

The owner decided on 2026-10-23 to release in as many small steps as possible. All new features ship in the code but stay
**OFF** until the owner switches them on from the admin page. Turning a feature on needs no app update.
The flags agent owns `backend/docs/FEATURES.md`, and that file is final once it is on main.

## Keys (exact strings)
| key | what it unlocks |
|---|---|
| `calls_audio` | in-app audio calls (all contexts except appointments) |
| `calls_video` | video calls + "ভিডিওতে ধরুন" + upgrade (needs calls_audio) |
| `appt_video` | doctor video appointments: appt_join, the join screen (needs online_chamber) |
| `chat` | in-app text messages |
| `chat_media` | photos + location in messages (needs chat) |
| `online_chamber` | online chamber setup/booking + bKash fee flow |
| `inv_pharmacy` | pharmacy receipts (books sale invoice, online order delivered lines) |
| `inv_lab` | lab bills/receipts |
| `inv_chamber` | doctor/assistant fee receipts + chamber desk |
| `inv_ambulance` | ambulance trip finish + fare receipt |
| `my_expenses` | patient "আমার রসিদ ও খরচ" (shows receipts of enabled kinds only) |
| `desktop` | desktop_register/offline_* + desktop download link |
| `pay_manual` | Lifolo Pay master switch: manual MFS payments (payments CONTRACT) |
| `pay_chamber_advance` | in-person chamber advance fee (needs pay_manual) |
| `pay_delivery` | pharmacy home-delivery prepay (needs pay_manual, inv_pharmacy) |
| `pay_lab` | lab home-collection / bill prepay (needs pay_manual, inv_lab) |
| `pay_ambulance` | ambulance fare after the trip (needs pay_manual, inv_ambulance) |
| `pay_due` | patient pays a receipt's due amount (needs pay_manual) |
| `pay_bkash_pgw` | bKash Tokenized Checkout, Lifolo's own income only |
| `practice_systems` | medical systems (চিকিৎসা পদ্ধতি) — off: everyone allopathy, no system choice/labels/filter (backend/docs/PRACTICE.md) |
| `pro_physio` | physiotherapists (ফিজিওথেরাপিস্ট) |
| `pro_psychologist` | psychologists / counsellors (সাইকোলজিস্ট / কাউন্সেলর) |
| `health_consent` | স্বাস্থ্য তথ্যের সম্মতি — explicit, recorded patient consent before health data entry (also for storage abroad); emergency features never gated (backend/docs/CONSENT.md). Outside `all-on`: follows its own setting in tests/e2e |
| `title_guard` | "‘ডা.’ পদবির নিয়ম" — "ডা."/"Dr." only for MBBS/BDS: refused on save, stripped where shown, practitioner labels (backend/docs/PRACTICE.md §6). Inside `all-on` (on in tests/e2e) |

## Modes per key
`{mode: "off"|"pilot"|"on", pilotUids: [uid…], demo: boolean}`
- `off`: nobody gets the feature. This is the default for every key in production.
- `pilot`: only the uids in `pilotUids`, plus every demo account when `demo:true`.
- `on`: everyone.
- Dependencies: a key counts as on for a user only if every key it needs is also on for that user.
- **Two-party features** (calls, chat, appt_video): the feature must be on for BOTH users. Otherwise `contact_can` returns false for that flag.
- Test/CI profile: every key is on.

## Backend
- `Features.isOn(uid: String?, key: String): Boolean`. The result is cached in Redis for 30 s, and the cache is cleared on admin change.
- `Features.require(ctx, key)`: throws 404 code `feature_off` with the Bangla message "এই সুবিধা এখনো চালু হয়নি".
- EVERY action of a gated feature calls `require` first. Pushes and inbox entries for an off feature are not sent.
- `features` action (guest allowed) → `{keys: {calls_audio: true, …}, v: <version int>}` for the caller.
- `admin_features_get` → all keys with mode/pilotUids/demo and the last change.
- `admin_feature_set {key, mode, pilotUids?, demo?}`: admin only, audited.
  - After a change, send the silent FCM data push `features_changed {v}` to affected users. Sending to the topic `all` is fine.
  - Also emit the SSE event `features_changed`.

## Clients
- **Android:** `object Features { val keys: StateFlow<Map<String,Boolean>>; fun on(key): Boolean; suspend fun refresh() }`
  - Refresh on app start, login, resume (at most once a minute), and on the `features_changed` push.
  - Persist the last keys so the app works offline.
  - Every entry point of a gated feature is hidden while it's off: buttons, tabs, menu rows, settings switches.
  - A deep link to an off feature shows "এই সুবিধা শিগগির আসছে".
- **Web/desktop:** `web-vue/src/lib/features.ts`, which exports `featureOn(key)` (reactive) and `refreshFeatures()`.
  - Same refresh rules, plus the SSE `features_changed` event.
  - Router guard on gated routes.
- Server `feature_off` errors show the same Bangla text and never crash.

## Admin page
`web-vue/admin`, page "ফিচার সুইচ":
- A card per key: Bangla name + one-line description.
- Mode buttons বন্ধ / পাইলট / সবার জন্য.
- Pilot user list: add users by email or phone using the existing admin user search, remove them.
- A "ডেমো অ্যাকাউন্টে চালু" toggle.
- A confirm step before "সবার জন্য".
- Change history.

---

## How to use it (helpers)

### Backend — `com.lifolo.features.Features` (Spring bean)
```kotlin
class ChatActions(private val features: Features, …) : ActionModule {
    fun send(ctx: ActionContext): Any {
        features.require(ctx, Features.CHAT)              // first line of EVERY action of the feature
        if (!features.bothOn(ctx.user.id, peerUid, Features.CHAT)) throw …   // two-party features
        …
        if (features.isOn(peerUid, Features.CHAT)) push.pushUsers(…)          // no push/inbox for an off feature
    }
}
```
- `isOn(uid: String?, key)`; `uid = null` is a guest (only `on` counts). Unknown keys are off.
- `require(ctx, key)` checks the **caller** (`ctx.caller`, the person holding the phone — also when a caregiver acts
  for a patient). `require(uid, key, lang)` is the same check outside an action (HTTP endpoints, jobs).
- Error: HTTP 404 `{error: "এই সুবিধা এখনো চালু হয়নি", code: "feature_off"}` (English "This feature is not available yet"
  for `lang: "en"`).
- Key constants: `Features.CALLS_AUDIO … Features.DESKTOP`, `Features.PRACTICE_SYSTEMS`, `PRO_PHYSIO`, `PRO_PSYCHOLOGIST`, `HEALTH_CONSENT`, `TITLE_GUARD`; `Features.NEEDS` (dependencies), `Features.TWO_PARTY`.
- Cache: the settings (all keys + version) live in Redis `lifolo:features` for 30 s; `clearCache()` after a change.
  When Redis fails, the database answers.
- `lifolo.features.all-on` (env `FEATURES_ALL_ON`, default false): every key on for everyone — except the keys in
  `Features.OUTSIDE_ALL_ON` (`health_consent`), which add a precondition instead of unlocking something and so always follow
  their own setting (tests switch them with `setFeature`). On in the test profile
  (`application-test.yml`) and the e2e server (`e2e/spring.sh`); never in production. A test that checks the
  off/pilot behaviour sets `features.allOn = false` and restores it afterwards (see `FeaturesIT`).

### Tables (V670)
- `feature_flags (key PK, mode off|pilot|on, pilot_uids text[], demo, updated_at, updated_by)` — no row = off.
- `feature_changes` — one row per admin change (history; `max(id)` is the version `v`).
- `feature_clients (uid)` — accounts whose app has called `features`; only they get the silent `features_changed`
  push (an older app would show an empty notification).

### `features` action
Guest allowed (`ActionPolicy.PUBLIC_ACTIONS`) and **never** served from the 1-minute guest cache
(`ActionPolicy.GUEST_UNCACHED`). Answer: `{keys: {calls_audio: false, chat: true, …all 24…}, v: 12}`.

### Android — `app/src/main/java/com/bspc/blood/Features.kt`
```kotlin
if (Features.on(Features.CHAT)) { … }                     // plain code
val chatOn = featureOn(Features.CHAT)                      // in a @Composable (recomposes on change)
Features.keys                                              // StateFlow<Map<String, Boolean>>
suspend Features.refresh()                                 // force; refreshSoon() = at most once a minute
Features.soonText()                                        // "এই সুবিধা শিগগির আসছে" for a deep link to an off feature
Features.isFeatureOff(e)                                   // ServerError code feature_off
```
Wired already: `Features.init` in `MainActivity.onCreate` (last keys from SharedPreferences), refresh on start and every
login/logout (`LoginGate.uid`), `refreshSoon()` on every resume, the `features_changed` data push
(`BloodMessagingService`, no notification shown), and any `feature_off` server error (ServerApi).

### Web — `web-vue/src/lib/features.ts`
```ts
import { featureOn, featureGuard, gateRoutes, isFeatureOff, featureSoonText } from '@/lib/features';
v-if="featureOn('chat')"                                   // reactive
{ path: '/messages', component: Chat, beforeEnter: featureGuard('chat') }   // off → toast "শিগগির আসছে" + '/'
gateRoutes(router, { '/messages': 'chat', '/work/desk': 'inv_chamber' })   // by path prefix (section routes)
```
Wired already: `initFeatures()` in `main.ts` — refresh on start, on login change, when the tab becomes visible (at most
once a minute), on any `feature_off` error, and on the SSE event `features_changed` from `/api/events` (read with
`fetch` because `EventSource` cannot send the token). Last keys kept in `localStorage` (`lf.features`).
Tests: the mocked API (`test/fixtures/features.mjs`) answers every key on; a Playwright test can set
`window.__lfTest.features = { chat: false }` to see the off state.

### Admin (step 2)
- `admin_features_get` → `{items: [{key, mode, pilotUids, pilots: [{uid, email, name, phone, missing?}], demo, needs, twoParty,
  updatedAt, updatedBy, last}], history: [last 100 changes, newest first], v, allOn}`.
- `admin_feature_set {key, mode, pilotUids?, demo?}` — a field left out keeps its value; the same setting again answers
  `{unchanged: true}` (no history, no log, no push). Pilot uids must be existing accounts (≤ 500). Audited twice: a
  `feature_changes` row (history, version) and the admin log (`features/<key>`). Then: Redis cache cleared, silent push
  `{type: "features_changed", v}` (no title, no bell entry, no category) to the affected phones — everyone when the old or
  new mode is `on`, else the old + new pilot lists and, with `demo`, every demo account — and the SSE event
  `features_changed {v}` to the same people's open `/api/events` streams. Sent in the background.
- Admin page: `web-vue/admin` → "ফিচার সুইচ" (`#/features`, `admin/src/pages/Features.ts`).
- Several server instances: each keeps the Redis read 2 s in memory, so another instance sees a change ≤ 2 s later.

### online_chamber (the first gated feature)
See `docs/CHAMBERS-ONLINE.md` → "Feature switch". The `OnlineSwitch` bean (`allowed(uid)`, `require(ctx)`,
`demoEnabled`) wraps the key; `DEMO_ONLINE_CHAMBERS` is gone — demo accounts follow the switch like everyone else.

### Tests
- Backend: the test profile has every key on. Off/pilot behaviour: `features.allOn = false` (restore in `@AfterEach`) and
  `setFeature(jdbc, features, Features.CHAT, Features.PILOT, listOf("u1"), demo = false)` from `support/FeatureSwitches.kt`.
  Examples: `FeaturesIT`, `FeatureAdminIT`, `appointments/OnlineChamberSwitchIT`.
- Android: `Features.set(mapOf(Features.CHAT to true), uid = "u1")` in Robolectric tests; `Features.clear()` after.
- Web: `window.__lfTest.features = { chat: false }` in the test build; the mocked API answers all on.

### Ops: all on / all off / restore (production, without the admin login)
The owner decided on 2026-10-23 to work and test on lifolo.com (testers only) with **every switch on**, and also with
them off. GitHub → Actions → **"Ops: feature switches"** (`.github/workflows/ops-features.yml`) → Run workflow → `action`:

| action | what it does |
|---|---|
| `show` | prints every key's mode (off / pilot / on), pilot count, demo flag, and unmet dependencies. Changes nothing. |
| `all_on` | saves a snapshot, then sets every key to `on` (needed keys first, so no dependency is left unmet). |
| `all_off` | saves a snapshot, then sets every key to `off`. |
| `restore` | puts back the snapshot taken before the **latest** `all_on`/`all_off`, exactly: mode, pilot list, demo flag. Before restoring it saves another snapshot (`…-before-restore.json`). To go further back, give the `snapshot` input a file name from an earlier run's summary. A key added after the snapshot was taken is set to `off`. |

`all_on`/`all_off` change only the **mode**. The pilot lists and "ডেমো অ্যাকাউন্টে চালু" flags stay as they were, so
`restore` (or the admin page) can go back to pilot later.

How it works:
- The workflow connects with the usual SSH (`deploy/ci/vps-connect.sh`) and runs `deploy/ops/features.sh` on the VPS.
- The script calls `POST http://127.0.0.1:8088/api/cron/features?do=show|set` with `Authorization: Bearer $CRON_SECRET`.
  The secret is read from `/etc/lifolo/app.env` into a temporary header file, never onto the command line or into the log.
  nginx answers 404 for `/api/cron/` from outside.
- The endpoint is `features/FeatureOps.kt`. It writes with the same code as `admin_feature_set` (`FeatureActions.store`).
  Each change adds a `feature_changes` row (history, version `v`) and an `admin_log` row (`admin_feature_set`,
  `features/<key>`), and sets `feature_flags.updated_by`. The actor is **`ops-features`**, so the admin page's
  history shows who changed it.
- Afterwards the Redis cache is cleared once. ONE silent `features_changed` push and SSE event goes to everyone the
  changes affect, so open apps refresh at once.
- A list with one wrong item writes nothing (400). No database writes happen outside the app.
- Snapshots are kept on the VPS in `/var/lib/lifolo/feature-snapshots/<UTC time>-before-<action>.json` (root only, 0600).
- The step summary is in Bangla and also goes to the log branch `ci-log-ops-drills` (`features/`). It shows only modes
  and pilot counts, never uids or emails.
- The workflow has its own concurrency group (`ops-features`). It never queues in `vps-admin`, where it could cancel a
  pending "Deploy backend".

With everything on:
- **`pay_bkash_pgw`** stays on even without the `BKASH_*` settings on the server. Only Lifolo's own subscription checkout
  then answers 503 `pgw_unavailable` ("অনলাইন পেমেন্ট এখন চালু নেই"). The summary says so. Nothing else depends on it.
- **`health_consent`** is on too. Patients are asked for consent before a health-data entry (CONSENT.md). Apps older than
  RC4 (e.g. RC3) get the 428 text "…অ্যাপ হালনাগাদ করুন". This is expected.
- **Nightly DemoRefresh** (`demo/DemoRefresh.kt`, every 10 min, a new build each Dhaka day) keeps working:
  - the seed writes straight to the database, so no switch (`health_consent`, `title_guard`) blocks it;
  - with `online_chamber` on for demo accounts, the demo doctor's online chamber is built. The first run after `all_on`
    (or `all_off`) rebuilds the demo once (about 10 min) because the online chamber comes or goes;
  - demo login and today's serials stay.

  Covered by `DemoRefreshIT` "every switch on in production". The endpoint itself is covered by `FeatureOpsIT`.
