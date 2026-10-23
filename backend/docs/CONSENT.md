# Lifolo — Health data consent (স্বাস্থ্য তথ্যের সম্মতি)

The owner approved this on 2026-10-23. Feature switch: `health_consent` (backend/docs/FEATURES.md), default **off**,
modes off / pilot / on. Migration **V682** (`consents`).

## Why
Bangladesh's personal data protection law (the Personal Data Protection Ordinance 2025, possibly replaced by a PDP Act
2026) treats health data as **sensitive** and needs **explicit, specific consent**. At consent time the person must be told
the purpose, the retention period, who receives the data and how to withdraw. Until now Lifolo only had "using the app =
agreeing to the policy", which is not enough. Lifolo's database is in Europe (Contabo, possibly Singapore later), so
storage **outside Bangladesh** gets its own consent too.

> Not legal advice. The wording is a first draft; a lawyer will edit it (see "Changing the text").

## Two scopes, recorded separately
| scope | meaning |
|---|---|
| `health_data` | keep the person's health information in Lifolo |
| `data_abroad` | keep it on servers outside Bangladesh (now Europe; see PRIVACY.md "Where data is stored") |

One screen and one "রাজি আছি" button grant both, but the server writes **one row per scope**.

## The text — ONE versioned file
`backend/src/main/resources/consent/health-consent.json`: `version` + `bn` + `en` (title, intro, six sections — what /
why / where / who / how long (link to PRIVACY.md "কতদিন রাখা হয়") / how to withdraw — the two scope lines, the
button labels, the caregiver and "consent needed" messages, and the withdrawal screen). The apps never carry a copy:
`consent_status` sends the text in the caller's language, so **changing it needs no app release**.

### Changing the text (for the lawyer / owner)
1. Edit the strings in `health-consent.json` (keep `bn` and `en` saying the same thing).
2. If the meaning changed, put a **new `version`** (e.g. `2026-11-15.1`). Every earlier consent becomes `outdated` and
   people are asked again on their next health-data entry or health-section visit. A pure typo fix may keep the version.
3. Deploy the backend. Nothing else needs editing (the scope ids are fixed in code).
The server checks at start-up that every required string exists in both languages.

## Data — `consents` (V682)
Append-only audit log: `id, uid → accounts ON DELETE CASCADE, scope, version, action (granted|withdrawn),
source (app|web|desktop), at`. A trigger refuses `UPDATE` — history is never rewritten. A scope's state is its latest row:
`granted` (current version), `outdated` (granted for an older version), `withdrawn`, or `none`. The rows go with the
account when it is deleted. Backward compatible (a new table; old code never reads it).

## Actions
Only the person themselves — `CareAccess` refuses `consent_*` with a caregiver's `asUid`:
403 `consent_self_only` "রোগীকে নিজে একবার সম্মতি দিতে হবে".

- `consent_status` → `{on, version, current, ask, needed[], grantedAt, scopes: {health_data|data_abroad: {state, version,
  at, source}}, history[≤20], text}`. `ask` = switch on for the caller and something missing. Works with the switch off
  (answers `on: false, ask: false`).
- `consent_grant {scopes: ["health_data","data_abroad"], version, source}` → the status + `recorded` (scopes newly
  written; an already-current scope writes nothing). Needs the switch (404 `feature_off`). `version` must be the current
  text's → else 409 `consent_outdated {version}` (the screen reloads the new text). Bad scope → 400 `bad_scope`;
  `source` not app/web/desktop → 400 `bad_source`.
- `consent_withdraw {scope: health_data|data_abroad|all, source}` → the status + `recorded` (+ `unchanged` when nothing was
  granted). **Always allowed** — with the switch off and while the account is paused (`ActionPolicy.WHILE_DEACTIVATED`).
  It **never deletes data**.

## The gate (`consent/ConsentGate.kt`, an `ActionGuard` run by the dispatcher before every handler)
While `health_consent` is on **for the patient** (`ctx.user`, i.e. the patient also when a caregiver acts for them), a
patient-initiated entry of health data without a current consent for both scopes is refused:

```
HTTP 428 {error, code: "consent_required", scopes: [missing…], version, caregiver: bool}
```
(428 Precondition Required — no existing code in the project meant "precondition"; 409 is used for conflicts.) The
message is the text's `required` line ("…অ্যাপ হালনাগাদ করুন, বা lifolo.com-এ লগইন করে দিন" — for apps older than RC4)
or, for a caregiver, `caregiver` ("রোগীকে নিজে একবার সম্মতি দিতে হবে").

### Gated actions (`ConsentGate.GATED`)
| action | gated when |
|---|---|
| `rx_claim` | always (adding a doctor's prescription — and its reports — to my account) |
| `profile_update` | `set.bloodGroup`, `set.weightKg` or `set.lastDonationMillis` is non-empty **and differs** from the stored value (the forms resend unchanged fields; clearing a value is not an entry) |
| `donation_date_set` | `millis` set and different from the stored date |
| `book` | the provider is a **psychologist** |
| `care_invite` | always (sharing my health data with a new caregiver) |
| `care_share_psych` | `share: true` |
| `rx_send_pharmacy` | `mode: "online"` (home delivery with address + phone) |
| `rx_send_lab` | with `home` (home sample collection) |
| `lab_home_change` | always (home collection) |

There is no patient-side upload of a prescription photo or a test report today (photo orders closed 15 Oct 2026; reports
come from labs) — a future patient upload action must be added to `GATED`.

### Never gated — emergency exception (`ConsentGate.NEVER_GATED`, checked at start-up and in ConsentIT)
Blood requests and responses (`request_create`, `response_update`, `confirm_donor`, … and the HTTP endpoints
`/api/respond`, `/api/close-request`, `/api/edit-request`, `/api/notify-request` — which also set donors' last donation
date), emergency appeals (`emergency_respond`, `emergency_donated`), ambulances (`ambreq_*`, `ambulance_called`), in-app
calls (`call_*`), every listing (`providers`, `pharmacies`, `labs`, `bloodbanks`, `hospitals`, `ambulances`, …) and every
guest action. Reading one's own data (`rx_mine`, `rx_get`, `my_appointments`, reports) is never gated either.
Why: a person in an emergency must never meet a consent wall; these flows are needed to protect life and are covered by
the vital-interest exception (to be confirmed by the lawyer).

### Provider-created records
A doctor writing a prescription (`rx_create`), a lab uploading a report (`lab_report`), a pharmacy dispensing, an
assistant adding a patient — none is gated: the provider has a record-keeping duty. The patient's `consent_status` then
has `ask: true`, and the apps ask the next time the patient opens a health section.

### Caregivers
A caregiver (`asUid`) can neither give nor withdraw consent for the patient. A gated action for an unconsented patient
returns 428 with `caregiver: true`; the apps show no consent screen, only "রোগীকে নিজে একবার সম্মতি দিতে হবে". The
caregiver's own consent does not count for the patient.

## When existing users are asked
**On the next open of a health section** (web: আমার প্রেসক্রিপশন, আমার অ্যাপয়েন্টমেন্ট; Android: My prescriptions, My
appointments) — or when they first try a gated entry (428). **Not at app start**: that is the less intrusive choice —
people who open Lifolo for a blood request or an ambulance in an emergency never see a consent wall, and people who never
use the health features are never interrupted. "এখন না" on the health-section prompt is remembered per account and text
version **on that device** (web `localStorage` `lf.consentLater`, Android SharedPreferences `health_consent`), so it is
asked once; a gated entry still asks every time (it cannot proceed otherwise). Caregiver mode never shows the prompt.

## Clients
- **Retry flow** — web `lib/api.ts` (`withConsent`) and Android `ServerApi.withConsent`: a 428 `consent_required` →
  `consent_status` (text) → consent screen → "রাজি আছি" (`consent_grant`, both scopes, the shown version, source
  `web`/`desktop`/`app`) → the **same request is sent once more**; "এখন না" → error `consent_declined`
  "সেভ হয়নি। এর জন্য স্বাস্থ্য তথ্যের সম্মতি লাগে।". `caregiver: true` → the server message, no screen.
- **Screen** — web `sections/consent/ConsentDialog.vue` (Reka UI dialog; bottom sheet on phones), Android
  `HealthConsentDialog` (CardDialog). Text from the server, theme colours and typography only (no fonts set).
- **Settings › গোপনীয়তা** — web `ConsentSettings.vue`, Android `HealthConsentSettings`: each scope's state and date,
  "পুরো লেখা দেখুন", "সম্মতি দিন" (when not current), "সম্মতি তুলে নিন" → a confirmation that says plainly that nothing is
  deleted and links to account deletion. Shown only while the switch is on for the user.
- Desktop (Tauri) runs the web app; it records `source: "desktop"`.

## Tests
- Backend: `consent/ConsentIT` — switch off = unchanged, status/grant/audit (append-only), every gated action with and
  without consent (and the non-entry variants), one scope missing, emergency paths never gated, provider-created records,
  caregivers, withdrawal (data stays), text version bump, pilot (+ demo). `features/FeaturesIT` — `health_consent` outside
  all-on.
- Web: `tests/unit/consent-api.test.ts` (428 → retry / declined / caregiver), `tests/unit/consent.test.ts`,
  `e2e/consent.spec.ts` (switch off, section prompt, retry flow, "এখন না", settings + withdraw).
- Android: `ConsentScreensTest` (server text, dialog, settings, withdraw confirmation, ask/answer).
- Manual: `docs/test-plan/manual/23-health-consent.md` (M-CNS, release phase 18).

## Open for the lawyer
- Final Bangla/English wording in `health-consent.json` (then bump `version`).
- Which law applies (PDP Ordinance 2025 vs a 2026 Act) and whether the consent age / guardian rules for family members
  (children's prescriptions under a parent's account) need their own wording.
- Whether the emergency exception (vital interests) and the provider record-keeping basis are worded correctly in
  PRIVACY.md, and whether `data_abroad` needs the specific country named when the server moves to Singapore (then bump
  the version so everyone is asked again).
- Retention periods are unchanged (PRIVACY.md "কতদিন রাখা হয়").
