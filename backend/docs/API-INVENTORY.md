# API inventory — Node server → Spring Boot

Generated for phase 1 from `server/` on branch `main` (commit 6e16e79 (server/ unchanged at 530ca2c)) by static analysis of every
`/api/community` action (AST walk of the handler and every helper it calls, across files) plus manual review.
This is the checklist for phase 2: **tick an action when its Kotlin port passes the ported Node tests.**
"port: P1…P6" = the work package in [PHASE2-PLAN.md](PHASE2-PLAN.md).

* **225 `/api/community` actions** + **11 other HTTP endpoints** (+ 4 share rewrites) + **15 daily cron steps** + lazy/async behaviours.
* Firestore collection names are used below (the table names are in [SCHEMA.md](SCHEMA.md#firestore--postgresql-mapping)).
* "tests" = Node test files under `server/test/` that call the action (by name).
* Phase 1 already implements: `/api/health`, `/api/auth/send-code`, `/api/auth/verify-code` (incl. demo + web session),
  `/api/cron/daily` (runner only), `profile_get`, `profile_update`, `web_logout`, and the dispatcher rules below.
* **Coverage after phase 2 (P1–P6 merged into `spring`): 225 / 225 `/api/community` actions, 11 / 11 other HTTP endpoints
  (+ the 4 share rewrites), 12 / 15 daily cron steps (steps 7 and 15 and the backfill half of 14 are dropped on purpose —
  see the table below), and every lazy/async behaviour listed at the end.** Every action is registered in the
  `ActionRegistry` (checked against this list) and covered by the ported Node tests or new ITs; the intentional
  differences from Node are listed in [PHASE2-PLAN.md → Phase 2 complete](PHASE2-PLAN.md#phase-2-complete).

## How /api/community dispatches (implemented in phase 1 — `common/action/CommunityController.kt`)

1. `Authorization: Bearer <Firebase ID token>` → verify with revocation check. Failures: disabled → 403 `account_disabled`
   (with `accounts.disabled_reason`), revoked + pending deletion → 403 `account_disabled` (deletion date), else 401
   "Session expired, please log in again". Empty token → 401 "Please log in".
2. No bearer but the `__Host-lf_admin` cookie → web panel session: needs header `x-lifolo-admin: 1` (else 401
   `session_expired`); only `admin_*`, `catalog_get`, `labtests_get`, `web_logout`; caller must still be admin and not
   disabled (cached 1 min) else cookie cleared + 403 `session_expired`.
3. Neither → guest: only the **15 public actions** (`guest_requests, emergencies_active, questions, question, tips,
   providers, home_providers, hospitals, ambulances, pharmacies, bloodbanks, labs, lab_prices_public, drug_search, catalog_get`),
   240 req/min per IP (429), answers cached 60 s per identical body. Anything else → 401 "Please log in".
4. Unknown action → 400 "Unknown request. Please update the app." (after the login check).
5. `body.asUid` → caregiver acts for the patient: only the 27 CARE actions (Oct 2026: + `appt_pay_claim`, `appt_report`) (marked **caregiver** below) and an active
   `careLinks/{patient}_{caretaker}` (else 403, or 403 `care_revoked`; Oct 2026: 403 `care_paused` while the patient's
   account is deactivated).
   *Spring, Oct 2026:* before this step a deactivated caller gets 423 `account_deactivated` for everything except
   `profile_get`, `account_reactivate`, `delete_account`, `notify_prefs_get`, `web_logout` (see "Users & account").
6. `admin_*` results are logged to `adminLog` unless the result has `unchanged: true` (only actions in adminweb.js LOGGED).
7. Errors: `{error, code?}`; `body.lang`: `tr(en,bn)` gives Bangla only for `"bn"`; legacy Bangla messages are translated
   to English only for `"en"` (i18n.js). 500 → generic message.

Auth legend: **guest** = works without login · **user** = Firebase login · **admin** = owner or extra admin (verified email) ·
**owner** = ADMIN_EMAILS only · **role:…** = extra check in the handler (badge = approved professional badge;
approved-pharmacy/approved-lab = own listing approved; doctor|assistant = doctor or an assistant email of that doctor;
ambreq-party = patient or driver of that request) · **caregiver** = allowed with `asUid` · **web-panel** = callable with the admin cookie.

## Users & account (package `users`) — 7 actions

### Account state, deactivation and account emails (Spring only, Oct 2026 — `users/AccountDeactivation.kt`, `notify/AccountMail.kt`)

**`account` object** — in `profile_get` and in the `/api/auth/verify-code` answer (also the demo login), so the apps can show
the right screen:

```
account: { state: "active" | "deactivated" | "deleting",
           deactivatedAt?: ms,                       // state = deactivated
           deletionRequestedAt?: ms, deletesAt?: ms } // state = deleting (deletesAt = the date the daily job deletes it)
```

A login cancels a pending deletion before the answer is built, so verify-code answers `active` (or `deactivated`) — never
`deleting`. Deletion and deactivation never coexist (see below).

**While deactivated** (`accounts.deactivated_at` set — V608):

| | |
|---|---|
| allowed actions | **only** `profile_get`, `account_reactivate`, `delete_account`, `notify_prefs_get`, `web_logout` (`ActionPolicy.WHILE_DEACTIVATED`) |
| every other action (also public ones called with a token, and the legacy `/api/respond`, `/api/notify-request`, `/api/close-request`, `/api/edit-request`) | **423** `{error: "আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে। চালু করতে \"আবার চালু করুন\" চাপুন।", code: "account_deactivated"}` (English when `lang: "en"`). Checked on the authenticated caller, after the login check and the unknown-action 400 |
| profile | hidden from everyone else: not a donor for nearby requests or emergency waves (`GeoQuery.nearDonors`); questions/answers/tips stay but the author is shown as **"নিষ্ক্রিয় ব্যবহারকারী"** ("Deactivated user" for `lang: "en"`) with no badge. Blood responses already given keep the name/phone that donor shared with that requester (not re-hidden) |
| listings | pharmacy, lab, ambulances, blood banks: `status = 'suspended'`, previous status in `hidden_for_deactivation`; doctor/nurse profile + every chamber (no new bookings) and home care: `listed/active/home_visit_enabled = false`, previous values in `providers.deact_listed/deact_active/deact_home`. Provider + guest caches cleared |
| appointments | already booked ones stay — as patient and as the doctor (they can be cancelled by the other side as usual) |
| notifications | phone pushes only for category `account` (`PushService.send`); the bell list still records the rest. All device tokens are dropped at deactivation |
| caregivers | links paused both ways: a caregiver acting for a deactivated patient → 403 `care_paused`; a deactivated caregiver → 423; no caregiver copies of the patient's news (`pushPatient`) |
| own blood requests / ambulance calls | unchanged (not closed) |
| expiry | none — the daily job never deletes a deactivated account (only `delete_at` does) |

- [x] **`account_deactivate`** — *Spring only:* switch the own account off temporarily
  - src: `users/AccountDeactivation.kt` · auth: user, own account only (not a caregiver `asUid` → 403, not the web panel)
  - request: — · response: `{ok, account, token}` (`unchanged: true` when already off — but see the 423 rule: a deactivated caller gets 423)
  - effect: sets `deactivated_at`, hides as in the table above, drops every device token and revokes every login
    (`revokeRefreshTokens` — Firebase revokes per account, so **this device's ID token is revoked too**: `token` is a fresh
    custom token, as in verify-code, to sign this device in again without a code; a client that ignores it just logs in
    again and lands on the "turn on again" screen). Email (`account`): "অ্যাকাউন্ট সাময়িক বন্ধ করা হয়েছে — আবার লগইন করে চালু করতে পারবেন"
  - errors: 409 `deletion_pending` "আপনার অ্যাকাউন্ট মোছার অপেক্ষায় আছে, তাই সাময়িক বন্ধ করা যাবে না। আগে আবার লগইন করে মোছা বাতিল করুন।" (deletion supersedes) · tests: DeactivationIT
- [x] **`account_reactivate`** — *Spring only:* switch it back on: everything restored (an admin change made meanwhile wins: a listing
  no longer `suspended` keeps the admin's status, a provider suspended meanwhile stays suspended — lifting it lists it again —,
  a home-care block keeps home care off)
  - src: `users/AccountDeactivation.kt` · auth: user, own account · request: — · response: `{ok, account}` (`unchanged: true` when it was not off)
  - email (`account`): "অ্যাকাউন্ট আবার চালু হয়েছে". The app should call `device_register` again afterwards · tests: DeactivationIT

**Account emails** (SES, `account` category, Bangla + one English line + https://lifolo.com; sent in the background after the
change is saved — a failure is only logged and never fails the action; none for demo accounts): deletion requested (date =
`deleteAt` as an Asia/Dhaka date, what will be deleted, "৩০ দিনের মধ্যে লগইন করলে মোছা বাতিল হবে"; only for the first request —
a repeated request keeps the first date and sends nothing), deletion cancelled by a login (app or web panel) ("মোছা বাতিল হয়েছে"),
deactivated, reactivated.

- [x] **`delete_account`** — Schedule deletion in 30 days: hide listings (status suspended + hiddenForDeletion), close ambulance requests, suspend provider + cancel appointments, drop FCM tokens, revoke refresh tokens → {ok, deleteAt}
  - src: `server/lib/account.js` → `delete_account` · auth: user · port: **P6**
  - Oct 2026: optional `{ack: true}` (the app's "I understand" tick) — accepted and not required, so older apps without it
    still work. Allowed while deactivated: the deactivation is lifted first (listings restored, no reactivation email), then
    the deletion hides them as usual. The first request emails the deletion date (see above).
  - request: `ack?` · response: `{ok, deleteAt}`
  - collections: ambReqLimits, ambulanceCalls, ambulanceRequests, ambulances, appointments, bloodBanks, inbox, labs, pharmacies, providers, serverState, users · side effects: revoke refresh tokens, FCM push + inbox, push to patient + caregivers · errors: — · tests: deletion, demo-health, labtests, rxflow
- [x] **`profile_get`** — Own profile subset (profileOut) or null, plus the account state (Oct 2026)
  - src: `server/lib/clientdata.js` → `actions.profile_get` · auth: user (not guest, not caregiver) · port: **phase 1**
  - request: — · response: `{profile, account}` (`account` — see above; also while deactivated)
  - collections: users · side effects: — · errors: — · tests: clientdata
- [x] **`profile_update`** — Merge-update whitelisted fields (SELF_FIELDS), lat+lng together (→ geohash, donorGeo), lastDonation only forward, commitAdd/commitRemove
  - src: `server/lib/clientdata.js` → `actions.profile_update` · auth: user (not guest, not caregiver) · port: **phase 1**
  - request: `set`, `commitAdd`, `commitRemove` · response: `{ok,unchanged}` or `{ok}`
  - collections: users · side effects: — · errors: 400, 403 · tests: clientdata
- [x] **`phone_send_code`** — SMS code to profile phone (SMS_URL_TEMPLATE; disabled when unset → 400); per-number 3/day + 1/min (phoneLimits); quota phoneCode=5/day
  - src: `server/lib/phone.js` → `actions.phone_send_code` · auth: user · quota: phoneCode (5/day) · port: **P6**
  - request: — · response: `{ok,to}`
  - collections: phoneLimits, serverState, users · side effects: outbound HTTP · errors: 400, 429, 502 · tests: — (none)
- [x] **`phone_verify_code`** — Verify 6-digit SMS code (5 tries, 10 min) → verifiedPhone
  - src: `server/lib/phone.js` → `actions.phone_verify_code` · auth: user · port: **P6**
  - request: `code` · response: `{ok}`
  - collections: serverState · side effects: — · errors: 400 · tests: — (none)

## Auth / web session (package `auth`) — 1 actions

- [x] **`web_logout`** — Clears the admin web-session cookie
  - src: `server/api/community.js` → `actions.web_logout` · auth: web session cookie (any) web-panel · port: **phase 1**
  - request: — · response: `{ok}`
  - collections: — · side effects: — · errors: — · tests: — (none)

## Content: Q&A, tips, catalog, hospitals (package `content`) — 16 actions

- [x] **`me`** — Own badge (+ docs status), isAdmin, isOwner, phoneCheck enabled, phoneVerified, unread inbox count
  - src: `server/api/community.js` → `actions.me` · auth: user · port: **P2**
  - request: — · response: `{badge,isAdmin,isOwner,phoneCheck,phoneVerified,unread}`
  - collections: badges, config, inbox, serverState, users · side effects: — · errors: — · tests: demo-health, demo-rx, guest, rules
- [x] **`questions`** — Health Q&A list, 30/page, cursor `before` (createdAt ms); authorName hidden for anonymous or guests
  - src: `server/api/community.js` → `actions.questions` · auth: guest · port: **P2**
  - request: `before` · response: `{items:[...]}`
  - collections: questions · side effects: — · errors: — · tests: demo-health, guest
- [x] **`question`** — One question + up to 200 answers; answer author name/badge from live approved badge
  - src: `server/api/community.js` → `actions.question` · auth: guest · port: **P2**
  - request: `id` · response: `{question:{id,text,authorName,mine,...,createdAt,editedAt,answerCount},answers:[{id,text,authorName,badge,mine,createdAt,editedAt}]}`
  - collections: badges, questions, …/answers · side effects: — · errors: 400, 404 · tests: demo-health
- [x] **`ask`** — Post question (≤1000 chars, optional anonymous); quota ask=5/day
  - src: `server/api/community.js` → `actions.ask` · auth: user · quota: ask (5/day) · port: **P2**
  - request: `text`, `anonymous` · response: `{id}`
  - collections: questions, serverState, users · side effects: — · errors: 400 · tests: demo-health, guest
- [x] **`answer`** — Verified professional answers; increments answerCount, records answer path for deletion, pushes asker
  - src: `server/api/community.js` → `actions.answer` · auth: user role:badge · quota: answer (40/day) · port: **P2**
  - request: `id`, `text` · response: `{ok}`
  - collections: badges, inbox, questions, serverState, users, …/answers · side effects: FCM push + inbox · errors: 400, 403, 404 · tests: demo-health
- [x] **`edit_question`** — Author edits text/anonymous flag; reopens moderation reports on text change
  - src: `server/api/community.js` → `actions.edit_question` · auth: user (author) · port: **P2**
  - request: `id`, `text`, `anonymous` · response: `{ok}`
  - collections: modReports, questions · side effects: — · errors: 400, 404, 403 · tests: — (none)
- [x] **`edit_answer`** — Author edits answer; reopens moderation reports
  - src: `server/api/community.js` → `actions.edit_answer` · auth: user (author, badge still approved) · port: **P2**
  - request: `id`, `answerId`, `text` · response: `{ok}`
  - collections: badges, modReports, questions/answers · side effects: — · errors: 400, 404, 403 · tests: — (none)
- [x] **`delete_question`** — Recursive delete; admin deletion is logged (admin_delete_post)
  - src: `server/api/community.js` → `actions.delete_question` · auth: user (author) or admin · port: **P2**
  - request: `id` · response: `{ok}`
  - collections: adminLog, config, questions · side effects: recursive delete, admin_log row · errors: 400, 403 · tests: — (none)
- [x] **`delete_answer`** — Delete answer, decrement answerCount; admin deletion logged
  - src: `server/api/community.js` → `actions.delete_answer` · auth: user (author) or admin · port: **P2**
  - request: `id`, `answerId` · response: `{ok}`
  - collections: adminLog, config, questions, questions/answers · side effects: admin_log row · errors: 400, 403 · tests: — (none)
- [x] **`tips`** — Health tips list, 30/page, cursor `before`; live badge name/type
  - src: `server/api/community.js` → `actions.tips` · auth: guest · port: **P2**
  - request: `before` · response: `{items:[{id,title,body,authorName,badge,mine,createdAt,editedAt}]}`
  - collections: badges, tips · side effects: — · errors: — · tests: demo-health
- [x] **`post_tip`** — Verified professional posts tip (title ≤120, body ≤3000); quota tip=5/day
  - src: `server/api/community.js` → `actions.post_tip` · auth: user role:badge · quota: tip (5/day) · port: **P2**
  - request: `title`, `body` · response: `{id}`
  - collections: badges, serverState, tips, users · side effects: — · errors: 403, 400 · tests: demo-health
- [x] **`edit_tip`** — Edit tip; reopens moderation reports
  - src: `server/api/community.js` → `actions.edit_tip` · auth: user (author, badge approved) · port: **P2**
  - request: `id`, `title`, `body` · response: `{ok}`
  - collections: badges, modReports, tips · side effects: — · errors: 400, 404, 403 · tests: — (none)
- [x] **`delete_tip`** — Delete tip; admin deletion logged
  - src: `server/api/community.js` → `actions.delete_tip` · auth: user (author) or admin · port: **P2**
  - request: `id` · response: `{ok}`
  - collections: adminLog, config, tips · side effects: admin_log row · errors: 400, 403 · tests: — (none)
- [x] **`catalog_get`** — All pick-lists (specialties, home services, ambulance types, blood bank services) merged with defaults; config/catalog cached 5 min (also loaded on every request)
  - src: `server/lib/catalog.js` → `actions.catalog_get` · auth: guest web-panel · port: **P6**
  - request: — · response: `{lists,updatedAt}`
  - collections: config · side effects: — · errors: — · tests: guest
- [x] **`hospitals`** — Approved hospitals of district (+upazila) — DGHS directory + approved user additions
  - src: `server/lib/hospitals.js` → `actions.hospitals` · auth: guest · port: **P2**
  - request: `district`, `upazilaId` · response: `{items}`
  - collections: hospitals · side effects: — · errors: 400 · tests: demo-blood
- [x] **`hospital_request`** — Request a new hospital (dedupe/merge with similar pending, duplicateOf existing); quota hospital=10/day
  - src: `server/lib/hospitals.js` → `actions.hospital_request` · auth: user · quota: hospital (10/day) · port: **P2**
  - request: `nameEn`, `nameBn`, `type`, `upazilaId`, `unionId`, `district`, `address`, `phone` · response: `{ok,duplicateOf}` or `{ok,merged}` or `{ok}`
  - collections: hospitalRequests, hospitals · side effects: — · errors: 400 · tests: — (none)

## Blood donation (package `blood`) — 25 actions

- [x] **`bloodbanks`** — Approved blood banks in district (open first, own upazila, govt first; open24 filter)
  - src: `server/lib/bloodbank.js` → `actions.bloodbanks` · auth: guest · port: **P1**
  - request: `district`, `upazilaId`, `open24` · response: `{items}`
  - collections: bloodBanks · side effects: — · errors: 400 · tests: demo-blood
- [x] **`bloodbank_mine`** — Own blood banks with docs status
  - src: `server/lib/bloodbank.js` → `actions.bloodbank_mine` · auth: user · port: **P1**
  - request: — · response: `{items}`
  - collections: bloodBanks · side effects: — · errors: — · tests: demo-blood
- [x] **`bloodbank_save`** — Add/edit blood bank (terms, docs); re-verify on key changes; sanctions; quota bloodbank=5/day
  - src: `server/lib/bloodbank.js` → `actions.bloodbank_save` · auth: user · quota: bloodbank (5/day) · port: **P1**
  - request: `termsAccepted`, `id`, `docs`, `name`, `kind`, `phone`, `district`, `upazilaId`, `address`, `licenseNo`, `open24`, `openAt`, `closeAt`, `services`, `closedDays` · response: `{ok,pending}` or `{ok,pending,id}`
  - collections: bloodBanks, sanctions, serverState · side effects: — · errors: 400, 404, 403 · tests: demo-blood
- [x] **`bloodbank_delete`** — Delete own blood bank (not suspended / open reports) + stats + verify docs
  - src: `server/lib/bloodbank.js` → `actions.bloodbank_delete` · auth: user (owner) · port: **P1**
  - request: `id` · response: `{ok}`
  - collections: bloodBankStats, bloodBanks, modReports, verifyDocs · side effects: — · errors: 403 · tests: — (none)
- [x] **`bloodbank_called`** — Record call (10-min dedupe) + bloodBankStats
  - src: `server/lib/bloodbank.js` → `actions.bloodbank_called` · auth: user · port: **P1**
  - request: `id` · response: `{ok}`
  - collections: bloodBankCalls, bloodBanks · side effects: — · errors: — · tests: demo-blood
- [x] **`bloodbank_stats`** — Owner stats per bank: calls/day, reports
  - src: `server/lib/bloodbank.js` → `actions.bloodbank_stats` · auth: user · port: **P1**
  - request: — · response: `{items}`
  - collections: bloodBankStats, bloodBanks, modReports · side effects: — · errors: — · tests: — (none)
- [x] **`blood_request_get`** — One blood request (requestOut) or null
  - src: `server/lib/clientdata.js` → `actions.blood_request_get` · auth: user (same world) · port: **P1**
  - request: `requestId` · response: `{request}`
  - collections: requests · side effects: — · errors: — · tests: clientdata
- [x] **`blood_requests_mine`** — Own requests, newest first
  - src: `server/lib/clientdata.js` → `actions.blood_requests_mine` · auth: user · port: **P1**
  - request: — · response: `{items}`
  - collections: requests · side effects: — · errors: — · tests: clientdata
- [x] **`blood_requests_near`** — Open active requests within maxKm (geoOpen geohash bounds, fallback district list), group filter, same world, urgent first
  - src: `server/lib/clientdata.js` → `actions.blood_requests_near` · auth: user · port: **P1**
  - request: `lat`, `lng`, `maxKm`, `groups` · response: `{items}`
  - collections: requests · side effects: — · errors: 400 · tests: clientdata
- [x] **`blood_response_get`** — Own response to a request
  - src: `server/lib/clientdata.js` → `actions.blood_response_get` · auth: user · port: **P1**
  - request: `requestId` · response: `{response}`
  - collections: requests/responses · side effects: — · errors: — · tests: clientdata
- [x] **`blood_responses_list`** — Accepted/donated responses with donor name/phone
  - src: `server/lib/clientdata.js` → `actions.blood_responses_list` · auth: user (requester) · port: **P1**
  - request: `requestId` · response: `{items}`
  - collections: requests, …/responses · side effects: — · errors: 403 · tests: clientdata
- [x] **`blood_contact_get`** — Requester contact (requests/{id}/private/contact)
  - src: `server/lib/clientdata.js` → `actions.blood_contact_get` · auth: user (requester, or accepted/donated donor of open request) · port: **P1**
  - request: `requestId` · response: `{contact}`
  - collections: requests, …/private, …/responses · side effects: — · errors: 403 · tests: clientdata
- [x] **`report_request`** — Report a blood request once (reports/{requestId}_{uid}); 409 if repeated
  - src: `server/lib/clientdata.js` → `actions.report_request` · auth: user · port: **P1**
  - request: `requestId` · response: `{ok}`
  - collections: reports · side effects: — · errors: 409 · tests: clientdata
- [x] **`donation_date_set`** — Change last donation date (backward needs confirmed:true → 409 confirm_needed); risk log; quota dateChange=3/day
  - src: `server/lib/donationdate.js` → `actions.donation_date_set` · auth: user · quota: dateChange (3/day) · port: **P1**
  - request: `millis`, `confirmed` · response: `{ok,unchanged}` or `{ok,risk}`
  - collections: serverState, users · side effects: — · errors: 400, 404, 409:confirm_needed · tests: demo-blood
- [x] **`correction_create`** — *Spring only (Oct 2026), no Node action:* the person asks an admin to correct their last donation date ("তথ্য সংশোধনের অনুরোধ"). Node had only the Firestore client write (`correctionRequests`, now closed); the admin side is unchanged (`/api/admin/corrections`, `/api/admin/resolve` → approve sets the date, push type `correction`)
  - src: `blood/CorrectionActions.kt` · auth: user (not a caregiver, not the web panel) · quota: correction (3/day) · port: **P10**
  - request: `requestedMillis` (required key: a Dhaka-day UTC-midnight ms from 1990-01-01 up to today, or `null` = "never donated"), `reason` (string, 1–300 letters after trim) · response: `{ok, id}`
  - errors: 400 (date missing/invalid/future, reason empty/too long, same as the current date), 404 (no profile), 409 (a request is already waiting — one pending per person, unique index V606), 429 (3 a day)
  - table: correction_requests (name and current date copied from the profile)
- [x] **`correction_mine`** — *Spring only:* my latest correction request (for the profile card: waiting / approved / rejected)
  - src: `blood/CorrectionActions.kt` · auth: user · port: **P10**
  - request: — · response: `{item: {id, status: "pending"|"approved"|"rejected", currentMillis, requestedMillis, reason, createdAtMillis, resolvedAtMillis} | null}`
- [x] **`response_update`** — Donor cancel/donated or requester removes donor; recount bags, restore/notify standby donors
  - src: `server/lib/donors.js` → `response_update` · auth: user (donor: cancel/donated; requester: remove) · port: **P1**
  - request: `requestId`, `op`, `donorUid` · response: `{ok,restored}` or `{ok}`
  - collections: inbox, requests, users, …/responses · side effects: FCM push + inbox · errors: 400, 404 · tests: demo-blood
- [x] **`confirm_donor`** — Requester confirms a donor; pushes donor
  - src: `server/lib/donors.js` → `confirm_donor` · auth: user (requester) · port: **P1**
  - request: `requestId`, `donorUid` · response: `{ok,already}` or `{ok}`
  - collections: inbox, requests, users, …/responses · side effects: FCM push + inbox · errors: 400, 404, 403 · tests: demo-blood
- [x] **`emergencies_active`** — Active emergency calls for my district or that notified me (+ my status, ask list); guest: district only, no demo
  - src: `server/lib/emergency.js` → `actions.emergencies_active` · auth: guest · port: **P1**
  - request: `district` · response: `{items,ask}`
  - collections: emergencies, emergencies/donors, inbox, users · side effects: FCM push + inbox · errors: — · tests: demo-blood, guest
- [x] **`emergency_donated`** — Donor reports donated or not; sets lastDonation forward
  - src: `server/lib/emergency.js` → `actions.emergency_donated` · auth: user · port: **P1**
  - request: `id`, `donated` · response: `{ok}`
  - collections: emergencies, emergencies/donors, users · side effects: — · errors: 400 · tests: demo-blood
- [x] **`emergency_respond`** — Donor coming/cancel; group compatibility, per-group counts
  - src: `server/lib/emergency.js` → `actions.emergency_respond` · auth: user · port: **P1**
  - request: `id`, `op` · response: `{ok}`
  - collections: emergencies, users, …/donors · side effects: — · errors: 400, 403, 409 · tests: demo-blood
- [x] **`guest_requests`** — Guest home: open requests near lat,lng (BD bounds, maxKm 5–25), no name/phone/note/location (cached per 0.05° cell 1 min)
  - src: `server/lib/guest.js` → `actions.guest_requests` · auth: guest · port: **P1**
  - request: `lat`, `lng`, `maxKm` · response: `{items}`
  - collections: requests · side effects: — · errors: 400 · tests: clientdata, demo-blood, guest
- [x] **`request_delete`** — Delete own blood request recursively; notify accepted donors
  - src: `server/lib/myrequests.js` → `actions.request_delete` · auth: user (requester) · port: **P1**
  - request: `requestId` · response: `{ok}` or `{ok,notified}`
  - collections: inbox, requests, requests/responses, users, …/responses · side effects: recursive delete, push (requestend), FCM push + inbox · errors: 400, 403 · tests: — (none)
- [x] **`request_create`** — Create blood request (validated; lat/lng rounded to 2 dp, geohash/geoOpen; private contact subdoc); limits MAX_REQUESTS_PER_DAY / open requests (serverState createDay)
  - src: `server/lib/requestcreate.js` → `actions.request_create` · auth: user · port: **P1**
  - request: `lat`, `lng`, `patientGroup`, `bags`, `hospital`, `placeName`, `neededByMillis`, `neededAtMillis`, `requesterPhone`, `requesterName`, `note`, `geohash`, `urgent`, `travelFare` · response: `{ok,id}`
  - collections: requests, serverState, …/private · side effects: — · errors: 400, 429 · tests: demo-blood, demo-health, guest, labtests
- [x] **`request_reopen`** — Reopen within 1 h of closing (undo close marks from meta/close, restore donation dates); pushes donors
  - src: `server/lib/requestcreate.js` → `actions.request_reopen` · auth: user (requester) · port: **P1**
  - request: `requestId` · response: `{ok}`
  - collections: inbox, requests, requests/responses, users, …/meta, …/responses · side effects: push (requestend), FCM push + inbox · errors: 400, 404, 403, 429 · tests: — (none)

## Providers, appointments, queue (package `appointments`) — 17 actions

*Spring, 23 Oct 2026 — online (video) chambers + the bKash fee (V620, `appointments/OnlinePay.kt`, full spec:
[CHAMBERS-ONLINE.md](CHAMBERS-ONLINE.md)):* a chamber has `mode: "in_person"|"online"`; an online chamber has no
hospital/address, `bkashNumber` (owner only) and `note`; booking it stores `appointments.online` and `payState`
`unpaid|claimed|paid` (null = nothing to pay). Video calls (`appt_join`) are in the calls package (spring-p14).

- [x] **`providers`** — Doctor/nurse chamber list with filters (district/upazila/union/hospital/specialty/day/openNow/maxFee/hospitalQ/gender/online) + facets; list cached 2 min. `online:true` = doctors with an online chamber wherever they are (area filters ignored), `online:false` = chambers at a place only; area filters never match an online chamber. Chambers carry `mode`, never `note` or `bkashNumber` (R6)
  - src: `server/lib/appointments.js` → `providers` · auth: guest caregiver · port: **P2**
  - request: `type`, `homeVisit`, `district`, `upazilaId`, `unionId`, `hospitalId`, `specialty`, `online` · response: `{items}` or `{items,facets}`
  - collections: badges, providers, users · side effects: — · errors: 400 · tests: demo-health, demo-night, demo-rx, demo-services, doctor-filters, queue
- [x] **`provider_me`** — Own provider (chambers) document; an online chamber includes `note`, `bkashNumber`, `bkashActive`, `bkashPendingUntil` (24 h cool-off after a change)
  - src: `server/lib/appointments.js` → `provider_me` · auth: user · port: **P2**
  - request: — · response: `{provider}`
  - collections: providers · side effects: — · errors: — · tests: demo-services
- [x] **`provider_save`** — Save chambers (≤ MAX_CHAMBERS at a place + 1 online for doctors, days/times, fee, appointments), specialties, degrees, gender, homeVisit; legacy shape supported; cancels stale appointments when chambers change. Online chamber `{mode:"online", days/start/end|times, fee|noFee, bkashNumber (unless noFee), note ≤200, appointments, patientMin?}`; fee must be a taka amount (0 = no fee); note without phone numbers; a changed bKash number is logged, pushed to the doctor and reaches new bookings after 24 h; demo doctors 403 unless DEMO_ONLINE_CHAMBERS
  - src: `server/lib/appointments.js` → `provider_save` · auth: user role:badge (doctor/nurse/nutritionist/trainer); phone-verified when enabled · port: **P2**
  - request: `chambers`, `specialties`, `homeVisit`, `gender`, `listed`, `appointments`, `degrees`, `chamber`, `schedule`, `district`, `active`, `specialty`, `fee` · response: `…` or `{ok,cancelled}`
  - collections: badges, providers, sanctions, serverState, users · side effects: — · errors: 403, 409:update_required, 400 · tests: — (none)
- [x] **`book`** — Patient books provider chamber date (≤60 days ahead, same world, listed+taking appointments, chamberKey check, member for family); quota book=5/day; pushes provider. Online chamber: key `online` (chamberKey optional), stores `online`, `payState` (`unpaid`, or null without a fee), fee, bKash number, note; push "নতুন অনলাইন অ্যাপয়েন্টমেন্টের অনুরোধ"
  - src: `server/lib/appointments.js` → `book` · auth: user role:badge caregiver · quota: book (5/day) · port: **P2**
  - request: `providerUid`, `date`, `chamberIndex`, `note`, `chamberKey`, `memberId` · response: `{id}`
  - collections: appointments, badges, inbox, providers, serverState, users · side effects: FCM push + inbox · errors: 400, 404, 409 · tests: demo-health, demo-night, demo-rx, doctor-filters, e2efixes, guest, labtests, queue
- [x] **`my_appointments`** — Appointments as patient and as provider (active ≤300, history ≤30 per status); other party phone when confirmed. A caregiver (`asUid`) gets the patient side only. Items add `online`, `payState`, `payRef`, `payNote`, `fee`, `noFee`, `onlineNote` (null for in-person) and on the patient side `bkashNumber`
  - src: `server/lib/appointments.js` → `my_appointments` · auth: user caregiver · port: **P2**
  - request: — · response: `{items}`
  - collections: appointments, users · side effects: — · errors: — · tests: demo-health, demo-rx
- [x] **`appointment_update`** — confirm (assigns serial via serials counter, transaction) / decline / cancel; pushes other party
  - src: `server/lib/appointments.js` → `appointment_update` · auth: user (provider: confirm/decline needs badge; patient or provider: cancel) caregiver · port: **P2**
  - request: `id`, `op`, `providerNote`, `serial` · response: `{ok}`
  - collections: appointments, badges, inbox, providers, serials, users · side effects: FCM push + inbox · errors: 400, 404, 403, 409 · tests: demo-health, demo-rx, e2efixes, queue
- [x] **`queue_today`** — Doctor/assistant: today’s chambers with confirmed serials, current position
  - src: `server/lib/queue.js` → `actions.queue_today` · auth: user role:doctor(chamber)|assistant · port: **P2**
  - request: `doctorUid` · response: `{role,date,chambers}`
  - collections: appointments, badges, providers, queues, serials · side effects: — · errors: — · tests: queue
- [x] **`queue_control`** — start \| next \| set \| pause \| resume a chamber queue (expect for stale detection); pushes upcoming patients (near turn)
  - src: `server/lib/queue.js` → `actions.queue_control` · auth: user role:doctor(chamber)|assistant · port: **P2**
  - request: `doctorUid`, `op`, `chamberKey`, `date`, `expect`, `appointmentId`, `serial` · response: `{chamber,...}`
  - collections: appointments, badges, providers, queues, serials · side effects: push to patient + caregivers · errors: 400, 404, 409 · tests: queue
- [x] **`appointment_queue`** — Live serial status, people ahead, ETA (queueStats average)
  - src: `server/lib/queue.js` → `actions.appointment_queue` · auth: user (patient of appointment) caregiver · port: **P2**
  - request: `id` · response: `{live}` or `…`
  - collections: appointments, providers, queues, serials · side effects: — · errors: 404 · tests: queue
- [x] **`appointment_late`** — "I will be late" 10/20/30/60 min + note, once per day; pushes doctor/assistants
  - src: `server/lib/queue.js` → `actions.appointment_late` · auth: user (patient of appointment) caregiver · port: **P2**
  - request: `id`, `minutes`, `note` · response: `{ok}`
  - collections: appointments, providers, queues, serials, users · side effects: FCM push + inbox · errors: 404, 409, 400 · tests: queue
- [x] **`appointment_late_reply`** — Doctor/assistant replies ok / later (moves to end); pushes patient
  - src: `server/lib/queue.js` → `actions.appointment_late_reply` · auth: user role:doctor(chamber)|assistant · port: **P2**
  - request: `id`, `op` · response: `{ok,chamber}`
  - collections: appointments, badges, providers, queues, serials · side effects: push to patient + caregivers · errors: 404, 400, 409 · tests: queue
- [x] **`appt_pay_claim`** — *Spring only (Oct 2026).* Patient: "টাকা পাঠিয়েছি" on an online appointment (pending/confirmed): unpaid/claimed → claimed, optional bKash TrxID (10 letters/digits, spaces dropped, upper-cased; a repeat without one keeps the earlier; once per doctor → 409 `pay_ref_used`; 5 claims/appointment/hour → 429 `pay_claim_rate`); pushes the doctor + assistants "রোগী ফি পাঠিয়েছেন — যাচাই করুন" (cat appointments; only when something changed)
  - src: `appointments/OnlinePay.kt` · auth: user (patient of appointment) caregiver
  - request: `appointmentId`, `payRef?` · response: `{ok, payState:"claimed", payRef}`
  - errors: 404 (not yours / demo↔real), 400 (TrxID), 409 `not_online` / `no_fee` / `state` · tests: OnlineChambersIT
- [x] **`appt_pay_confirm`** — *Spring only.* Doctor or assistant: "ফি পেয়েছি" — unpaid/claimed → paid (appointment pending/confirmed/completed; again on paid = same answer, no push); pushes the patient + caregivers "ডাক্তার ফি পেয়েছেন"
  - src: `appointments/OnlinePay.kt` · auth: user role:doctor|assistant (assistant: doctor badge approved, else 403)
  - request: `appointmentId` · response: `{ok, payState:"paid"}`
  - errors: 404, 403, 409 `not_online` / `no_fee` / `state` · tests: OnlineChambersIT
- [x] **`appt_report`** — *Spring only.* Patient (caregiver), doctor or assistant flags an appointment for the admins (one open report per person per appointment; quota report)
  - src: `appointments/OnlinePay.kt` · auth: user caregiver · request: `appointmentId`, `reason` · response: `{ok, id}` · errors: 404, 400 · tests: OnlinePaySafetyIT
- [x] **`admin_appt_reports`** / **`admin_appt_report_set`** — *Spring only.* Admins: appointment reports (open/resolved/dismissed) with the appointment, its pay log and the doctor's bKash number changes; set a report's status
  - src: `appointments/OnlinePay.kt` · auth: admin · request: `status?` / `id`, `status` · response: `{items}` / `{ok}` · tests: OnlinePaySafetyIT
- [x] **`appt_pay_reject`** — *Spring only.* Doctor or assistant: "পাইনি" — claimed → unpaid with an optional reason (`payNote`, ≤200); pushes the patient "ডাক্তার এখনো ফি পাননি — বিকাশ লেনদেনটা আবার দেখুন"
  - src: `appointments/OnlinePay.kt` · auth: user role:doctor|assistant
  - request: `appointmentId`, `reason?` · response: `{ok, payState:"unpaid", payNote}`
  - errors: 404, 403, 409 `not_online` / `no_fee` / `state` · tests: OnlineChambersIT

## Prescriptions & pharmacy orders (package `rx`) — 25 actions

- [x] **`rx_me`** — Doctor: own name, degrees, chamber names
  - src: `server/lib/prescriptions.js` → `actions.rx_me` · auth: user role:doctor · port: **P3**
  - request: — · response: `{name,degrees,chambers}`
  - collections: badges, providers · side effects: — · errors: — · tests: demo-rx
- [x] **`rx_team`** — Doctor: assistant emails
  - src: `server/lib/prescriptions.js` → `actions.rx_team` · auth: user role:doctor · port: **P3**
  - request: — · response: `{emails}`
  - collections: badges, providers · side effects: — · errors: — · tests: — (none)
- [x] **`rx_team_set`** — Doctor: set assistant emails (≤ limit, not own)
  - src: `server/lib/prescriptions.js` → `actions.rx_team_set` · auth: user role:doctor · port: **P3**
  - request: `emails` · response: `{emails}`
  - collections: badges, providers · side effects: — · errors: 400 · tests: demo-rx
- [x] **`rx_assisting`** — Assistant: doctors I assist
  - src: `server/lib/prescriptions.js` → `actions.rx_assisting` · auth: user (verified email listed as assistant) · port: **P3**
  - request: — · response: `{items}`
  - collections: badges, providers · side effects: — · errors: — · tests: demo-rx
- [x] **`rx_patients`** — Doctor/assistant: entered patients + today’s app appointments (Oct 2026: appointments add `online`, `payState`, `payRef`, `start`)
  - src: `server/lib/prescriptions.js` → `actions.rx_patients` · auth: user role:doctor|assistant · port: **P3**
  - request: `doctorUid` · response: `{role,patients,appointments,appPatients}`
  - collections: appointments, badges, providers, rxPatients · side effects: — · errors: — · tests: demo-lab, demo-rx
- [x] **`rx_patient_history`** — Doctor: earlier prescriptions of a patient (rxPatientId or appointmentId) + lab orders
  - src: `server/lib/prescriptions.js` → `actions.rx_patient_history` · auth: user role:doctor · port: **P3**
  - request: `rxPatientId`, `appointmentId` · response: `{items,labOrders}`
  - collections: appointments, badges, labOrders, prescriptions, rxPatients, rxReviews · side effects: — · errors: 404, 400 · tests: demo-rx, labhistory, labprices, rxflow
- [x] **`rx_patient_add`** — Doctor/assistant: add paper patient (name, age, gender, phone → phoneKey); dedupe; quota rxPatient=200/day
  - src: `server/lib/prescriptions.js` → `actions.rx_patient_add` · auth: user role:doctor|assistant · quota: rxPatient (200/day) · port: **P3**
  - request: `doctorUid`, `phone`, `name`, `age`, `gender` · response: `{patient,existed}`
  - collections: badges, providers, rxPatients, serverState · side effects: — · errors: 400 · tests: demo-rx, labhistory, labprices, rxflow
- [x] **`rx_create`** — Doctor writes prescription (medicines, tests, advice, follow-up, findings…) → code; links app patient or rxPatient; quota rx=300/day; pushes patient (+caregivers)
  - src: `server/lib/prescriptions.js` → `actions.rx_create` · auth: user role:doctor · quota: rx (300/day) · port: **P3**
  - request: `appointmentId`, `rxPatientId`, `patient`, `medicines`, `tests`, `advice`, `followUpDays`, `chamberIndex`, `complaints`, `findings`, `diagnosis`, `testPlace`, `testPlaceId`, `followUpNote`, `name`, `age`, `gender` · response: `{id,code}`
  - collections: appointments, badges, careLinks, inbox, prescriptions, providers, rxPatients, serverState, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 404, 400 · tests: demo-lab, demo-rx, labhistory, labprices, rxflow
- [x] **`rx_cancel`** — Cancel prescription
  - src: `server/lib/prescriptions.js` → `actions.rx_cancel` · auth: user (writing doctor) · port: **P3**
  - request: `id` · response: `{ok}`
  - collections: prescriptions · side effects: — · errors: 404 · tests: rxflow
- [x] **`rx_doctor_list`** — Doctor: own recent 150 prescriptions
  - src: `server/lib/prescriptions.js` → `actions.rx_doctor_list` · auth: user role:doctor · port: **P3**
  - request: — · response: `{items}`
  - collections: badges, prescriptions · side effects: — · errors: — · tests: demo-rx
- [x] **`rx_mine`** — Patient: my prescriptions
  - src: `server/lib/prescriptions.js` → `actions.rx_mine` · auth: user caregiver · port: **P3**
  - request: — · response: `{items}`
  - collections: prescriptions · side effects: — · errors: — · tests: demo-health, demo-pharmacy, demo-rx, demo-services, labtests, rxflow
- [x] **`rx_claim`** — Patient adds ONE paper prescription by its code + phone (profile's or a family member's) → only that prescription comes to the account (409 if another account has it); later/earlier ones are added one by one with their own codes, nothing is auto-linked (Oct 2026; Node linked all of the doctor's prescriptions for the phone). quota rxClaim=20/day
  - src: `server/lib/prescriptions.js` → `actions.rx_claim` · auth: user caregiver · quota: rxClaim (10/day) · port: **P3**
  - request: `code`, `phone` · response: `{ok,added}`
  - collections: prescriptions, rxPatients, serverState, users · side effects: — · errors: 400, 409 · tests: demo-rx, labhistory, labprices, rxflow
- [x] **`rx_get`** — One prescription + lab orders/reviews
  - src: `server/lib/prescriptions.js` → `actions.rx_get` · auth: user (patient or doctor of rx) caregiver · port: **P3**
  - request: `id` · response: `{item,labOrders}` or `{item}`
  - collections: labOrders, prescriptions, rxReviews · side effects: — · errors: 404 · tests: demo-health, labhistory
- [x] **`rx_pharmacy_open`** — Pharmacy opens prescription by code (same world); quota rxPharm=300/day
  - src: `server/lib/rxnet.js` → `actions.rx_pharmacy_open` · auth: user role:approved-pharmacy · quota: rxPharm (300/day) · port: **P4**
  - request: `code` · response: `{item}`
  - collections: pharmacies, prescriptions, serverState · side effects: — · errors: — · tests: demo-rx, demo-services
- [x] **`rx_dispense`** — Pharmacy records dispensed quantities (items [{i,qty}], missing) → prescriptions.dispensed[], completes rxOrder, pharmacyMissed; pushes patient
  - src: `server/lib/rxnet.js` → `actions.rx_dispense` · auth: user role:approved-pharmacy · port: **P4**
  - request: `code`, `items`, `missing` · response: `{item}`
  - collections: careLinks, inbox, pharmacies, pharmacyMissed, prescriptions, rxOrders, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 409, 400 · tests: demo-pharmacy, demo-rx, demo-services, privacy-gaps, rxflow, shopbooks
- [x] **`rx_pharmacy_options`** — Patient: nearby pharmacies for an rx (shop/online modes, stock match, estimate)
  - src: `server/lib/rxnet.js` → `actions.rx_pharmacy_options` · auth: user caregiver · port: **P4**
  - request: `rxId`, `district`, `mode`, `q`, `upazilaId`, `lat`, `lng`, `placeId` · response: `{items}`
  - collections: pharmacies, pharmacyStock, prescriptions · side effects: — · errors: 400 · tests: demo-health, demo-pharmacy, demo-rx, demo-services, pharmacyestimate, rxflow
- [x] **`rx_shop_find`** — Patient: find shop by 6-char shop code; quota medTry=30/day
  - src: `server/lib/rxnet.js` → `actions.rx_shop_find` · auth: user caregiver · quota: medTry (30/day) · port: **P4**
  - request: `code` · response: `{item:{id,name,address,phone,online,deliveryFee}}`
  - collections: pharmacies, serverState · side effects: — · errors: 400, 404 · tests: demo-pharmacy, rxflow
- [x] **`rx_send_pharmacy`** — Patient sends rx to pharmacy (shop or online delivery with address/phone/note); quota rxSend=20/day; pushes shop
  - src: `server/lib/rxnet.js` → `actions.rx_send_pharmacy` · auth: user caregiver · quota: rxSend (20/day) · port: **P4**
  - request: `rxId`, `pharmacyId`, `mode`, `address`, `phone`, `note` · response: `{id}`
  - collections: inbox, pharmacies, prescriptions, rxOrders, serverState, users · side effects: FCM push + inbox · errors: 409, 404, 400 · tests: demo-health, demo-pharmacy, demo-rx, demo-services, labtests, pharmacylist-rx, rxflow
- [x] **`rx_order_withdraw`** — Withdraw order (reason not_available → pharmacyMissed)
  - src: `server/lib/rxnet.js` → `actions.rx_order_withdraw` · auth: user (patient of order) caregiver · port: **P4**
  - request: `id`, `reason` · response: `{ok}`
  - collections: inbox, pharmacyMissed, prescriptions, rxOrders, users · side effects: FCM push + inbox · errors: 409 · tests: demo-health, labtests, privacy-gaps, rxflow
- [x] **`rx_orders_pharmacy`** — Pharmacy: incoming orders (segments, counts)
  - src: `server/lib/rxnet.js` → `actions.rx_orders_pharmacy` · auth: user role:approved-pharmacy · port: **P4**
  - request: — · response: `{items,counts}`
  - collections: pharmacies, rxOrders · side effects: — · errors: — · tests: demo-pharmacy, demo-rx, demo-services, rxflow, worksegs
- [x] **`rx_order_update`** — Pharmacy: ready/cancelled (shop) or accepted(price, eta)/out/cancelled (online); pushes patient
  - src: `server/lib/rxnet.js` → `actions.rx_order_update` · auth: user role:approved-pharmacy · port: **P4**
  - request: `id`, `status`, `reason`, `missing`, `price`, `eta` · response: `{ok}`
  - collections: careLinks, inbox, pharmacies, pharmacyMissed, prescriptions, rxOrders, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 409 · tests: demo-pharmacy, demo-rx, demo-services, privacy-gaps, rxflow, worksegs
- [x] **`rx_pharmacy_home`** — Pharmacy: shop code (generated once), online settings, missed list
  - src: `server/lib/rxnet.js` → `actions.rx_pharmacy_home` · auth: user role:approved-pharmacy · port: **P4**
  - request: — · response: `{shopCode,online,hasLocation,missed}`
  - collections: pharmacies, pharmacyMissed · side effects: — · errors: — · tests: demo-pharmacy, demo-rx, demo-services, rxflow
- [x] **`rx_pharmacy_online_set`** — Pharmacy: online selling enabled, radiusKm, fee, note
  - src: `server/lib/rxnet.js` → `actions.rx_pharmacy_online_set` · auth: user role:approved-pharmacy · port: **P4**
  - request: `enabled`, `radiusKm`, `fee`, `note` · response: `{online}`
  - collections: pharmacies · side effects: — · errors: 400 · tests: rxflow
- [x] **`rx_missed_clear`** — Pharmacy: clear one (k) or all missed medicines
  - src: `server/lib/rxnet.js` → `actions.rx_missed_clear` · auth: user role:approved-pharmacy · port: **P4**
  - request: `k` · response: `{ok}`
  - collections: pharmacies, pharmacyMissed · side effects: — · errors: — · tests: — (none)
- [x] **`rx_activity`** — Patient: pharmacy/lab orders, reviews, dispensed for an rx
  - src: `server/lib/rxnet.js` → `actions.rx_activity` · auth: user (patient of rx) caregiver · port: **P3**
  - request: `rxId` · response: `{medicines,dispensed,orders,labOrders:[...],reviews:[...],nextOpen}`
  - collections: labOrders, prescriptions, providers, rxOrders, rxReviews · side effects: — · errors: — · tests: demo-health, demo-lab, demo-pharmacy, demo-rx, demo-services, labprices, privacy-gaps, rxflow

## Pharmacy listing, stock, medicine finder (package `pharmacy`) — 17 actions

- [x] **`drug_search`** — Type-ahead drug search over bundled drugs.json
  - src: `server/lib/medfind.js` → `actions.drug_search` · auth: guest · port: **P4**
  - request: `q` · response: `{items:[{i,name,generic,form,company}]}`
  - collections: — · side effects: — · errors: — · tests: demo-night, demo-pharmacy, demo-services, pharmacy24
- [x] **`med_find`** — "Which shop has it?": push ≤15 nearby open approved pharmacies (find setting on, ≤30/day/shop); quota medFind=5/day
  - src: `server/lib/medfind.js` → `actions.med_find` · auth: user · quota: medFind (5/day) · port: **P4**
  - request: `i`, `district`, `upazilaId`, `lat`, `lng` · response: `{ok,noPharmacy}` or `{ok,noneOpen}` or `{ok,id,notified}`
  - collections: inbox, medFinds, pharmacies, serverState, users · side effects: FCM push + inbox · errors: 400 · tests: demo-health, demo-night, demo-pharmacy, demo-services, guest, pharmacy24
- [x] **`med_finds_mine`** — My last 10 finds with shops answering "have"
  - src: `server/lib/medfind.js` → `actions.med_finds_mine` · auth: user · port: **P4**
  - request: — · response: `{items:[...]}`
  - collections: medFinds, pharmacies · side effects: — · errors: — · tests: demo-pharmacy, demo-services
- [x] **`med_find_close`** — Close my find
  - src: `server/lib/medfind.js` → `actions.med_find_close` · auth: user (finder) · port: **P4**
  - request: `id` · response: `{ok}`
  - collections: medFinds · side effects: — · errors: 400, 404 · tests: — (none)
- [x] **`med_find_requests`** — Shop: open finds sent to me
  - src: `server/lib/medfind.js` → `actions.med_find_requests` · auth: user (approved pharmacy) · port: **P4**
  - request: — · response: `{items,findOff}`
  - collections: medFinds, pharmacies · side effects: — · errors: — · tests: demo-night, demo-pharmacy, demo-services
- [x] **`med_find_answer`** — Shop answers have/not; pushes finder on have
  - src: `server/lib/medfind.js` → `actions.med_find_answer` · auth: user (approved pharmacy) · port: **P4**
  - request: `id`, `have` · response: `{ok}`
  - collections: inbox, medFinds, pharmacies, users · side effects: FCM push + inbox · errors: 400, 403, 404, 409 · tests: demo-pharmacy, demo-services
- [x] **`pharmacy_find_setting`** — Opt in/out of medicine find requests (findOff)
  - src: `server/lib/medfind.js` → `actions.pharmacy_find_setting` · auth: user (pharmacy owner) · port: **P4**
  - request: `off` · response: `{ok}`
  - collections: pharmacies · side effects: — · errors: 404 · tests: — (none)
- [x] **`pharmacy_me`** — Own pharmacy (with docs status) or null
  - src: `server/lib/pharmacy.js` → `actions.pharmacy_me` · auth: user · port: **P4**
  - request: — · response: `{pharmacy}`
  - collections: pharmacies · side effects: — · errors: — · tests: demo-night, demo-pharmacy, demo-services, pharmacylist-rx
- [x] **`pharmacy_save`** — Create/edit pharmacy (license, phone, address, hours, closedDays, discounts, delivery, docs); re-verify; sanctions; quota pharmacy=5/day
  - src: `server/lib/pharmacy.js` → `actions.pharmacy_save` · auth: user · quota: pharmacy (5/day) · port: **P4**
  - request: `name`, `licenseNo`, `phone`, `district`, `upazilaId`, `address`, `lat`, `lng`, `open24`, `openAt`, `closeAt`, `discount`, `discountMin`, `freeDeliveryOver`, `discountNote`, `termsAccepted`, `closedDays`, `delivery`, `deliveryNote`, `docs` · response: `{ok,pending}`
  - collections: pharmacies, sanctions, serverState · side effects: — · errors: 400, 403 · tests: demo-services, pharmacylist-rx
- [x] **`pharmacy_pause`** — Temporarily closed until (≤60 days) or reopen
  - src: `server/lib/pharmacy.js` → `actions.pharmacy_pause` · auth: user (pharmacy owner) · port: **P4**
  - request: `until` · response: `{ok}`
  - collections: pharmacies · side effects: — · errors: 404, 400 · tests: demo-pharmacy
- [x] **`pharmacy_delete`** — Remove own pharmacy (not suspended); deletes stats, ratings, docs, strips order contacts
  - src: `server/lib/pharmacy.js` → `actions.pharmacy_delete` · auth: user (pharmacy owner) · port: **P4**
  - request: — · response: `{ok}`
  - collections: modReports, pharmacies, pharmacyRatings, pharmacyStats, rxOrders, verifyDocs · side effects: — · errors: 403 · tests: pharmacyrate, privacy-gaps
- [x] **`pharmacies`** — Approved pharmacies in district; modes shop/online, rxId → per-card stock match & estimate; openNow/open24; canRate; same world when mode/rxId
  - src: `server/lib/pharmacy.js` → `actions.pharmacies` · auth: guest caregiver · port: **P4**
  - request: `district`, `upazilaId`, `mode`, `rxId`, `lat`, `lng`, `openNow`, `open24` · response: `{items:[{...,canRate,rateAspects}],...}`
  - collections: pharmacies, pharmacyCalls, pharmacyStock, prescriptions, rxOrders, serverState · side effects: — · errors: 400 · tests: demo-ambulance, demo-pharmacy, demo-services, e2efixes, pharmacy24, pharmacylist-rx, pharmacyrate
- [x] **`pharmacy_called`** — Record call (10-min dedupe) + pharmacyStats
  - src: `server/lib/pharmacy.js` → `actions.pharmacy_called` · auth: user · port: **P4**
  - request: `id` · response: `{ok}`
  - collections: pharmacies, pharmacyCalls · side effects: — · errors: — · tests: demo-health, demo-services, pharmacyrate
- [x] **`pharmacy_rate`** — Aspect rating (behaviour/price/delivery) after call or purchase; one per user per shop, editable
  - src: `server/lib/pharmacy.js` → `actions.pharmacy_rate` · auth: user · port: **P4**
  - request: `aspects`, `rating`, `comment` · response: `{ok,rating}`
  - collections: pharmacies, pharmacyCalls, pharmacyRatings, prescriptions, rxOrders · side effects: — · errors: 400, 404, 403 · tests: demo-services, pharmacyrate
- [x] **`pharmacy_stats`** — Owner: calls, rating, aspects, recent comments, reports
  - src: `server/lib/pharmacy.js` → `actions.pharmacy_stats` · auth: user · port: **P4**
  - request: — · response: `{...,rating,ratingCount,aspects,comments:[...],reports}`
  - collections: modReports, pharmacies, pharmacyStats · side effects: — · errors: — · tests: demo-pharmacy, demo-services, pharmacyrate
- [x] **`stock_get`** — Pharmacy: in-stock names
  - src: `server/lib/rxnet.js` → `actions.stock_get` · auth: user role:approved-pharmacy · port: **P4**
  - request: — · response: `{items,updatedAt}`
  - collections: pharmacies, pharmacyStock · side effects: — · errors: — · tests: demo-pharmacy, demo-services
- [x] **`stock_set`** — Pharmacy: add/remove stock names (≤3000)
  - src: `server/lib/rxnet.js` → `actions.stock_set` · auth: user role:approved-pharmacy · port: **P4**
  - request: `add`, `remove` · response: `{items}`
  - collections: pharmacies, pharmacyStock · side effects: — · errors: 400 · tests: demo-pharmacy, demo-services, pharmacyestimate, pharmacylist-rx, rxflow, shopbooks

## Pharmacy shop books (package `books`) — 15 actions

- [x] **`books_items`** — Products with stock, price, cost, expiry, low flags
  - src: `server/lib/shopbooks.js` → `actions.books_items` · auth: user role:approved-pharmacy · port: **P4**
  - request: — · response: `{items,units}`
  - collections: pharmacies, pharmacyStock, shopItems/items · side effects: — · errors: — · tests: pharmacyestimate, shopbooks
- [x] **`books_item_save`** — Add/edit product; qty change recorded as count adjustment; syncs pharmacyStock
  - src: `server/lib/shopbooks.js` → `actions.books_item_save` · auth: user role:approved-pharmacy · port: **P4**
  - request: `name`, `id`, `price`, `disc`, `generic`, `form`, `unit`, `cost`, `lowAt`, `expiry`, `qty` · response: `{item}`
  - collections: pharmacies, pharmacyStock, shopBooks/days, shopBooks/{}, shopItems/items · side effects: — · errors: 400, 409 · tests: pharmacyestimate, pharmacylist-rx, shopbooks
- [x] **`books_item_delete`** — Delete product (history kept); syncs stock list
  - src: `server/lib/shopbooks.js` → `actions.books_item_delete` · auth: user role:approved-pharmacy · port: **P4**
  - request: `id` · response: `{ok}`
  - collections: pharmacies, pharmacyStock, shopItems/items · side effects: — · errors: — · tests: pharmacyestimate, shopbooks
- [x] **`books_item_stock`** — Manual in-stock on/off for non-quantity shops
  - src: `server/lib/shopbooks.js` → `actions.books_item_stock` · auth: user role:approved-pharmacy · port: **P4**
  - request: `id`, `on` · response: `…`
  - collections: pharmacies, pharmacyStock, shopItems/items · side effects: — · errors: — · tests: shopbooks
- [x] **`books_purchase_add`** — Purchase: qty up, average cost, nearest expiry, optional new MRP; new names create items; day aggregates
  - src: `server/lib/shopbooks.js` → `actions.books_purchase_add` · auth: user role:approved-pharmacy · port: **P4**
  - request: `at`, `lines`, `supplier`, `note` · response: `{id,purchase:{id,...}}`
  - collections: pharmacies, pharmacyStock, shopBooks/days, shopBooks/{}, shopItems/items · side effects: — · errors: 400 · tests: pharmacyestimate, shopbooks
- [x] **`books_purchases`** — Purchases of last 90 days (≤200)
  - src: `server/lib/shopbooks.js` → `actions.books_purchases` · auth: user role:approved-pharmacy · port: **P4**
  - request: — · response: `{items:[...]}`
  - collections: pharmacies, shopBooks/{} · side effects: — · errors: — · tests: shopbooks
- [x] **`books_purchase_delete`** — Undo purchase (qty back; undo map); day aggregates
  - src: `server/lib/shopbooks.js` → `actions.books_purchase_delete` · auth: user role:approved-pharmacy · port: **P4**
  - request: `id` · response: `{ok}`
  - collections: pharmacies, pharmacyStock, shopBooks/days, shopBooks/purchases, shopItems/items · side effects: — · errors: — · tests: pharmacyestimate, shopbooks
- [x] **`books_sale_add`** — Counter sale or rx sale (from dispensed lines, once per dispensing); line/bill discounts; profit; day aggregates
  - src: `server/lib/shopbooks.js` → `actions.books_sale_add` · auth: user role:approved-pharmacy · port: **P4**
  - request: `source`, `rx`, `billDisc`, `at`, `lines`, `customer` · response: `{id,sale:{id,...}}`
  - collections: pharmacies, pharmacyStock, prescriptions, rxOrders, shopBooks/days, shopBooks/sales, shopBooks/{}, shopItems/items · side effects: — · errors: 404, 400, 409 · tests: privacy-gaps, shopbooks
- [x] **`books_rx_preview`** — Preview rx dispensing as sale lines (matched items, prices, cost)
  - src: `server/lib/shopbooks.js` → `actions.books_rx_preview` · auth: user role:approved-pharmacy · port: **P4**
  - request: `code`, `at` · response: `{code,at,source,recorded,lines}`
  - collections: pharmacies, pharmacyStock, prescriptions, rxOrders, shopBooks/sales, shopItems/items · side effects: — · errors: 404 · tests: privacy-gaps, shopbooks
- [x] **`books_sales`** — Sales by period (today/7d/30d/month, ≤300)
  - src: `server/lib/shopbooks.js` → `actions.books_sales` · auth: user role:approved-pharmacy · port: **P4**
  - request: `period` · response: `{items}`
  - collections: pharmacies, shopBooks/{} · side effects: — · errors: — · tests: privacy-gaps, shopbooks
- [x] **`books_sale_delete`** — Delete sale; qty and day aggregates reversed
  - src: `server/lib/shopbooks.js` → `actions.books_sale_delete` · auth: user role:approved-pharmacy · port: **P4**
  - request: `id` · response: `{ok}`
  - collections: pharmacies, pharmacyStock, shopBooks/days, shopBooks/sales, shopItems/items · side effects: — · errors: — · tests: shopbooks
- [x] **`books_adjust`** — Write-off (loss at cost) or stock count correction
  - src: `server/lib/shopbooks.js` → `actions.books_adjust` · auth: user role:approved-pharmacy · port: **P4**
  - request: `kind`, `itemId`, `qty`, `reason` · response: `{id,adjust:{id,...}}`
  - collections: pharmacies, pharmacyStock, shopBooks/days, shopBooks/{}, shopItems/items · side effects: — · errors: 400 · tests: pharmacyestimate, shopbooks
- [x] **`books_adjusts`** — Adjustments of last 90 days
  - src: `server/lib/shopbooks.js` → `actions.books_adjusts` · auth: user role:approved-pharmacy · port: **P4**
  - request: — · response: `{items:[...]}`
  - collections: pharmacies, shopBooks/{} · side effects: — · errors: — · tests: shopbooks
- [x] **`books_today`** — Today card: sales, net, profit, write-off, low stock, expiring in 60 days, priced flag
  - src: `server/lib/shopbooks.js` → `actions.books_today` · auth: user role:approved-pharmacy · port: **P4**
  - request: — · response: `{sales,net,profit,writeOff,low,expiring,priced}`
  - collections: pharmacies, shopBooks/days, shopItems/items · side effects: — · errors: — · tests: shopbooks
- [x] **`books_report`** — P&L report for period from daily aggregates + stock valuation
  - src: `server/lib/shopbooks.js` → `actions.books_report` · auth: user role:approved-pharmacy · port: **P4**
  - request: `period` · response: `…`
  - collections: pharmacies, pharmacyStock, shopBooks/days, shopItems/items · side effects: — · errors: — · tests: shopbooks

## Labs, lab orders, report reviews (package `lab`) — 26 actions

- [x] **`lab_prices_get`** — Lab owner: own test & price list with catalog
  - src: `server/lib/labprices.js` → `actions.lab_prices_get` · auth: user role:approved-lab · port: **P3**
  - request: — · response: `…`
  - collections: config, labPrices, labs · side effects: — · errors: — · tests: demo-lab, labprices
- [x] **`lab_prices_set`** — Save whole price list (codes or own names, price, tat, on), discountPct, homeFee; returns kept hidden-code names
  - src: `server/lib/labprices.js` → `actions.lab_prices_set` · auth: user role:approved-lab · port: **P3**
  - request: `items`, `discountPct`, `homeFee` · response: `{...,kept}`
  - collections: config, labPrices, labs · side effects: — · errors: 400 · tests: labprices, pharmacyestimate
- [x] **`lab_prices_public`** — Public price list of one approved lab (cached 1 min)
  - src: `server/lib/labprices.js` → `actions.lab_prices_public` · auth: guest caregiver · port: **P3**
  - request: `labId` · response: `{prices}`
  - collections: config, labPrices, labs · side effects: — · errors: 404 · tests: demo-lab, labprices
- [x] **`labtests_get`** — Lab test catalogue (config/labTests merged with defaults)
  - src: `server/lib/labtests.js` → `actions.labtests_get` · auth: user (also web-panel, caregiver) · port: **P3**
  - request: — · response: `{tests}`
  - collections: config · side effects: — · errors: — · tests: demo-lab
- [x] **`lab_me`** — Own lab (with docs status) or null
  - src: `server/lib/rxnet.js` → `actions.lab_me` · auth: user · port: **P3**
  - request: — · response: `{lab}` or `{lab:{...,...,docsRequestNote}}`
  - collections: labs · side effects: — · errors: — · tests: demo-lab, demo-services
- [x] **`lab_save`** — Register/edit lab (DGHS facilityId or own name, license, hours, homeCollection, docs); re-verify; quota lab=3/day
  - src: `server/lib/rxnet.js` → `actions.lab_save` · auth: user · quota: lab (3/day) · port: **P3**
  - request: `facilityId`, `name`, `district`, `upazilaId`, `address`, `licenseNo`, `phone`, `termsAccepted`, `hours`, `homeCollection`, `docs` · response: `{ok,pending}`
  - collections: hospitals, labs, serverState · side effects: — · errors: 400, 403 · tests: — (none)
- [x] **`lab_delete`** — Delete own lab (not suspended) + prices + docs; strips home addresses
  - src: `server/lib/rxnet.js` → `actions.lab_delete` · auth: user (lab owner) · port: **P3**
  - request: — · response: `{ok}`
  - collections: labOrders, labPrices, labs, verifyDocs · side effects: — · errors: 403 · tests: labprices
- [x] **`labs`** — Approved labs in district (price summary)
  - src: `server/lib/rxnet.js` → `actions.labs` · auth: guest caregiver · port: **P3**
  - request: `district`, `upazilaId`, `facilityId` · response: `{items}`
  - collections: config, labPrices, labs · side effects: — · errors: 400 · tests: demo-ambulance, demo-health, demo-lab, demo-rx, demo-services, guest, labprices
- [x] **`rx_lab_options`** — Patient: labs for rx tests (doctor’s place first, coverage, price, distance), lastHome
  - src: `server/lib/rxnet.js` → `actions.rx_lab_options` · auth: user caregiver · quota: rxPharm (300/day) · port: **P3**
  - request: `rxId`, `district`, `tests`, `upazilaId`, `lat`, `lng`, `placeId` · response: `{items,lastHome}`
  - collections: config, labPrices, labs, prescriptions, serverState · side effects: — · errors: 400 · tests: demo-lab, labprices, pharmacyestimate
- [x] **`rx_send_lab`** — Patient sends tests to lab (optional home collection: address, phone, day, slot, note); price snapshot; quota rxSend; pushes lab
  - src: `server/lib/rxnet.js` → `actions.rx_send_lab` · auth: user caregiver · quota: rxSend (20/day) · port: **P3**
  - request: `rxId`, `labId`, `tests`, `home` · response: `{id}`
  - collections: config, inbox, labOrders, labPrices, labs, prescriptions, serverState, users · side effects: FCM push + inbox · errors: 409, 404, 400 · tests: demo-health, demo-lab, demo-rx, labhistory, labprices, rxflow
- [x] **`lab_orders`** — Lab: incoming orders (segments, counts)
  - src: `server/lib/rxnet.js` → `actions.lab_orders` · auth: user role:approved-lab · port: **P3**
  - request: — · response: `{items,counts}`
  - collections: labOrders, labs · side effects: — · errors: — · tests: demo-lab, demo-rx, demo-services, labhistory, labprices, privacy-gaps, rxflow, worksegs
- [x] **`lab_open_code`** — Lab opens rx by code → walk-in order; quota rxPharm
  - src: `server/lib/rxnet.js` → `actions.lab_open_code` · auth: user role:approved-lab · quota: rxPharm (300/day) · port: **P3**
  - request: `code` · response: `{item}`
  - collections: config, labOrders, labPrices, labs, prescriptions, serverState · side effects: — · errors: 409, 400 · tests: demo-lab, labprices
- [x] **`lab_order_status`** — Lab: received / cancelled; pushes patient
  - src: `server/lib/rxnet.js` → `actions.lab_order_status` · auth: user role:approved-lab · port: **P3**
  - request: `id`, `status` · response: `{ok}`
  - collections: careLinks, inbox, labOrders, labs, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 409 · tests: demo-lab, demo-rx, labhistory, labprices, rxflow
- [x] **`lab_home_confirm`** — Lab confirms home collection date/time; pushes patient
  - src: `server/lib/rxnet.js` → `actions.lab_home_confirm` · auth: user role:approved-lab · port: **P3**
  - request: `id`, `date`, `time`, `note` · response: `{ok,at}`
  - collections: careLinks, inbox, labOrders, labs, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 409 · tests: demo-lab, labprices
- [x] **`lab_home_decline`** — Lab declines home collection (order stays); pushes patient
  - src: `server/lib/rxnet.js` → `actions.lab_home_decline` · auth: user role:approved-lab · port: **P3**
  - request: `id`, `note` · response: `{ok}`
  - collections: careLinks, inbox, labOrders, labs, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 409 · tests: demo-lab, labprices, privacy-gaps
- [x] **`lab_home_cancel`** — Patient cancels home collection before confirmation (removes address/phone); pushes lab
  - src: `server/lib/rxnet.js` → `actions.lab_home_cancel` · auth: user (patient of order) caregiver · port: **P3**
  - request: `id` · response: `{ok}`
  - collections: inbox, labOrders, users · side effects: FCM push + inbox · errors: — · tests: labprices
- [x] **`lab_home_change`** — Patient changes home collection details; pushes lab
  - src: `server/lib/rxnet.js` → `actions.lab_home_change` · auth: user (patient of order) caregiver · port: **P3**
  - request: `home`, `id` · response: `{ok}`
  - collections: inbox, labOrders, labs, serverState, users · side effects: FCM push + inbox · errors: 404, 400 · tests: labprices
- [x] **`lab_report`** — Lab submits results (items, note, ≤2 photos) → reported; creates doctor review request; pushes patient
  - src: `server/lib/rxnet.js` → `actions.lab_report` · auth: user role:approved-lab · port: **P3**
  - request: `id`, `items`, `photos`, `note` · response: `{ok}`
  - collections: careLinks, inbox, labOrders, labs, providers, rxReviews, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 400 · tests: demo-lab, demo-rx, labhistory, labprices, rxflow
- [x] **`lab_order_get`** — Read one lab order/report + history
  - src: `server/lib/rxnet.js` → `actions.lab_order_get` · auth: user (patient, lab, or doctor when paid/no fee → else 402) caregiver · port: **P3**
  - request: `id` · response: `{item,history}`
  - collections: labOrders, rxReviews · side effects: — · errors: 402 · tests: demo-health, demo-lab, demo-rx, labhistory, labprices, privacy-gaps, rxflow
- [x] **`lab_form`** — Lab: report form templates for order tests (lab’s own ranges)
  - src: `server/lib/rxnet.js` → `actions.lab_form` · auth: user role:approved-lab · port: **P3**
  - request: `id` · response: `{forms:[...]}`
  - collections: config, labOrders, labs · side effects: — · errors: — · tests: demo-lab, demo-rx, rxflow
- [x] **`lab_ranges_set`** — Lab: save own normal ranges
  - src: `server/lib/rxnet.js` → `actions.lab_ranges_set` · auth: user role:approved-lab · port: **P3**
  - request: `ranges` · response: `{ok,count}`
  - collections: labs · side effects: — · errors: 400 · tests: demo-lab, demo-services, rxflow
- [x] **`review_settings`** — Doctor report-review settings (enabled, fee/noFee, pay number, methods)
  - src: `server/lib/rxnet.js` → `actions.review_settings` · auth: user role:doctor · port: **P3**
  - request: `set`, `enabled`, `noFee`, `fee`, `payMethods`, `payNumber` · response: `…`
  - collections: badges, providers · side effects: — · errors: 403, 400 · tests: labhistory
- [x] **`review_pay`** — Patient submits TrxID/method; pushes doctor
  - src: `server/lib/rxnet.js` → `actions.review_pay` · auth: user (patient of review) caregiver · port: **P3**
  - request: `id`, `trxId`, `method` · response: `{ok}`
  - collections: inbox, rxReviews, users · side effects: FCM push + inbox · errors: 409, 400 · tests: demo-lab, demo-rx, labhistory, labtests
- [x] **`reviews_doctor`** — Doctor/assistant: review requests with TrxIDs
  - src: `server/lib/rxnet.js` → `actions.reviews_doctor` · auth: user role:doctor|assistant · port: **P3**
  - request: `doctorUid` · response: `{items}`
  - collections: badges, providers, rxReviews · side effects: — · errors: — · tests: demo-rx, labhistory
- [x] **`review_confirm`** — Doctor/assistant confirms payment ok/not; pushes patient
  - src: `server/lib/rxnet.js` → `actions.review_confirm` · auth: user role:doctor|assistant · port: **P3**
  - request: `doctorUid`, `id`, `ok`, `note` · response: `{ok}`
  - collections: badges, careLinks, inbox, providers, rxReviews, users · side effects: push to patient + caregivers, FCM push + inbox · errors: — · tests: demo-lab, demo-rx, labhistory
- [x] **`review_done`** — Doctor comments on report (optional newRxId); pushes patient
  - src: `server/lib/rxnet.js` → `actions.review_done` · auth: user role:doctor · port: **P3**
  - request: `id`, `comment`, `newRxId` · response: `{ok}`
  - collections: badges, careLinks, inbox, rxReviews, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 403, 400 · tests: demo-lab, demo-rx, labhistory

## Ambulance listing & "call an ambulance" (package `ambulance`) — 22 actions

- [x] **`amb_facilities`** — Destination hospital list for district (cached 10 min) or search q / nearest to lat,lng
  - src: `server/lib/ambplace.js` → `actions.amb_facilities` · auth: user · port: **P5**
  - request: `district`, `lat`, `lng`, `q` · response: `…`
  - collections: — · side effects: — · errors: 400 · tests: ambulance-place
- [x] **`maps_link_resolve`** — Google Maps link/text → {lat,lng}; short links resolved by HTTP redirect (per-uid in-memory limit, 429)
  - src: `server/lib/ambplace.js` → `actions.maps_link_resolve` · auth: user · port: **P5**
  - request: `url` · response: `{lat,lng}` or `…`
  - collections: — · side effects: — · errors: 400, 429 · tests: ambulance-place
- [x] **`ambreq_create`** — "Call an ambulance": pick candidates (≤130 km, available, type, fare filter), wave of 5, push drivers; one active request per patient; PER_HOUR=5 (ambReqLimits)
  - src: `server/lib/ambreq.js` → `actions.ambreq_create` · auth: user · port: **P5**
  - request: `district`, `unionId`, `type`, `phone`, `pickup`, `note`, `ambulanceId`, `dest`, `maxFare`, `includeUnknown`, `oxygen` · response: `{id,notified,request}`
  - collections: ambReqLimits, ambulanceRequests, ambulances, users · side effects: FCM push + inbox · errors: 400, 409:no_fare_match, 409:no_ambulance, 409:amb_req_active, 429 · tests: ambulance-fare, ambulance-place, ambulance-request
- [x] **`ambreq_count`** — Preview count of matching free ambulances for the fare/type/destination (no writes; district list cached 10 s)
  - src: `server/lib/ambreq.js` → `actions.ambreq_count` · auth: user · port: **P5**
  - request: `district`, `type`, `dest`, `maxFare`, `includeUnknown`, `oxygen` · response: `{count,total,unknown,levels:{},raiseTo,hospitals,night,...}` or `{count,total,unknown,levels,raiseTo,hospitals,night,...}`
  - collections: ambulances · side effects: — · errors: 400 · tests: ambulance-fare, ambulance-place
- [x] **`ambreq_status`** — Patient waiting page: request by id or own active (ambReqLimits.activeId); advances timeouts lazily
  - src: `server/lib/ambreq.js` → `actions.ambreq_status` · auth: user (patient of request) · port: **P5**
  - request: `id` · response: `{request}`
  - collections: ambReqLimits, ambulanceRequests, ambulances, users · side effects: push to patient + caregivers, FCM push + inbox · errors: — · tests: ambulance-fare, ambulance-request
- [x] **`ambreq_cancel`** — Patient cancels with reason; frees ambulance, pushes driver(s)
  - src: `server/lib/ambreq.js` → `actions.ambreq_cancel` · auth: user (patient) · port: **P5**
  - request: `id`, `reason` · response: `{ok}`
  - collections: ambulanceRequests, ambulances, users · side effects: FCM push + inbox · errors: — · tests: ambulance-fare, ambulance-request
- [x] **`ambreq_driver`** — Driver inbox: open requests sent to me + the one I accepted (advances lazily)
  - src: `server/lib/ambreq.js` → `actions.ambreq_driver` · auth: user · port: **P5**
  - request: — · response: `{items}`
  - collections: ambulanceRequests, ambulances, users · side effects: push to patient + caregivers, FCM push + inbox · errors: — · tests: ambulance-fare, ambulance-place, ambulance-request
- [x] **`ambreq_accept`** — "Coming": quote mode stores fare offer (≤ budget, 2 min validity) else first-come accept; marks ambulance busy
  - src: `server/lib/ambreq.js` → `actions.ambreq_accept` · auth: user (driver the request was sent to) · port: **P5**
  - request: `id`, `fare` · response: `{ok,request}`
  - collections: ambulanceRequests, ambulances, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 409:taken, 404, 409, 409:busy, 400:fare_needed, 400, 409:over_budget · tests: ambulance-fare, ambulance-request
- [x] **`ambreq_agree`** — Patient accepts a quote → accepted, agreedFare; releases other quotes, pushes losers
  - src: `server/lib/ambreq.js` → `actions.ambreq_agree` · auth: user (patient) · port: **P5**
  - request: `id`, `ambulanceId` · response: `{ok,request}`
  - collections: ambulanceRequests, ambulances, users · side effects: FCM push + inbox, push to patient + caregivers · errors: 400, 409:quote_gone, 409:state · tests: ambulance-fare
- [x] **`ambreq_budget`** — Raise max fare / includeUnknown; sends to ≤5 newly matching ambulances, resets 3-min expiry
  - src: `server/lib/ambreq.js` → `actions.ambreq_budget` · auth: user (patient) · port: **P5**
  - request: `id`, `maxFare`, `includeUnknown` · response: `{ok,added,request}`
  - collections: ambulanceRequests, ambulances, users · side effects: FCM push + inbox, push to patient + caregivers · errors: 409:state, 400 · tests: ambulance-fare
- [x] **`ambreq_decline`** — Driver declines; widens early if everyone declined
  - src: `server/lib/ambreq.js` → `actions.ambreq_decline` · auth: user (driver) · port: **P5**
  - request: `id` · response: `{ok}`
  - collections: ambulanceRequests, ambulances, users · side effects: push to patient + caregivers, FCM push + inbox · errors: — · tests: ambulance-request
- [x] **`ambreq_step`** — Driver: arrived / done / cancel(reason); records trip on ambulanceCalls (agreedFare) for rating
  - src: `server/lib/ambreq.js` → `actions.ambreq_step` · auth: user (accepted driver) · port: **P5**
  - request: `id`, `step`, `reason` · response: `{ok}`
  - collections: ambulanceCalls, ambulanceRequests, ambulances, users · side effects: push to patient + caregivers, FCM push + inbox · errors: 400, 409 · tests: ambulance-fare, ambulance-request
- [x] **`ambreq_byphone`** — Patient found a car by phone (one they called during this request) → close as byPhone, push others
  - src: `server/lib/ambreq.js` → `actions.ambreq_byphone` · auth: user (patient) · port: **P5**
  - request: `id`, `ambulanceId` · response: `…`
  - collections: ambulanceCalls, ambulanceRequests, ambulances, users · side effects: FCM push + inbox · errors: — · tests: ambulance-fare, ambulance-request
- [x] **`ambreq_replace`** — Drop accepted driver (patient_found_other); with ambulanceId → byPhone close, else reopen without that driver
  - src: `server/lib/ambreq.js` → `actions.ambreq_replace` · auth: user (patient) · port: **P5**
  - request: `id`, `ambulanceId` · response: `…` or `{ok,request}`
  - collections: ambulanceCalls, ambulanceRequests, ambulances, users · side effects: FCM push + inbox · errors: 409 · tests: ambulance-fare, ambulance-request
- [x] **`ambulances`** — Approved ambulances in district: available first, nearest (gps), own upazila, rating; canRate per caller; busyCount, nearestId; kicks background "still available?" reminders
  - src: `server/lib/ambulance.js` → `actions.ambulances` · auth: guest · port: **P5**
  - request: `district`, `upazilaId`, `type`, `lat`, `lng`, `gps` · response: `{items,busyCount,busy,nearestId}`
  - collections: ambulanceCalls, ambulances, users · side effects: FCM push + inbox · errors: 400 · tests: ambulance-fare, ambulance-near, ambulance-request, demo-ambulance, demo-services, guest
- [x] **`ambulance_mine`** — Own ambulances (≤ MAX_PER_OWNER) with docs status
  - src: `server/lib/ambulance.js` → `actions.ambulance_mine` · auth: user · port: **P5**
  - request: — · response: `{items}`
  - collections: ambulances · side effects: — · errors: — · tests: ambulance-fare, ambulance-near, demo-ambulance, demo-services
- [x] **`ambulance_save`** — Add/edit ambulance (name, phone, vehicleNo, types, area, fare chart, base lat/lng, docs); re-verification on key changes; sanctions check; quota ambulance=10/day
  - src: `server/lib/ambulance.js` → `actions.ambulance_save` · auth: user · quota: ambulance (10/day) · port: **P5**
  - request: `name`, `phone`, `vehicleNo`, `types`, `district`, `upazilaId`, `termsAccepted`, `clearBase`, `lat`, `lng`, `fareChart`, `area`, `fare`, `id`, `docs` · response: `{ok,pending,...}` or `{ok,pending,id,...}`
  - collections: ambulances, sanctions, serverState · side effects: — · errors: 400, 404, 403 · tests: ambulance-fare, ambulance-near, demo-ambulance
- [x] **`ambulance_available`** — Toggle available (sets availableAt, remindAt = +11 h)
  - src: `server/lib/ambulance.js` → `actions.ambulance_available` · auth: user (owner) · port: **P5**
  - request: `id`, `available` · response: `{ok}`
  - collections: ambulances · side effects: — · errors: 400, 404 · tests: ambulance-near, ambulance-request, demo-ambulance, demo-services
- [x] **`ambulance_delete`** — Delete own ambulance (not when suspended or with open reports); deletes verify docs
  - src: `server/lib/ambulance.js` → `actions.ambulance_delete` · auth: user (owner) · port: **P5**
  - request: `id` · response: `{ok}`
  - collections: ambulances, modReports, verifyDocs · side effects: — · errors: 400, 403 · tests: — (none)
- [x] **`ambulance_called`** — Record a call (ambulanceCalls uid_id, 10-min dedupe), bump ambulanceStats; attach to active ambreq when reqId given; same world only
  - src: `server/lib/ambulance.js` → `actions.ambulance_called` · auth: user · port: **P5**
  - request: `id`, `reqId` · response: `{ok}`
  - collections: ambulanceCalls, ambulanceRequests, ambulances, users · side effects: FCM push + inbox · errors: — · tests: ambulance-fare, ambulance-near, ambulance-request, demo-ambulance, demo-services, guest
- [x] **`ambulance_rate`** — 1–5 stars within 7 days of a call; overcharged flag only for in-app fare trips; updates ratingSum/Count + recent comments
  - src: `server/lib/ambulance.js` → `actions.ambulance_rate` · auth: user · port: **P5**
  - request: `id`, `rating`, `overcharged`, `comment` · response: `{ok}`
  - collections: ambulanceCalls, ambulances · side effects: — · errors: 400, 404, 403 · tests: ambulance-fare, ambulance-request, demo-ambulance, demo-services
- [x] **`ambulance_stats`** — Owner stats per ambulance: calls per day, rating, recent comments, open report count
  - src: `server/lib/sellerstats.js` → `actions.ambulance_stats` · auth: user · port: **P5**
  - request: — · response: `{items}`
  - collections: ambulanceStats, ambulances, modReports · side effects: — · errors: — · tests: demo-ambulance, demo-services

## Home care (package `homecare`) — 4 actions

- [x] **`home_providers`** — Home-visit doctors/nurses near GPS or district/upazila; service/female filter; canRate (cached list)
  - src: `server/lib/homecare.js` → `actions.home_providers` · auth: guest · port: **P2**
  - request: `type`, `lat`, `lng`, `district`, `upazilaId`, `service`, `female` · response: `{items}` or `{items:[{...,photo}]}`
  - collections: badges, homeCalls, providers, users · side effects: — · errors: 400 · tests: demo-services
- [x] **`home_called`** — Record call to home-care provider (+homeStats)
  - src: `server/lib/homecare.js` → `actions.home_called` · auth: user · port: **P2**
  - request: `uid` · response: `{ok}`
  - collections: homeCalls, providers · side effects: — · errors: — · tests: demo-services
- [x] **`home_rate`** — Rate home-care provider within 30 days of call
  - src: `server/lib/homecare.js` → `actions.home_rate` · auth: user · port: **P2**
  - request: `uid`, `rating`, `comment` · response: `{ok}`
  - collections: homeCalls, providers · side effects: — · errors: 400, 404, 403 · tests: demo-services
- [x] **`home_stats`** — Provider own home-care stats (enabled/blocked, calls, rating, comments, reports)
  - src: `server/lib/homecare.js` → `actions.home_stats` · auth: user · port: **P2**
  - request: — · response: `{enabled,blocked,...,rating,ratingCount,comments:[...],reports}`
  - collections: homeStats, modReports, providers · side effects: — · errors: — · tests: demo-services

## Caregivers & family (package `care`) — 6 actions

- [x] **`care_list`** — My caregivers, people I care for, family members
  - src: `server/lib/careaccess.js` → `actions.care_list` · auth: user · port: **P2**
  - request: — · response: `{caretakers:[...],caringFor:[...],family}`
  - collections: careLinks, users · side effects: — · errors: — · tests: demo-health, rxflow
- [x] **`care_invite`** — Invite caregiver by email (≤ MAX_CARETAKERS); quota care=10/day; pushes invitee. Oct 2026: the same `{ok}` whether or not the email has an account (no enumeration) — an address without one waits in `care_email_invites` and becomes an invitation when that email signs in (verified); `care_list.caretakers` shows not-yet-accepted invitations without a name and with an opaque `caretakerUid` "ci_…" (pass it to `care_remove`)
  - src: `server/lib/careaccess.js` → `actions.care_invite` · auth: user · quota: care (10/day) · port: **P2**
  - request: `email` · response: `{ok}`
  - collections: careLinks, inbox, serverState, users · side effects: FCM push + inbox · errors: 400, 409 (no more 404) · tests: demo-health, labtests, rxflow
- [x] **`care_respond`** — Caregiver accepts/declines invitation; pushes patient
  - src: `server/lib/careaccess.js` → `actions.care_respond` · auth: user · port: **P2**
  - request: `patientUid`, `accept` · response: `{ok}`
  - collections: careLinks, inbox, users · side effects: FCM push + inbox · errors: 404 · tests: demo-health, rxflow
- [x] **`care_remove`** — Patient removes caregiver or caregiver leaves
  - src: `server/lib/careaccess.js` → `actions.care_remove` · auth: user · port: **P2**
  - request: `patientUid`, `caretakerUid` · response: `{ok}`
  - collections: careLinks · side effects: — · errors: 404 · tests: demo-health, labtests, rxflow
- [x] **`family_get`** — Family members (no own account) stored on profile
  - src: `server/lib/careaccess.js` → `actions.family_get` · auth: user caregiver · port: **P2**
  - request: — · response: `{family}`
  - collections: users · side effects: — · errors: — · tests: demo-health
- [x] **`family_set`** — Replace family list (≤ MAX_FAMILY, validated members)
  - src: `server/lib/careaccess.js` → `actions.family_set` · auth: user · port: **P2**
  - request: `family` · response: `{family}`
  - collections: users · side effects: — · errors: 400 · tests: demo-health, labtests, rxflow

## Notifications: inbox, devices, settings (package `notify`) — 6 actions

- [x] **`device_register`** — Attach FCM token to this account (≤5, removed from other accounts)
  - src: `server/lib/devices.js` → `actions.device_register` · auth: user · port: **P6**
  - request: `token` · response: `{ok}`
  - collections: users · side effects: — · errors: 400 · tests: — (none)
- [x] **`device_unregister`** — Remove this FCM token from account
  - src: `server/lib/devices.js` → `actions.device_unregister` · auth: user · port: **P6**
  - request: `token` · response: `{ok}`
  - collections: users · side effects: — · errors: — · tests: — (none)
- [x] **`inbox`** — Latest 50 inbox items (read flag from inboxSeenAt)
  - src: `server/lib/inbox.js` → `actions.inbox` · auth: user · port: **P6**
  - request: — · response: `{items:[{id,title,body,data,read,createdAt}]}`
  - collections: inbox, serverState · side effects: — · errors: — · tests: demo-health, demo-services
- [x] **`inbox_read`** — Mark all read (inboxSeenAt = now)
  - src: `server/lib/inbox.js` → `actions.inbox_read` · auth: user · port: **P6**
  - request: — · response: `{ok}`
  - collections: serverState · side effects: — · errors: — · tests: demo-health
- [x] **`notify_prefs_get`** — *Spring only (Oct 2026), no Node action:* my notification settings per category
  - src: `notify/NotifyPrefs.kt` · auth: user, own settings only (a caregiver's `asUid` → 403)
  - request: — · response: `{ok, all, cats: {blood_nearby, blood_emergency, my_request, appointments, rx, ambulance, community, business, calls, messages}}` (all Boolean; no row = all `true`; `calls` = in-app calls, `messages` = in-app messages, Oct 2026)
  - tables: notify_prefs, user_profiles (`blood_nearby` **is** `user_profiles.notify`) · errors: 401, 403 · tests: NotifyPrefsIT
- [x] **`notify_prefs_set`** — *Spring only (Oct 2026):* change them; merges (only the given values change), answers like `notify_prefs_get`
  - request: `all?: Boolean`, `cats?: {<id>: Boolean}` (only the 9 ids above; `account` or anything else, or a non-Boolean → 400)
  - `cats.blood_nearby` writes `user_profiles.notify` (the same value `profile_update {notify}` writes — one source, nothing to sync;
    `maxPushPerDay`, `notifyGroups`, `maxKm` stay in the profile as before)
  - tables: notify_prefs, user_profiles · errors: 400, 401, 403 · tests: NotifyPrefsIT

### Notification categories (enforced in `PushService.send`, the single choke point of every push)

Every push gets one category (`notify/NotifyCats.kt`; NotifyCatsTest fails when a `"type" to "…"` in the server code has
none). The category is added to the push data as **`cat`** — in the FCM data payload (the apps use it for per-category
Android channels) and in the bell-list item. If the person's `all` is off, or the category is off, **no FCM message is sent;
the inbox item is always written**. `account` is always sent (also with `all` off) — and it is the **only** category sent to a deactivated account
(`account_deactivate`, Oct 2026). Unknown types count as `account`.
Donor picking also skips switched-off donors (`GeoQuery.DONOR_SELECT`): nearby requests need `notify` **and** `all`;
emergency waves need `blood_emergency` **and** `all` (no longer the nearby switch — V607 sets `blood_emergency` off for
everyone who had `notify` off, so nobody starts getting appeals they had silenced).

| push `type` (data) | sent to / when | category |
|---|---|---|
| `request` + `cat=blood_nearby` | nearby matching donors: new request, "notify again" (RequestFlowService) | `blood_nearby` |
| `eligible` | "you can donate again" (daily; already only with `notify` on) | `blood_nearby` |
| `emergency` | Lifolo team appeal (waves) and "appeal ended" to donors who said coming | `blood_emergency` |
| `response` | requester: a donor accepted / a confirmed donor cancelled / left; request edits relevant to the requester | `my_request` |
| `request` (no `cat`) | donor of a request they answered: confirmed, removed, restored, "enough donors", "may be needed again", request edited / closed / reopened / no longer needed | `my_request` |
| `request_removed` | donor: the request they answered was deleted | `my_request` |
| `appointment` | patient: confirmed/declined/cancelled, "2 before you", late reply, no reply, appointment today; doctor: patient cancelled, "will be late", today's count; either side: cancelled because the other account was deleted | `appointments` |
| `appointment` + `cat=business` | doctor: new appointment request, "requests waiting" reminder, chambers removed by the team | `business` |
| `rx` | patient: new prescription, refill reminder (2 days), follow-up, pharmacy order status, lab order status / home visit / report, doctor checked the report | `rx` |
| `med_find_mine` | finder: a shop has the medicine | `rx` |
| `amb_req_mine` | patient: fare offer, accepted / coming, arrived, done, cancelled, timed out | `ambulance` |
| `services` + `sub=amb_req` | driver: later updates on a call (patient took another car, cancelled, got a car, quote accepted) | `ambulance` |
| `services` + `sub=amb_req` + `cat=business` | driver: a new ambulance call (the wave) | `business` |
| `services` | owner: listing approved / rejected / suspended / docs requested (ambulance, pharmacy, blood bank, lab), "still available?" | `business` |
| `badge` | professional badge approved / rejected / docs requested | `business` |
| `rx_pharmacy`, `rx_lab`, `med_find` | shop / lab: new order, order changed or withdrawn, medicine-finder request | `business` |
| `rx_review` | doctor: a report waits for review, report-review payment to check | `business` |
| `answer` | asker: a verified professional answered | `community` |
| `care` (no `asUid`) | caregiver invitation, invitation accepted | `account` |
| `care` + `asUid` | caregiver's copy of the patient's news (`pushPatient`) | the category of the patient's news (`cat` set by PushService) — follows the **caregiver's** own settings |
| `support` | Lifolo team reply | `account` |
| `correction` | last-donation-date correction result | `account` |
| `hospital` | the hospital I asked for was added | `account` |

Decision (owner's question): donor-side updates on a request the donor already answered ("you are confirmed", "no longer
needed", …) are `my_request`, not `blood_nearby`. `blood_nearby` = the old switch, which only ever stopped *new* nearby
requests; folding commitments into it would silently cut confirmed donors off. `my_request` therefore means "requests I'm
part of" — mine as requester, or ones I agreed to donate for.

Bug check ("turning notifications off doesn't stop them"): the server path was right — `profile.notify = false` removes the
donor from `pickDonors` (and from emergency waves), so no nearby-request push went out (BloodFlowIT, NotifyPrefsIT). The
complaint came from everything else: that switch only ever covered nearby requests, while responses, emergencies'
"ended", appointments, prescriptions, ambulance, business etc. always pushed. Those are now covered by the categories.

## In-app calls (package `calls`, Spring only, Oct 2026) — 17 actions

Full contract: [CALLS.md](CALLS.md) (security review: `docs/test-plan/CALLS-SECURITY-REVIEW.md`). Logged in, own account
only (no caregiver `asUid`). LiveKit env missing → 503 `calls_unavailable`. Contact rules shared with messages:
`contact/ContactPolicy.kt`, `contact/BlockService` (calls/messages blocks).
- [x] **`call_config`** — `{ok, enabled, url?}`
- [x] **`contact_can`** — `{items:[{ctx, refId, peerUid?}]}` → `{items:[{ctx, refId, peerUid, canCall, canVideo, canMessage, blocked?}]}`
- [x] **`call_start`** — `{ctx, refId, peerUid?, video?}` → `{ok, callId, room, url, token, video, peer}`; 403 `call_not_allowed` / `call_blocked`, 409 `call_off` / `call_busy`, 429 `call_rate` (caller 10/10 min, pair 3 unanswered/h, 2 declines → 30 min, callee 20 rings/h); push `call_incoming` (TTL 45 s, no inbox) + SSE
- [x] **`call_accept`** — `{callId, video?}` → `{ok, callId, room, url, token, video, peer}`; 409 `call_gone`
- [x] **`call_decline`**, **`call_cancel`**, **`call_end`** — `{callId}` → `{ok, state}`; push/SSE `call_state`; room deleted (RoomService)
- [x] **`call_status`** — `{callId}` → state, peer, times, and a fresh `url/room/token` while accepted; the 30 s heartbeat
- [x] **`call_history`** — `{before?}` → `{ok, items:[…dir…], next?}`
- [x] **`call_upgrade`**, **`call_upgrade_accept`**, **`call_upgrade_decline`** — audio → video with the other side's yes
- [x] **`call_block`** `{peerUid, callId?}`, **`call_unblock`** `{peerUid}`, **`call_blocks`** — blocks calls and messages
- [x] **`call_report`** — `{callId, reason, note?}` → `call_reports`
- [x] **`appt_join`** — `{appointmentId}` → `{ok, callId, room, url, token, video:true}`; one live room per appointment; push `appt_call_ready`
- [x] **`POST /api/livekit/webhook`** (HTTP, from the call server; LiveKit-signed JWT + body sha256) — `room_finished` / `participant_left` end stuck calls; `appt_join` 409 `room_full` (2 per room)
  - tables: call_sessions, call_blocks, call_reports (V622), notify_prefs.calls · jobs: `call-timeouts` (JobRunr, 15 s: ringing > 45 s → missed + collapsed `call_missed`; live calls re-checked — relation, block, account, 90 s without heartbeats → ended), daily step 15 `callsDeleted` (180 days) · events: `AccountCutOffEvent` (deactivate / admin disable) ends live calls · tests: CallsIT, CallsAuthzIT

## In-app messages (package `chat`, Spring only, Oct 2026) — 10 actions

Full contract: [CHAT.md](CHAT.md). Logged in, own account only (no caregiver `asUid`). Who may write to whom = the calls' `ContactPolicy`.
- [x] **`chat_open`** — `{ctx, refId, peerUid?}` or `{threadId}` → `{ok, threadId, ctx, refId, peer, canSend, blocked?, closed?, messages, before}`; 403 `chat_not_allowed` / `call_blocked`
- [x] **`chat_send`** — `{threadId, clientId, text? | photo? | location?}` → `{ok, message}`; idempotent per clientId; 409 `chat_closed`, 429 `chat_rate` (30/min, 20 photos/day, 20 before a reply), 400 `bad_photo`; push `chat_message` (collapsed per thread, no text) + SSE
- [x] **`chat_messages`** — `{threadId, before?}` → `{ok, threadId, ctx, refId, peer, canSend, messages, before}` (50, opaque cursor)
- [x] **`chat_threads`** — `{cursor?}` → `{ok, items:[{threadId, ctx, refId, peer, last, unread, canSend}], cursor}`
- [x] **`chat_read`** — `{threadId, upTo}` → `{ok, read}`; SSE + silent push `chat_read {threadId, upTo, by}`
- [x] **`chat_unsend`** — `{messageId}` → `{ok, message}` (own, ≤ 10 min, 409 `chat_unsend_window`)
- [x] **`chat_report`** — `{messageId, reason, note?}` → `{ok}` (content + photo copied into chat_reports)
- [x] **`chat_unread`** — → `{ok, total}`
- [x] **`admin_chat_reports`** — admin: `{status?, before?}` → `{ok, items, next?}`; **`admin_chat_report_resolve`** — `{id, status: resolved|dismissed}`
  - GET `/api/chat/photo/{messageId}?e=&s=` — the photo for a signed URL (1 h, no login)
  - tables: chat_threads, chat_messages, chat_reports (V660), notify_prefs.messages · daily step 16 `chatDeleted` (close ended threads; delete 90 days after closing) · tests: ChatIT

## Admin, moderation, verification, support (package `admin`) — 47 actions

- [x] **`badge_apply`** — Apply for professional badge (doctor/nurse/nutritionist/trainer/expert) with regNo, workplace, doc photos; on approved badge → pendingChange correction; quota badge=3/day
  - src: `server/api/community.js` → `actions.badge_apply` · auth: user · quota: badge (3/day) · port: **P2**
  - request: `type`, `regNo`, `workplace`, `docs` · response: `{ok,correction}` or `{ok}`
  - collections: badges, serverState, users · side effects: — · errors: 400, 409 · tests: demo-health
- [x] **`admin_badges`** — Badge applications by status (+ pending corrections and verify panel info when panel:true)
  - src: `server/api/community.js` → `actions.admin_badges` · auth: user admin web-panel · port: **P2**
  - request: `status`, `panel` · response: `{items}`
  - collections: badges · side effects: — · errors: 403 · tests: demo-health, demo-rx, demo-services
- [x] **`admin_badge_resolve`** — Approve/reject badge or correction (verify checks required); delists provider + cancels future appointments on reject; restores listing on re-approve; pushes user
  - src: `server/api/community.js` → `actions.admin_badge_resolve` · auth: user admin web-panel · port: **P2**
  - request: `uid`, `correction`, `approve`, `checks`, `reason` · response: `{ok,unchanged}` or `{ok}`
  - collections: appointments, badges, inbox, providers, users · side effects: FCM push + inbox, admin_log row · errors: 403, 400, 404 · tests: demo-health, demo-rx, demo-services
- [x] **`admin_list`** — List owners (env) and extra admins (config/admins)
  - src: `server/api/community.js` → `actions.admin_list` · auth: owner (ADMIN_EMAILS, verified email) web-panel · port: **P6**
  - request: — · response: `{owners,items:[{email}]}`
  - collections: config · side effects: — · errors: 403 · tests: — (none)
- [x] **`admin_add`** — Add admin email (≤20); clears admin cache
  - src: `server/api/community.js` → `actions.admin_add` · auth: owner web-panel · port: **P6**
  - request: `email` · response: `{ok}`
  - collections: config · side effects: admin_log row · errors: 403, 400 · tests: — (none)
- [x] **`admin_remove`** — Remove admin email; clears admin cache
  - src: `server/api/community.js` → `actions.admin_remove` · auth: owner web-panel · port: **P6**
  - request: `email` · response: `{ok}`
  - collections: config · side effects: admin_log row · errors: 403, 400 · tests: — (none)
- [x] **`admin_overview`** — Web panel landing: badges, pharmacies, ambulances, blood banks, labs, hospital requests, reports, corrections, open tickets, admins (each part fails independently)
  - src: `server/api/community.js` → `actions.admin_overview` · auth: user admin web-panel · port: **P6**
  - request: `status`, `panel` · response: `{me:{email,isOwner},badges,pharmacies,ambulances,bloodBanks,labs,hospitalRequests,reports,corrections,tickets,admins,at}`
  - collections: badges, correctionRequests · side effects: — · errors: 403 · tests: demo-health, demo-services, guest
- [x] **`admin_stats`** — Analytics (users, requests, listings…), cached 30 min in serverState/_adminStats; fresh:true recomputes (count() queries)
  - src: `server/lib/adminweb.js` → `actions.admin_stats` · auth: user admin web-panel · port: **P6**
  - request: `fresh` · response: `…`
  - collections: ambulances, badges, bloodBanks, pharmacies, providers, questions, requests, serverState, tickets, tips, users · side effects: — · errors: — · tests: — (none)
- [x] **`admin_user_find`** — Find users by email/phone/uid (Auth lookup + users/pharmacies/ambulances phone variants)
  - src: `server/lib/adminweb.js` → `actions.admin_user_find` · auth: user admin web-panel · port: **P6**
  - request: `q` · response: `{items}`
  - collections: ambulances, pharmacies, users · side effects: — · errors: 400 · tests: — (none)
- [x] **`admin_user`** — Full user view: profile, auth, badge, listings, sanctions, counts
  - src: `server/lib/adminweb.js` → `actions.admin_user` · auth: user admin web-panel · port: **P6**
  - request: `uid` · response: `{user}`
  - collections: ambulances, appointments, badges, bloodBanks, homeStats, modReports, pharmacies, providers, requests, serverState, tickets, users · side effects: — · errors: 400, 404 · tests: — (none)
- [x] **`admin_user_action`** — Disable/enable account (Auth + revoke + disabledReason), lift/impose sanctions, home-care block; pushes user
  - src: `server/lib/adminweb.js` → `actions.admin_user_action` · auth: admin (owner for actions on admins/owner) web-panel · port: **P6**
  - request: `uid`, `op`, `reason` · response: `{ok}`
  - collections: appointments, inbox, providers, sanctions, serverState, users · side effects: FCM push + inbox, Firebase Auth update, revoke refresh tokens, admin_log row · errors: 400, 403, 404 · tests: — (none)
- [x] **`admin_log`** — Admin action history, 100/page, cursor `before` (at ms)
  - src: `server/lib/adminweb.js` → `actions.admin_log` · auth: user admin web-panel · port: **P6**
  - request: `before` · response: `{items:[{id,...}]}`
  - collections: adminLog · side effects: — · errors: — · tests: — (none)
- [x] **`admin_ambulances`** — Ambulances by status (pending default) with overchargeCount, docs
  - src: `server/lib/ambulance.js` → `actions.admin_ambulances` · auth: user admin web-panel · port: **P5**
  - request: `status` · response: `{items}`
  - collections: ambulances · side effects: — · errors: — · tests: ambulance-fare, demo-ambulance, demo-services
- [x] **`admin_ambulance_resolve`** — listingreview.resolveListing: approve/reject/suspend/reinstate with checks + reason; sanctions ban/unban; push owner
  - src: `server/lib/ambulance.js` → `actions.admin_ambulance_resolve` · auth: user admin web-panel · port: **P5**
  - request: `id` · response: `…`
  - collections: inbox, sanctions, users · side effects: FCM push + inbox, admin_log row · errors: — · tests: demo-ambulance, demo-services
- [x] **`admin_bloodbanks`** — Blood banks by status
  - src: `server/lib/bloodbank.js` → `actions.admin_bloodbanks` · auth: user admin web-panel · port: **P1**
  - request: `status` · response: `{items}`
  - collections: bloodBanks · side effects: — · errors: — · tests: demo-blood
- [x] **`admin_bloodbank_resolve`** — resolveListing for blood banks
  - src: `server/lib/bloodbank.js` → `actions.admin_bloodbank_resolve` · auth: user admin web-panel · port: **P1**
  - request: `id` · response: `…`
  - collections: inbox, sanctions, users · side effects: FCM push + inbox, admin_log row · errors: — · tests: demo-blood
- [x] **`admin_districts`** — District names (bn) sorted, for panel forms
  - src: `server/lib/bloodbank.js` → `actions.admin_districts` · auth: user admin web-panel · port: **P1**
  - request: — · response: `{items}`
  - collections: — · side effects: — · errors: — · tests: — (none)
- [x] **`admin_bloodbank_add`** — Admin adds an ownerless approved blood bank
  - src: `server/lib/bloodbank.js` → `actions.admin_bloodbank_add` · auth: user admin web-panel · port: **P1**
  - request: `name`, `kind`, `phone`, `district`, `upazilaId`, `address`, `licenseNo`, `open24`, `openAt`, `closeAt`, `services`, `closedDays` · response: `{ok,id}`
  - collections: bloodBanks · side effects: admin_log row · errors: — · tests: — (none)
- [x] **`admin_bloodbank_edit`** — Admin edits any blood bank; pushes owner if any
  - src: `server/lib/bloodbank.js` → `actions.admin_bloodbank_edit` · auth: user admin web-panel · port: **P1**
  - request: `id`, `upazilaId`, `name`, `kind`, `phone`, `district`, `address`, `licenseNo`, `open24`, `openAt`, `closeAt`, `services`, `closedDays` · response: `{ok}`
  - collections: bloodBanks, inbox, users · side effects: FCM push + inbox, admin_log row · errors: 400, 404 · tests: — (none)
- [x] **`admin_bloodbank_delete`** — Admin deletes blood bank (+stats, docs, reports); pushes owner with reason
  - src: `server/lib/bloodbank.js` → `actions.admin_bloodbank_delete` · auth: user admin web-panel · port: **P1**
  - request: `id`, `reason` · response: `{ok}`
  - collections: bloodBankStats, bloodBanks, inbox, modReports, users, verifyDocs · side effects: FCM push + inbox, admin_log row · errors: 400 · tests: — (none)
- [x] **`admin_catalog_set`** — Save one pick-list (order, names, hidden, new codes ≤80)
  - src: `server/lib/catalog.js` → `actions.admin_catalog_set` · auth: user admin web-panel · port: **P6**
  - request: `kind`, `type`, `items` · response: `{items}`
  - collections: config · side effects: admin_log row · errors: — · tests: — (none)
- [x] **`admin_hospitals`** — Hospitals of district for emergency form
  - src: `server/lib/emergency.js` → `actions.admin_hospitals` · auth: user admin web-panel · port: **P2**
  - request: `district` · response: `{items:[{id,name,nameEn}]}`
  - collections: hospitals · side effects: — · errors: 400 · tests: — (none)
- [x] **`admin_emergency_create`** — Create emergency call (groups/need, radius, hours, hospital, contact) and send first wave of pushes
  - src: `server/lib/emergency.js` → `actions.admin_emergency_create` · auth: user admin web-panel · port: **P1**
  - request: `confirmed`, `district`, `hospitalName`, `hospitalId`, `groups`, `radiusKm`, `hours`, `contactPhone`, `lat`, `lng`, `note` · response: `{ok,id,...}`
  - collections: emergencies, hospitals, inbox, users, …/donors · side effects: emergency push wave, FCM push + inbox, admin_log row · errors: 400 · tests: — (none)
- [x] **`admin_emergency_more`** — Send next wave to not-yet-notified eligible donors
  - src: `server/lib/emergency.js` → `actions.admin_emergency_more` · auth: user admin web-panel · port: **P1**
  - request: `id` · response: `{ok,...}`
  - collections: emergencies, inbox, users, …/donors · side effects: emergency push wave, FCM push + inbox, admin_log row · errors: 400 · tests: — (none)
- [x] **`admin_emergency_group`** — Close/reopen one blood group
  - src: `server/lib/emergency.js` → `actions.admin_emergency_group` · auth: user admin web-panel · port: **P1**
  - request: `id`, `group`, `closed` · response: `{ok}`
  - collections: emergencies · side effects: admin_log row · errors: 400, 404 · tests: — (none)
- [x] **`admin_emergency_close`** — End emergency; push coming donors
  - src: `server/lib/emergency.js` → `actions.admin_emergency_close` · auth: user admin web-panel · port: **P1**
  - request: `id` · response: `{ok,unchanged}` or `{ok}`
  - collections: emergencies, emergencies/donors, inbox, users · side effects: FCM push + inbox, admin_log row · errors: 400, 404 · tests: — (none)
- [x] **`admin_emergencies`** — Recent 30 emergencies with counts
  - src: `server/lib/emergency.js` → `actions.admin_emergencies` · auth: user admin web-panel · port: **P1**
  - request: — · response: `{items:[...]}`
  - collections: emergencies · side effects: — · errors: — · tests: demo-blood
- [x] **`admin_hospital_requests`** — Pending hospital requests with possible duplicates
  - src: `server/lib/hospitals.js` → `actions.admin_hospital_requests` · auth: user admin web-panel · port: **P2**
  - request: — · response: `{items}`
  - collections: hospitalRequests, hospitals · side effects: — · errors: — · tests: demo-blood
- [x] **`admin_hospital_resolve`** — approve / reject / merge into existing; pushes requesters
  - src: `server/lib/hospitals.js` → `actions.admin_hospital_resolve` · auth: user admin web-panel · port: **P2**
  - request: `id`, `op`, `targetId` · response: `{ok,hospitalId}` or `{ok}`
  - collections: hospitalRequests, hospitals, inbox, users · side effects: FCM push + inbox, admin_log row · errors: 400, 404 · tests: demo-blood
- [x] **`admin_labtests_set`** — Add/edit one lab test template (hide, never delete)
  - src: `server/lib/labtests.js` → `actions.admin_labtests_set` · auth: user admin web-panel · port: **P3**
  - request: `test` · response: `{test,tests}`
  - collections: config · side effects: admin_log row · errors: 403, 400, 409 · tests: rxflow
- [x] **`report`** — Report content/listing (question, answer, tip, provider, homecare, ambulance, pharmacy, bloodbank …) once per target; quota report=20/day; may auto-suspend (tellSuspendedByReport)
  - src: `server/lib/moderation.js` → `report` · auth: user · quota: report (20/day) · port: **P6**
  - request: `kind`, `id`, `answerId`, `reason` · response: `{ok}`
  - collections: medFinds, modReports, serverState · side effects: — · errors: 400, 404, 403 · tests: demo-ambulance, demo-blood, demo-health, demo-services, guest
- [x] **`admin_reports`** — Open reports grouped by target (provider reports only with panel:true)
  - src: `server/lib/moderation.js` → `admin_reports` · auth: user admin web-panel · port: **P6**
  - request: `panel` · response: `{items}`
  - collections: modReports · side effects: — · errors: 403 · tests: demo-blood, demo-health, demo-services
- [x] **`admin_report_resolve`** — Dismiss or remove target (delete post / suspend listing / block home care) + sanctions; pushes owner
  - src: `server/lib/moderation.js` → `admin_report_resolve` · auth: user admin web-panel · port: **P6**
  - request: `targetPath`, `reportedUid`, `kind`, `remove`, `reason` · response: `{ok}`
  - collections: appointments, inbox, modReports, providers, sanctions, users · side effects: recursive delete, FCM push + inbox, admin_log row · errors: 403, 400 · tests: demo-health, demo-services
- [x] **`admin_pharmacies`** — Pharmacies by status
  - src: `server/lib/pharmacy.js` → `actions.admin_pharmacies` · auth: user admin web-panel · port: **P4**
  - request: `status` · response: `{items}`
  - collections: pharmacies · side effects: — · errors: — · tests: demo-pharmacy, demo-services
- [x] **`admin_pharmacy_resolve`** — resolveListing for pharmacies
  - src: `server/lib/pharmacy.js` → `actions.admin_pharmacy_resolve` · auth: user admin web-panel · port: **P4**
  - request: `id` · response: `…`
  - collections: inbox, sanctions, users · side effects: FCM push + inbox, admin_log row · errors: — · tests: demo-pharmacy, demo-services
- [x] **`admin_labs`** — Labs by status
  - src: `server/lib/rxnet.js` → `actions.admin_labs` · auth: user admin web-panel · port: **P3**
  - request: `status` · response: `{items}`
  - collections: labs · side effects: — · errors: 403 · tests: demo-lab, demo-services
- [x] **`admin_lab_resolve`** — resolveListing for labs
  - src: `server/lib/rxnet.js` → `actions.admin_lab_resolve` · auth: user admin web-panel · port: **P3**
  - request: `id` · response: `…`
  - collections: inbox, sanctions, users · side effects: FCM push + inbox, admin_log row · errors: 403 · tests: demo-lab, demo-services
- [x] **`ticket_create`** — Open support/appeal ticket (category, text); quota ticket=5/day
  - src: `server/lib/support.js` → `actions.ticket_create` · auth: user · quota: ticket (5/day) · port: **P6**
  - request: `category`, `text` · response: `{ok,id}`
  - collections: serverState, tickets, users · side effects: — · errors: 400, 429 · tests: demo-health
- [x] **`tickets_mine`** — My last 20 tickets
  - src: `server/lib/support.js` → `actions.tickets_mine` · auth: user · port: **P6**
  - request: — · response: `{items}`
  - collections: tickets · side effects: — · errors: — · tests: demo-health
- [x] **`ticket_reply`** — Add message (reopens closed ticket)
  - src: `server/lib/support.js` → `actions.ticket_reply` · auth: user (ticket owner) · port: **P6**
  - request: `id`, `text` · response: `{ok}`
  - collections: tickets · side effects: — · errors: 400, 404 · tests: — (none)
- [x] **`admin_tickets`** — Tickets by status (open default)
  - src: `server/lib/support.js` → `actions.admin_tickets` · auth: user admin web-panel · port: **P6**
  - request: `status` · response: `{items}`
  - collections: tickets · side effects: — · errors: — · tests: demo-health
- [x] **`admin_ticket`** — One ticket with user info
  - src: `server/lib/support.js` → `actions.admin_ticket` · auth: user admin web-panel · port: **P6**
  - request: `id` · response: `{item:{...,uid,email,name,lastFrom}}`
  - collections: tickets · side effects: — · errors: 400, 404 · tests: — (none)
- [x] **`admin_ticket_reply`** — Reply and/or close; pushes user
  - src: `server/lib/support.js` → `actions.admin_ticket_reply` · auth: user admin web-panel · port: **P6**
  - request: `id`, `close`, `text` · response: `{ok}`
  - collections: inbox, tickets, users · side effects: FCM push + inbox, admin_log row · errors: 400, 404 · tests: demo-health
- [x] **`admin_user_section`** — Read-only section of a user’s activity (blood, appointments, rx, orders, …)
  - src: `server/lib/useractivity.js` → `actions.admin_user_section` · auth: user admin web-panel · port: **P6**
  - request: `uid`, `section` · response: `{section,...}`
  - collections: — · side effects: admin_log row · errors: 403, 400 · tests: demo-health
- [x] **`verify_docs_upload`** — Owner uploads requested document photos (base64) for badge/pharmacy/ambulance/bloodbank/lab; quota docs=10/day
  - src: `server/lib/verify.js` → `actions.verify_docs_upload` · auth: user (listing owner) · quota: docs (10/day) · port: **P6**
  - request: `kind`, `id`, `docs` · response: `{ok,saved,...}`
  - collections: serverState · side effects: — · errors: 404 · tests: — (none)
- [x] **`admin_verify_docs`** — Document photos for verification panel
  - src: `server/lib/verify.js` → `actions.admin_verify_docs` · auth: user admin web-panel · port: **P6**
  - request: `kind`, `id` · response: `{...,photos:[{type,label,data,at}]}`
  - collections: — · side effects: admin_log row · errors: 403 · tests: demo-blood, demo-health, demo-services
- [x] **`admin_request_docs`** — Ask owner for document photos (note); pushes owner
  - src: `server/lib/verify.js` → `actions.admin_request_docs` · auth: user admin web-panel · port: **P6**
  - request: `kind`, `id`, `note` · response: `{ok}`
  - collections: inbox, users · side effects: FCM push + inbox, admin_log row · errors: 403, 400 · tests: — (none)

## Demo world (package `demo`) — 3 actions

- [x] **`admin_demo_seed`** — Seed demo world step by step (step 0 wipes + creates Auth accounts; steps 1..5 parts); returns {next,label,steps}
  - src: `server/lib/demo/index.js` → `actions.admin_demo_seed` · auth: owner web-panel · port: **P6**
  - request: `step` · response: `…` or `{...,steps}`
  - collections: labOrders, prescriptions, rxOrders, rxPatients, rxReviews, serverState · side effects: Firebase Auth create, Firebase Auth update, Firebase Auth delete, admin_log row · errors: 403, 400 · tests: ambulance-near, demo-ambulance, demo-blood, demo-health, demo-lab, demo-night, demo-pharmacy, demo-rx, demo-services, demo, doctor-filters, e2efixes, queue, shopbooks, worksegs
- [x] **`admin_demo_wipe`** — Wipe all demo accounts (real account-deletion path) and manifest docs; {more} when time budget exceeded
  - src: `server/lib/demo/index.js` → `actions.admin_demo_wipe` · auth: owner web-panel · port: **P6**
  - request: — · response: `…`
  - collections: labOrders, prescriptions, rxOrders, rxPatients, rxReviews, serverState · side effects: Firebase Auth delete, admin_log row · errors: 403 · tests: ambulance-near, demo-ambulance, demo-blood, demo-health, demo-lab, demo-pharmacy, demo-rx, demo-services, demo, queue, shopbooks
- [x] **`admin_demo_status`** — Demo manifest status and role emails
  - src: `server/lib/demo/index.js` → `actions.admin_demo_status` · auth: owner web-panel · port: **P6**
  - request: — · response: `{active,roles}` or `{active,partial,at,docs,roles}`
  - collections: serverState · side effects: — · errors: 403 · tests: demo

## Other HTTP endpoints (outside /api/community)

All use the Node `handler` envelope (POST only → 405 `{error:"Method not allowed"}`, JSON, `{error, code?}`) unless noted.
`verifyUser` endpoints answer 401 with the Bangla "লগইন দরকার" (translated for `lang:"en"`).

- [x] **`ANY /api/health`** — `server/api/health.js` · guest · reads one serverState doc → `{ok:true, firebase:true}` / 500 `{ok:false}`. Phase 1: checks PostgreSQL (`{ok, firebase, db}`).
- [x] **`POST /api/auth/send-code`** — `server/api/auth/send-code.js` · guest · request `email`, `lang` → `{ok, rid}` ·
  emailCodes, serverState (`ip_*`, `_emailGlobalExisting|New`) · email (Gmail → SES) · errors 400/429/503/502 ·
  caps: 50/IP/day, 300 existing + 120 new emails/day globally, 15/email/day, 1/min, ≤3 live codes, 10 min TTL · tests: email (unit).
- [x] **`POST /api/auth/verify-code`** — `server/api/auth/verify-code.js` · guest · request `email`, `code`, `rids[]` (≤5 × 32-hex), `web`,
  `demoRoles`, `demo` → `{ok, token, restored, account}` (app) · `{ok}` + `Set-Cookie: __Host-lf_admin` (web, admins only) ·
  `{active, roles[{key,name,role}]}` (demoRoles) · `{ok, token, restored, demo:true, account}` (demo) · emailCodes, serverState, Auth
  (getUserByEmail/createUser/updateUser/revokeRefreshTokens/createCustomToken), deletion cancel (ambulances, bloodBanks,
  pharmacies, labs, providers) — Oct 2026: a cancelled deletion is emailed ("মোছা বাতিল হয়েছে"), and `account` is the state
  object of "Users & account" (`active` or `deactivated`; logging in does **not** reactivate — the app shows "turn on again")
  · errors 400/403/404/429 · tests: demo, deletion, websession (unit), DeactivationIT.
- [x] **`POST /api/notify-request`** — `server/api/notify-request.js` · user (requester) · request `requestId`, `again` →
  `{ok, sent}` / `{ok, already, sent}` · requests, requests/{id}/meta/notified, responses, serverState (requestDay/Count,
  pushDay/Count), users (donorGeo/geohash range scans) · FCM push + inbox to matched donors (radius steps RADII_KM until
  ENOUGH_DONORS, per-donor daily push limit, same world) · lock `notifyingUntil` 60 s · again: ≤3 rounds, 6 h apart ·
  errors 400/403/404/409/429 · tests: — (none; covered by e2e).
- [x] **`POST /api/respond`** — `server/api/respond.js` · user (donor) · request `requestId` → `{ok}` / `{ok, already}` ·
  requests (acceptedCount), responses, serverState (respondDay/Count, respondedTo), users (commitments, respondedTo) ·
  push to requester · checks: same world, profile complete, group compatible, eligible (18–60 y, ≥50 kg, 120 days) ·
  errors 400/403/404/429 · tests: demo-blood.
- [x] **`POST /api/close-request`** — `server/api/close-request.js` · user (requester) · request `requestId`, `donatedUids[]` (≤20),
  `bagsReceived` → `{ok, marked, closed, notNeeded}` / `{ok, already}` · requests, responses, users (lastDonationMillis),
  requests/{id}/meta/close (undo info for request_reopen) · pushes: ended notice to accepted donors, thanks to marked donors,
  releaseOthers · tests: demo-blood.
- [x] **`POST /api/edit-request`** — `server/api/edit-request.js` · user (requester) · request `requestId`, `bags`, `hospital`,
  `neededByMillis`, `neededAtMillis`, `travelFare`, `urgent`, `contactPhone`, `note` → `{ok, notified}` · requests (editCount ≤10),
  requests/{id}/private/contact, responses · push to accepted donors · errors 400/403/404/429 · tests: demo-blood.
- [x] **`POST /api/admin/corrections`** — `server/api/admin/corrections.js` · admin (Bearer or web cookie) → `{ok, items}` ·
  correctionRequests (pending) · tests: —.
- [x] **`POST /api/admin/resolve`** — `server/api/admin/resolve.js` · admin · request `id`, `approve` → `{ok, uid}` / `{ok, already, uid}` ·
  correctionRequests, users (lastDonationMillis) · push to the user, adminLog `correction_resolve` · tests: —.
- [x] **`GET /api/share`** — `server/api/share.js` · guest · HTML/JSON/SVG, not the JSON envelope:
  `?id=` request share page (no name/phone/note) · `?rx=CODE` prescription verify page (prescriptions.verify) ·
  `?shop=CODE` pharmacy page · `?rxqr=CODE` / `?shopqr=CODE` QR SVG (cached 1 day) · `?assetlinks=1` Android App Links JSON.
  Rewrites (vercel.json): **`/r/:id`**, **`/rx/:code`**, **`/shop/:code`**, **`/.well-known/assetlinks.json`** → nginx/Spring routes in phase 2.
- [x] **`GET /api/cron/daily`** — `server/api/cron/daily.js` · `Authorization: Bearer $CRON_SECRET` (401 "অনুমতি নেই") ·
  GET only · response `{ok, privacy, closed, eligibleReminders, care, accountsDeleted, inboxDeleted, emergenciesClosed, geo,
  phoneLimitsDeleted, medFindsDeleted, rxReminders, refill, ambulanceReminders, ambulanceRequests, stockPrices}`.
  Phase 1: endpoint + JobRunr recurring job (03:00 UTC) + `DailyTask` runner; the steps are ported in phase 2.
- [x] **`GET /api/cron/storage`**, **`POST /api/cron/storage/migrate?limit=200`** — new (no Node equivalent) · Bearer
  `$CRON_SECRET` (401 "অনুমতি নেই") · R2 status `{configured, remaining{profilePhoto, profileThumb, verifyDocs,
  labReportPhotos}, stored, trash}`; migrate moves up to `limit` old base64 rows to R2 (+ `{ok, migrated, failed, changed}`;
  400 without R2, 409 while running); `GET /api/cron/storage/probe` → one row per source that still has base64 `{pk, sha256}`,
  with `?source=…&pk=…` that row read like the API reads it `{from: db|r2|none, sha256}` (400 unknown source, 404 no row)
  — [deploy/R2.md](../../deploy/R2.md). The app/web API is unchanged: photos are still
  sent and returned as the same base64 text.

Static files served by Vercel today (phase 2: nginx): `/admin` → `server/public/admin/index.html` (admin panel, strict CSP),
`/w/*` + SPA fallback → React web app, `/favicon.ico`.

## Daily cron steps (api/cron/daily.js, in this order; each step's failure is logged and skipped)

| # | step (response key) | source | data | side effects | phase 2 |
|---|---|---|---|---|---|
| 1 | expire open blood requests (`closed`) | daily.js | requests (status open, !isActive) → closed/expired, geoOpen removed | push "request ended" to accepted donors (requestend.notifyEnded, first 200) | blood |
| 2 | donors eligible again today (`eligibleReminders`) | daily.js | users.lastDonationMillis in (now−120d−1d, now−120d], notify≠false | FCM push + inbox | blood |
| 3 | appointment reminders (`care`) | reminders.js dailyReminders | appointments: pending past date or >48 h → expired; confirmed >3 days old → completed; today's confirmed | push to patient/provider | appointments |
| 4 | close expired emergencies (`emergenciesClosed`) | emergency.js closeExpired | emergencies active & ended → closed | push to "coming" donors | blood |
| 5 | medicine-find cleanup (`medFindsDeleted`) | medfind.js dailyCleanup | medFinds closed > 2 days → delete | — | pharmacy |
| 6 | phone-code limit cleanup (`phoneLimitsDeleted`) | daily.js | phoneLimits day < today → delete (≤400) | — | users |
| 7 | geohash maintenance (`geo`) | geo.js maintain | users geohash/donorGeo backfill; requests geoOpen add/remove | — | **drop** (SQL partial index replaces geoOpen; donor_geo maintained on write) |
| 8 | follow-up reminders (`rxReminders`) | prescriptions.js followUpReminders | prescriptions followUpDate in 2 days / tomorrow | push to patient + caregivers | rx |
| 9 | refill reminders (`refill`) | rxnet.js refillReminders | prescriptions/rxOrders refill date in 2 days | push to patient + caregivers | rx |
| 10 | scheduled account deletion (`accountsDeleted`) | account.js deletion.runDue | serverState.deleteAt ≤ now (5 per run) → deleteNow (everything, then Auth user). Deactivated accounts (`deactivated_at`, Oct 2026) are **never** picked: deactivation has no expiry | pushes to affected counterparties | users |
| 11 | inbox retention (`inboxDeleted`) | inbox.js cleanupInbox | inbox createdAt < now − 21 days (≤4000) | — | notify |
| 12 | "still available?" ambulance reminders (`ambulanceReminders`) | ambulance.js remindAvailable(backfill) | ambulances remindAt ≤ now (available 11 h) | push to owner | ambulance |
| 13 | ambulance-request cleanup (`ambulanceRequests`) | ambreq.js dailyCleanup | advance stale active requests; purge exact location/phone/address/note/name 24 h after close (purgeAt) | pushes from advance() | ambulance |
| 14 | privacy purge of online orders (`privacy.rxOrderBackfill`, `privacy.rxOrderContactsPurged`) | rxnet.js backfillClosedContacts, purgeClosedContacts | rxOrders closed > 7 days → address/phone removed (purgeContactAt) | — | rx (backfill: **drop**, one-off) |
| 15 | one-off backfills (`privacy.salePatientNames`, `stockPrices`) | shopbooks.js stripSalePatientNames, backfillStockPrices | shopBooks sales (collectionGroup), pharmacyStock prices/mrp | — | **drop** (no legacy data in PostgreSQL) |

Spring status (phase 2): steps 1, 2, 4 (`blood`), 3 (`appointments.AppointmentReminders`), 5 and 14 (`pharmacy`, `privacy`
holds only `rxOrderContactsPurged`), 6, 10 and 11 (`users.PhoneLimitsTask`, `users.AccountsDeletedTask`, `notify.InboxCleanupTask`),
8 and 9 (`rx`), 12 and 13 (`ambulance`), and the Spring-only step 15 `callsDeleted` (`calls`, call metadata after 180 days — not the Node step 15) are `jobs.DailyTask` beans run in this order by `DailyJobRunner` (JobRunr 03:00 UTC and
`GET /api/cron/daily`). Dropped: 7 (`geo`), the backfill half of 14 and 15 (`stockPrices`, `privacy.salePatientNames`) — one-off
Firestore maintenance with no PostgreSQL counterpart; those keys are absent from the response. A failing step answers `null`
(Node: its default `0`/`null`/`{}`) and the others still run.

## Scheduled, lazy and async behaviour (not cron)

- **Ambulance request state machine** (`ambreq.js advance`, run lazily on every read/write of a request and by cron step 13):
  open + 60 s (`WIDEN_MS`) or everyone declined → next wave of 5 (`WAVE`) + phone fallback list; open + 3 min (`EXPIRE_MS`) →
  expired (push patient); quotes valid 2 min (`QUOTE_MS`); accepted/arrived 12 h → auto close; purge 24 h after close
  (`PURGE_AFTER_MS`); max 5 creates/hour/patient (`PER_HOUR`, ambReqLimits). Phase 2 candidate: JobRunr delayed jobs per
  request + SSE (`SseHub`) instead of polling.
- **Ambulance availability**: available flag expires after 12 h (`AVAILABLE_MS`); reminder at 11 h (`remindAt`), sent by
  `remindInBackground()` when anyone lists ambulances (throttled `REMIND_EVERY_MS`) and by cron step 12.
- **Blood request notify**: `notifyingUntil` lock 60 s; re-notify ≤3 rounds, 6 h apart; per-donor push cap per day
  (`maxPushPerDay`, default 3, max 10); donors already notified stored in requests/{id}/meta/notified.
- **Request reopen** window: 1 h after closing (`request_reopen`, undo from meta/close).
- **Emergency waves**: admin-driven (`admin_emergency_create`/`admin_emergency_more` → `sendWave`), not timed.
- **Queue pushes**: `queue_control` pushes patients whose turn is near (`appointments.nearPushAt`), late replies.
- **Medicine find**: open 3 h (`MED_OPEN_MS`), forward allowed after 10 min (`MED_FORWARD_MS`), ≤15 shops, ≤30 finds/shop/day.
- **Rating windows**: ambulance & pharmacy 7 days after a call (`RATE_WINDOW_MS`), home care 30 days.
- **Account deletion grace**: 30 days (`GRACE_MS`); login cancels (phase 1 implemented); cron step 10 deletes.
- **Background push writes**: every push also writes the inbox; dead FCM tokens are removed (phase 1: `notify/PushService`).
- **In-memory caches (per instance, Node memcache)** → Redis/Spring Cache in Kotlin:
  guest answers 60 s (`guest:<body>`, phase 1 done), guest request grid 60 s (`guestreq:`), providers list 2 min (`providers:<type>`),
  home providers 2 min (`home:<type>`), lab prices public 60 s (`labprices:one:<id>`, `labprices:<district>`), labs per district 60 s
  (`labs:`), area pharmacies 60 s (`pharm:`), ambulance destinations 10 min (`ambfac:`), ambreq district cars 10 s
  (`ambreq:district:`), catalog 5 min (loaded on every request), lab tests catalogue TTL, admin list 10 min (phase 1 done:
  cache `admins`), admin stats 30 min (serverState/_adminStats), disabled-account check 1 min (phase 1 done).
- **In-memory rate limits** → Redis (atomic Lua INCR + PEXPIRE; Bucket4j until Oct 2026): guest 240/min/IP (phase 1 done), `maps_link_resolve` per uid per minute (`ambplace.checkLimit`).
- **Outbound HTTP**: SMS gateway (`SMS_URL_TEMPLATE`, phone.js), Google Maps short-link resolution (ambplace.js resolveShort, with timeout).

## Environment variables read by the Node server

`FIREBASE_SERVICE_ACCOUNT` (JSON or base64; also the HMAC base for email codes and the web session when no
`WEB_SESSION_SECRET`), `ADMIN_EMAILS`, `CRON_SECRET` (also the phone-limit HMAC key), `WEB_SESSION_SECRET`,
`GMAIL_USER` + `GMAIL_APP_PASSWORD` (→ SES in Kotlin), `SMS_URL_TEMPLATE`; standalone-server branch adds `DATABASE_URL`,
`PORT`, `HOST`, `TRUSTED_PROXIES`, `SHUTDOWN_TIMEOUT`, `DEFAULT_MAX_DURATION`, `CRON_BASE_URL`, `CRON_TIMEOUT`.
The Kotlin equivalents are listed in [../README.md](../README.md).
