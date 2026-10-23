# Today's merges (23 Oct 2026): independent security and privacy review

An adversarial second look at what reached `main` on 23 Oct 2026. The agents that built these features had reviewed
their own work. In scope: health consent (`backend/docs/CONSENT.md`, V682, the 428 gate), the "ডা."/"Dr." title rule
(`title_guard`, `backend/docs/PRACTICE.md` §6), the prescription state and timeline (`backend/docs/RX-STATE.md`), the
two-icon contact row and call menu, the pay/invoice push changes, and the self-hosted font change (CSP). The
afternoon merges (medicine finder quantity, prescription phase rules with `rx_mark_done`, the cancellation reason) are
covered under [Later merges](#later-merges).

Method: the code on `main` was read, and attacks were written as backend integration tests or app unit tests. Every
confirmed issue was fixed with the smallest change that closes it, and a regression test was added. Items that were
not fixed say why.

**সংক্ষেপে (মালিকের জন্য):** ১১টা বিষয় পাওয়া গেছে। এর মধ্যে ৬টা ঠিক করা হয়েছে, আর ৫টা আপনার বা উকিলের সিদ্ধান্তের জন্য রাখা হয়েছে।
সবচেয়ে বড় সমস্যা ছিল T1: হোমিওপ্যাথ বা নার্স নামের আগে অদৃশ্য অক্ষর (zero-width), অন্য ধরনের ফাঁকা জায়গা, চওড়া "Ｄｒ" বা রুশ "о" দিয়ে
"Dоctor" লিখলে "ডা."/"Dr." নামের সাথে থেকে যেত। সেভ করার সময় আটকাত না, আর দেখানোর সময়ও মুছত না। এখন আটকায়, আর আগে থেকে রাখা নামেও মুছে যায়।
T2: হোমিওপ্যাথ নিজেকে MBBS দাবি করে আবার যাচাইয়ের আবেদন করলে অপেক্ষার সময়েও "ডা." দেখাত। কারও আবেদন বাতিল হলেও দেখাত। এখন বাতিল আবেদনে কখনো দেখায় না,
আর হোমিওপ্যাথির তালিকা থাকলে অনুমোদনের আগে দেখায় না।
P1: সহকারীর রসিদ বাতিল আর "দিনের শেষ"-এর পুশে টাকার অঙ্ক আর রসিদ নম্বর লক স্ক্রিনে দেখা যেত। এখন সেখানে শুধু সাধারণ লেখা দেখায়।
আপনার সিদ্ধান্ত লাগবে C1-এ: সম্মতি না দেওয়া কেউ প্রথমবার রক্ত দিতে চাইলে আগে প্রোফাইলে রক্তের গ্রুপ দিতে হয়। আর সেটা দিতে গেলেই সম্মতির পর্দা আসে।
তার মানে জরুরি রক্তদানের পথেও পরোক্ষভাবে সম্মতির পর্দা পড়ে।

Severity scale: as in [06-phases-and-exit-criteria.md](06-phases-and-exit-criteria.md#তীব্রতা-severity). 🔴 Critical,
🟠 High, 🟡 Medium, 🟢 Low.

## Summary

| # | Topic | Severity | Status |
|---|---|---|---|
| T1 | Title rule: zero-width characters, Unicode spaces, full-width or styled letters, look-alike letters, combining marks, brackets and "ডক্টর" all hid a title, both when saving and when showing | 🟠 High | confirmed, **fixed** `fc0a7a61` |
| T2 | Title rule: a pending or rejected MBBS/BDS badge application made the person "eligible", so the title was shown (homeopath re-verifying as allopathic, or a fresh fake claim) | 🟡 Medium | confirmed, **fixed** `fc0a7a61` |
| T3 | Title rule: a non-eligible provider whose stored titled name equals an MBBS doctor's name was never stripped | 🟢 Low | confirmed, **fixed** `fc0a7a61` |
| T4 | Title rule: chamber pay requests (`pay_requests.biz_name`) were missing from the stored-name copies | 🟢 Low | confirmed, **fixed** `fc0a7a61` |
| T5 | Title rule covers a leading title only: suffixes ("করিম (ডা.)", "Karim, MBBS"), "ড." (PhD), free text and CSV exports are not covered | 🟢 Low | open (policy, see below) |
| P1 | Android lock screen showed amounts and receipt numbers for `invoice_void` and `invoice_summary` pushes | 🟡 Medium | confirmed, **fixed** `d586313b` |
| F1 | Desktop (Tauri) CSP still allowed `font-src 'self' data:` | 🟢 Low | confirmed, **fixed** `61e898bb` |
| C1 | Consent vs emergencies: answering a blood request needs a stored blood group (and weight), and entering those is gated, so a first-time donor meets the consent screen inside the emergency flow | 🟡 Medium | confirmed, **open** (owner/lawyer decision) |
| C2 | Consent audit log: `UPDATE` is refused, but `DELETE`/`TRUNCATE` are not, and the rows go with the account (no proof of consent after deletion) | 🟢 Low | confirmed, open (needs a migration) |
| C3 | `source` (app/web/desktop) is client-declared | ℹ️ Info | by design |
| X1 | Contact row: when `contact_can` fails, the client falls back to `call_config` and shows app-call options it cannot vouch for | ℹ️ Info | by design (the server still refuses) |

No Critical item. Each area below lists what was checked and found sound.

### With every feature switch on (owner direction: only testers use the site, treat every switch as ON)

* The fixes T1–T4 (`title_guard`) and P1 (`inv_*`) apply exactly in this setting.
* **C1 is the open item that matters most here:** with `health_consent` on, every tester who has not consented and
  answers a blood request for the first time (no stored blood group) sees the consent screen first.
* **`FEATURES_ALL_ON` does not turn on `health_consent`.** `Features.OUTSIDE_ALL_ON` exempts it, because it adds a
  precondition instead of unlocking a feature. Use the "Ops: feature switches" `all_on` action (merged later today,
  `557d9f62`) or the admin page. Those set every key, `health_consent` included, in the database. The environment
  variable alone leaves the consent gate **off**. The ops endpoint `/api/cron/features` needs `CRON_SECRET`, compared
  in constant time, refuses everyone when the secret is empty, and is not routed by nginx from outside, so it fails
  closed.

### Switch-off paths fail closed (checked)

* Server: an unknown key is off, and so is a key with no row (default mode off). A key is on only when every key in
  `NEEDS` is on too. A database error while reading the switches throws, so the request fails (500) and nothing is
  unlocked.
* Two-party features (`bothOn`) need the switch for both people. Actions behind a switch answer 404 `feature_off`.
  `consent_grant` needs `health_consent` (404). `consent_status` and `consent_withdraw` stay available on purpose:
  withdrawing must always be possible.
* Web (`lib/features.ts`: `state.keys[key] === true`) and Android (`Features.on`: `== true`) treat a key they have not
  received, or a failed `features` call, as **off**.
* `title_guard` off shows names as stored and does not check saving. That is the documented "off" behaviour, not a
  failure. One fail-**open** path remains: if the stored-name list cannot be read (`TitleGuard.names()` → empty) or the
  rewrite throws, the answer goes out unstripped. It is display-only and needs a database error. 🟢 Low, left as is.

---

## Title rule (`title_guard`, PRACTICE.md §6)

### T1: Unicode tricks hid the title (High, fixed)

**What was wrong.** `TitleRules.TITLE` was `^\s*(?:ডাক্তার|ডা|doctor|dr)(?:\s*[.:ঃ]+\s*|\s+)` on the raw string.
Java's `\s` matches only ASCII whitespace, and the alternatives match only ASCII letters, so each of these
was **not** a title. A non-MBBS provider could save it (no 400), and a stored one was shown unstripped to every
viewer:

| trick | example |
|---|---|
| zero-width / format characters before or inside | `​Dr. X`, `ডা‌. X`, `﻿Dr. X`, `⁦Dr. X`, `ডাক্‍তার X` |
| Unicode spaces | `Dr X`, ` ডা. X`, `Dr X`, `Dr　X`, `ডাঃ X` |
| full-width / mathematical / roman-numeral letters, dot leaders | `ＤＲ. X`, `𝐃𝐫. X`, `Dr． X`, `Dr․X` |
| look-alike letters | `Dоctоr X` (Cyrillic о), `Dг. X` (Cyrillic г), `ᴅʀ. X`, `Ꭰr. X` (Cherokee) |
| combining marks and variation selectors | `D́r. X`, `Dr️. X`, `ডা়. X` (nukta) |
| other punctuation after the title | `ডা৽ X` (Bengali abbreviation sign), `ডা। X` (danda) |
| brackets or symbols before it | `(ডা.) X`, `[Dr] X`, `🩺 Dr. X` |
| the Bengali spelling of "Doctor" | `ডক্টর X` |

**Fix** (`practice/TitleRules.kt`). The title is matched on a *folded* copy of the name. Format characters
(zero-width, BOM, bidi controls, soft hyphen), variation selectors, Latin and general combining marks and the Bengali
nukta are dropped. Every Unicode space becomes a plain space. Each non-ASCII, non-Bengali code point goes through NFKD,
so full-width, mathematical and dot-leader forms become ASCII. A small look-alike map (Cyrillic, Greek, Cherokee,
Lisu, small capitals) turns those letters into `d r o c t`. The pattern now allows any punctuation after the title,
brackets or symbols before it, and "ডক্টর". `strip()` cuts the **original** string at the matching position, so the
rest of a name is shown exactly as written. Bengali text is never normalised: the precomposed "ড়" is still not "ড".
The SQL pre-filter (`LOOKS`) and `maybeTitled` were widened for the folded forms. Without that, stored tricked names
would never reach the rule.

**Tests.** `TitleGuardIT`: `rules - unicode tricks cannot hide a title` covers 28 variants plus the non-titles that
must stay names ("ডালিয়া", "Drabir", "Doctorate", "Dmitry", "করিম ডা.", a bare "Dr."). `security review - unicode
titles refused on saving and stripped when stored` checks `profile_update` (400 `title_not_allowed`), the provider
list for stored `Dr …` and `​ডা. …` names, and the admin list.

### T2: pending or rejected MBBS claims counted as eligible (Medium, fixed)

**What was wrong.** A person's profession was "the badge (any status), else the listing". Two cases followed.
An approved homeopath who asks to re-verify as allopathic gets a `pending` badge with `system = allopathy`. A user
can also apply fresh as an allopathic doctor with any document. In both cases, from that moment the person was
*eligible*: `profile_update` accepted "ডা." and every stored titled spelling was shown with the title. A rejection
kept `type/system` on the row with `status = 'rejected'`, so the title stayed after the admin said no. Such a person
was also missing from the admin list "‘ডা.’ লেখা নাম".

**Fix.** `TitleRules.eligibleProfile`: when there is a badge, an MBBS/BDS claim counts once `approved`. A `pending`
claim counts for saving the name, so a real doctor waiting for review is not blocked from editing the profile and the
admin checks the name with the application. It counts for showing the name only when no listing says otherwise: a new
applicant has no listing yet and is not listed while waiting, while a homeopath re-verifying as allopathic keeps the
homeopathy listing until approval, so the title is not shown. A `rejected` claim never counts. With no badge, the
listing decides, as before. This rule is used for saving (`requireOwnName`), for the names to strip (`NAMES_SQL` now
returns badge status and listing separately) and for the admin list.

**Test.** `security review - an MBBS claim counts only once approved (pending, rejected)`.

### T3: a shared name kept the title (Low, fixed)

"A name that an eligible doctor also has is never stripped" was global. A homeopath whose stored name is exactly an
MBBS doctor's ("ডা. মোঃ …") was never stripped anywhere. This happens with common names, and the name can be copied
on purpose. Now such a name is stripped in an answer object when one of that object's other string values is the
non-eligible person's uid (list items, appointments and most other objects carry the uid next to the name). The MBBS
doctor's own items are untouched. Test: `security review - a non-eligible provider with the same titled name …`.
Remaining gap: a push or bell text with a shared name cannot tell the two apart. It stays as stored.

### T4: chamber pay requests (Low, fixed)

`pay_requests.biz_name` (chamber fee and advance, a copy made when the request is created) was not in `NAMES_SQL`. An
older titled spelling kept only there would show on the pay sheet. It is added now.

### T5: what the rule does not cover (Low, open)

* A title **after** the name ("করিম (ডা.)", "Karim, Dr.") or a fake degree in the name ("Karim MBBS"). The rule is
  defined as a leading title, so the owner and the lawyer decide whether to widen it.
* "ড." is the usual Bengali abbreviation for a PhD ("ড. মুহম্মদ …"), so it is deliberately not a title. A
  psychologist with a PhD may use it. Lawyer to confirm.
* Free text is not covered: chamber names, chat messages, prescription notes, and CSV exports (`invoice_export_csv`,
  `pay_export_csv`), where the name sits inside one long string.
* The web (`lib/titleGuard.ts`) and Android (`TitleGuard.kt`) inline validation do not fold Unicode, so a tricked
  name passes the form and the server answers 400 with its message. The server stays the enforcer. Mirroring the fold
  in the clients is only a UX improvement.

### Checked and sound

* No client and no server text builds a title itself ("ডা. " + name): names are shown as stored, and profession
  labels come from the server's `typeLabel`.
* Every `/api/community` answer passes `TitleGuard.apply` except `admin_title_names` and one's own `profile_get`.
  Pushes, bell entries, SSE `user:` topics (the only SSE topics), account emails, LiveKit names, lock-screen first
  names and `/rx/{code}` are filtered too.
* `admin_title_names` is admin-only (`isAdmin`, verified email in the owner list or `admin_emails`). The web panel
  cookie session also needs `isAdmin`, and every `admin_*` call is written to the admin log. It returns email and
  phone of the listed providers, which is the same data the badge review pages already show admins. No leak beyond
  admins was found.

---

## Health consent (CONSENT.md, V682, 428 `consent_required`)

### Gate coverage (sound)

For each `GATED` action, `ConsentGate.entersHealthData` was compared with the handler's own parsing. None can be sent
in a form the handler accepts but the gate reads as "no entry":

| action | gate reads | handler reads |
|---|---|---|
| `profile_update` | `set.bloodGroup / weightKg / lastDonationMillis`, changed vs stored | the same `set` keys (`SELF_FIELDS`); no other key reaches those columns |
| `donation_date_set` | `Body.int("millis")` (integer or float) | the same `Body.int("millis")`; explicit null = clearing |
| `book` | `providers.type` of `providerUid` | `ProviderStore.load(providerUid)` from the same table |
| `rx_send_pharmacy` | `mode == "online"` | `mode == "online"` else shop |
| `rx_send_lab` | `home` is an object or array | `LabPrices.cleanHome`: the same test |
| `care_share_psych` | `isTrue("share")` | `isTrue("share")` |
| `rx_claim`, `care_invite`, `lab_home_change` | always | — |

The gate runs in `ActionDispatcher` after the caregiver switch, so `ctx.user` is the patient, and before every
handler. Action names are exact map keys (no aliases). The only other write paths are the blood HTTP endpoints
(never gated by design) and admin or cron endpoints. The web panel session can call only `admin_*`.

* **Caregivers cannot consent.** `CareAccess` refuses `consent_*` with a foreign `asUid` (403 `consent_self_only`),
  and `ConsentActions.needLogin` checks `actingBy` again.
* **Version bumps cannot be skipped.** `consent_grant` requires `version == text.version` (409 `consent_outdated`
  otherwise). A row for an older version counts as `outdated`, and `missing()` returns it, so the gate answers 428
  again.
* **Emergency actions are never gated.** `GATED ∩ NEVER_GATED = ∅` is checked at start-up, no blood, ambulance, call or
  listing action is in `GATED`, and guests skip the gate.

### C1: the donor's blood group is gated, so the emergency flow meets consent (Medium, open)

`/api/respond` refuses a donor without a stored blood group ("আগে প্রোফাইল পূরণ করুন" / "রক্ত দিতে আগে প্রোফাইলে আপনার
রক্তের গ্রুপ দিন"). `isEligibleDonor` also needs weight, and `emergency_respond` needs the group ("এখানে আপনার গ্রুপের রক্ত
দরকার নেই"). Setting `bloodGroup` or `weightKg` through `profile_update` is gated. So with `health_consent` on, a person
without consent who opens an emergency request and tries to donate for the first time is sent to the profile, and saving the
blood group shows the consent screen. They can agree and continue, so this is not a hard block, but it is a consent
wall inside the emergency path that CONSENT.md promises never to put there. Existing donors with a stored group are
unaffected (unchanged values are not gated).
**Not fixed** because two owner-approved rules conflict: "blood group is health data → explicit consent" and "a
person in an emergency never meets a consent wall". Options for the owner and lawyer: (a) treat blood group, weight and
last donation date as part of the blood-donation purpose (vital interest) and take them out of the `profile_update`
gate; (b) keep the gate but have the apps skip the screen for the donor flow and record the entry under the
emergency basis; (c) keep it as it is and document it.

### C2: the audit log can still be deleted (Low, open)

V682's trigger refuses `UPDATE`. `DELETE` and `TRUNCATE` work, and `ON DELETE CASCADE` removes the person's whole
consent history with the account. No code path deletes rows today (only the cascade). The hardening is a `BEFORE DELETE` trigger
that allows only cascaded deletes (`pg_trigger_depth() > 1`) plus `BEFORE TRUNCATE`. Whether proof of consent must
outlive account deletion (for example a hashed uid and the timestamps) is a question for the lawyer. Not done now because it needs a
migration, and the rule is "only if unavoidable".

### C3: `source` (Info)

`source` (app/web/desktop) is whatever the client says. It is only a record, so this is fine for an audit log, but it
should not be used as proof of the channel.

---

## Prescription state and timeline (RX-STATE.md): sound

* `rx_mine` selects by `patient_uid = ctx.user.id`, and caregivers additionally lose psychologists' prescriptions
  (`privacy.hidesPrivate`) unless shared. `state` is computed only for the rows already returned.
* `rx_get` (IDOR): the row must have the caller as patient or as writing doctor (404 otherwise, the same body). A
  caregiver gets only the patient's side (`patientSideOnly`), and `privacy.hidden` hides psychologists. `state` and
  `timeline` are added only on the patient branch, and the writing doctor's branch is unchanged (no pharmacy or lab
  names of the patient's orders).
* Assistants, pharmacies and labs have no path to `state`. Their views (`rx_pharmacy_open`, `rx_assisting`, the lab code
  view, `/rx/{code}`) are untouched, and `RxStateLoader` reads only the ids passed in.
* The timeline holds the patient's own orders, purchases and lab steps (pharmacy and lab names), which the patient
  already sees in `rx_activity`.

## Contact row and call menu: sound (X1 is informational)

The web `ContactActions.vue` and Android `ContactActions` only *hide* options. Every app call or message goes through
`call_start` / the chat thread access (`ChatService.relationOf`), which apply `ContactPolicy.decide` (relation, window, blocks, feature switches for both people,
demo vs real). When `contact_can` cannot be read, the client falls back to `call_config` and the page's own `show`
(unchanged from before). It may then show an "অ্যাপ কল" that the server refuses with `call_not_allowed`. That is UX,
not access. The "ফোন কল" option appears only where the page already received and showed the number (the old
`PhoneLine` was removed in the same commit). In caregiver mode there is no app call or message. `withPhone` now
defaults to true, and every caller passes the number only on pages that showed it before.

## Pay and invoice pushes

* **P1 (Medium, fixed `d586313b`).** Server pushes are data-only FCM, so the app builds every notification. The app
  replaces all `pay_*` texts with "পেমেন্ট আপডেট — লাইফলো" and gives `invoice_new` a public (lock-screen) version, but
  `invoice_void` ("সহকারী রসিদ W-000123 বাতিল করেছেন · ৳৫০০ · reason · note") and `invoice_summary` ("দিনের শেষ: ১২টা
  রসিদ, ৳৮,৪০০") had none, so amounts and receipt numbers showed on the lock screen. Both now get "রসিদের খবর — লাইফলো"
  / "অ্যাপ খুলে দেখুন" (`Notifier.lockScreenText`). Test: `InvoicesTest`, asserting no digits and no ৳.
* **Ids in the payload.** `payRequestId`, `purpose`, `bizId`, `kind` and `asUid` only choose the screen to open. The
  screen loads through `pay_request_get`, which answers 404 unless `pay.sideOf(user, request)` is payer, business or
  caregiver. A crafted intent or a forged id cannot open someone else's payment. `asUid` goes through `CareAccess` on the
  server.
* The desktop app shows OS notifications only for SSE `call_incoming` (caller name, as on a phone ringing) and for
  `push` / `*_new` events, which the server does not emit today.

## Fonts and CSP

* Web and admin builds (`vite build`, `vite build -c vite.admin.config.ts`) have **no** `data:` URL in any CSS. All
  38 font files are `/w/assets/*.woff2`, and no Google Fonts host is referenced. The web and admin CSP (`deploy/deploy-web.sh`)
  keep `font-src 'self'`. Server pages load `/w/fonts/…` from the same origin.
* **F1 (Low, fixed `61e898bb`).** `desktop/src-tauri/tauri.conf.json` had `font-src 'self' data:`. The desktop build
  inherits `assetsInlineLimit` from `vite.config.ts`, so `data:` was no longer needed and is removed.

---

## Later merges

Reviewed as they reached `main` (until about 23:00).

* **Prescription phase rules + `rx_mark_done`** (`6233c40e`, V683): sound. `rx_mark_done` loads the row by id and
  requires `patient_uid == ctx.user.id` (404 otherwise, also for the writing doctor). It is in `CARE_ACTIONS`, so a
  caregiver acts as the patient, and `privacy.hidden` keeps psychologists' prescriptions away from caregivers. The
  "superseded" lookup (`RxStateLoader.facts`) only considers prescriptions on the **same account** (`patient_uid = ANY`)
  from the **same doctor**, so `supersededBy {id}` never points to another patient's prescription. A newer psychologist
  prescription can only supersede an older one from the same psychologist, which the caregiver cannot see either.
  Account deletion clears the two new columns.
* **Prescription cancel reason** (`bc81940b`, V684): sound. Only the writing doctor (`doctor_uid == ctx.user.id`;
  `rx_cancel` is not a caregiver action) can cancel. The reason must be one of five keys. The note is 3–200
  characters for "other" and ≤ 200 otherwise, and is stored as data. `cancel_reason` / `cancel_note` are read **only** by
  `RxStateLoader`, so they reach the patient's `rx_mine` / `rx_get` state and timeline and the caregiver's (minus
  psychologists). They never reach the doctor-side, pharmacy or lab views, `/rx/{code}` or any push. The first
  cancellation wins (`AND status <> 'cancelled'`).
* **Ops: feature switches** (`557d9f62`, `a93c8647`, `a44e913d`): `/api/cron/features` is behind `CronAuth`. See
  [With every feature switch on](#with-every-feature-switch-on-owner-direction-only-testers-use-the-site-treat-every-switch-as-on).
* **Medicine finder quantity** (`med_find` qty/unit, the shop's answer): **not on `main` yet**. The `medfind-qty` branch
  was still marked WIP at 21:26. A pre-read of that branch found nothing blocking:
  * the shop's push carries the medicine and the amount, never the buyer's name or phone;
  * the buyer's push keeps the old generic text with no stock number;
  * `dismiss` writes only the caller's own target row.
  * Note: its design differs from the brief: the shop answers `haveQty`, and there is no "none". It needs its own
    review when it merges. Its migration must be numbered above V684.

## CI

* `sec-review` (before merge): Backend (Spring Boot) ✅ (651 tests). The first run found two demo tests that relied on
  the title of a pending new MBBS applicant ("ডা. ডেমো তানভীর রহমান"), which led to the T2 refinement. Desktop app ✅.
* `sec-review-ci` (temporary, app only, no APK): compile + `InvoicesTest`, `PayTest`, `TitleGuardTest`: 37 tests ✅.
* `main` at `9dd9acda` (fast-forward, together with `6233c40e`): Backend ✅, Desktop app ✅, **Deploy backend ✅**. Web
  e2e was cancelled by the next push (`concurrency`). It was already failing on `main` before this review (`9111042c`,
  `8b344049`), and no web source file changed here.
