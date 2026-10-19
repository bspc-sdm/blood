# Phase 2 plan — porting the remaining 222 actions

Phase 1 (this branch) delivered the foundation: schema (V1–V7, all tables already exist), the `/api/community` dispatcher with
the Node rules, auth endpoints, `profile_get`/`profile_update`/`web_logout`, and shared services. Phase 2 ports everything else
in **six work packages** that can run in parallel on their own branches (`spring-p1` … `spring-p6`, each merged into `spring`).

Ground rules for every package:

* **Parity first.** Same request fields, response shape, status codes, error texts (copy Bangla strings from the Node file —
  see README §2 on "য়"), same quotas, same side effects. Improvements come after cutover.
* Work only inside your packages (+ your own tests). The registry finds your `ActionModule`; tables already exist. If you need a
  schema change, add a **new** migration `V{n}__p{k}_….sql` (pick `n` = 100·k + i, e.g. P3 uses V300, V301 …, to avoid clashes).
* Port the listed Node tests to `*IT.kt` (`IntegrationTest.call(uid, action, body)` mirrors the Node helper). Port unit tests
  of pure helpers too. CI must be green.
* Tick the actions in [API-INVENTORY.md](API-INVENTORY.md) when done. Daily-cron steps become `jobs.DailyTask` beans with the
  `order` from the inventory table (1–15) and the same response key.
* Demo data: each package ports its part of `server/lib/demo/*.js` as a `DemoSeedPart` bean (contract below).
* Account deletion: each package contributes an `AccountDeletionStep` bean (hide / restore / delete-now for its data).

## Phase-1 kernel everyone uses

`common`: `Body` (JS semantics), `ApiException`, `Lang.tr/pick/bnNumbers`, `I18n`, `Rules` (`LIMITS`, `isValidId`,
`cleanText`, `BLOOD_GROUPS`), `QuotaService.use`, `DhakaTime`, `Geo` (geohash, distance), `Places` (districts, upazilas, unions,
upazila centres), `Phones` (normalizeBd, contactPhone, normId, fixPhone), `Ids`, `Sql`, `RateLimiter`, `ObjectStorage` (R2).
`notify`: `PushService.send / pushUsers / pushPatient` (inbox + FCM, dead tokens), `SseHub`, `MailSender`.
`auth`: `AuthService` (`decodeUser`, `verifyUser`, `accountDisabledError`), `AdminService.isAdmin/isOwner`, `WebSession`.
`users`: `AccountService.ensure`, `DeletionService.cancel/liftProvider`. `care`: `CareAccess.actAs`. `admin`: `AdminLog`.
`demo`: `DemoWorld.sameWorld/isDemoUid`, `DemoLoginService`. `jobs`: `DailyTask`, `DailyJobRunner`.

## Shared contracts (owned by P6 — land them in P6's first PR, day 1)

Several packages need moderation, sanctions, verification documents, listing review and listing stats. P6 ports them first
(from `moderation.js`, `sanctions.js`, `verify.js`, `listingreview.js`, `sellerstats.js`) behind these interfaces in
`com.lifolo.admin.kernel`; the other packages code against the interfaces from day 1 and merge after P6's first PR:

```kotlin
interface Moderation {            // moderation.js reportCount / assertNoOpenReports / reopenOnEdit
    fun reportCount(targetPath: String): Int
    fun assertNoOpenReports(lang: String?, targetPath: String)
    fun reopenOnEdit(targetPath: String)
}
interface Sanctions {             // sanctions.js
    fun checkListing(lang: String?, kind: String, listing: Map<String, Any?>, ownerEmail: String?, isNew: Boolean)
    fun ban(kind: String, listing: Map<String, Any?>, ownerEmail: String?, info: Map<String, Any?>)
    fun unban(kind: String, listing: Map<String, Any?>, ownerEmail: String?)
    fun keepForEmail(email: String?, homeCare: Boolean)
}
interface VerifyDocs {            // verify.js validateDocs / saveDocs / deleteDocs / ownerInfo / panelInfo / assertChecks
    fun validate(lang: String?, kind: String, listing: Map<String, Any?>, docs: JsonNode?, required: Boolean)
    fun save(lang: String?, kind: String, listingId: String, ownerUid: String, listing: Map<String, Any?>, docs: JsonNode?, required: Boolean): List<String>?
    fun delete(kind: String, listingId: String)
    fun ownerInfo(kind: String, listingId: String, listing: Map<String, Any?>): Map<String, Any?>
    fun panelInfo(kind: String, listingId: String, listing: Map<String, Any?>): Map<String, Any?>
    fun assertChecks(lang: String?, admin: AuthUser, kind: String, listing: Map<String, Any?>, checks: JsonNode?): Map<String, Any?>
}
interface ListingReview {         // listingreview.js resolveListing / changedFields / possibleDuplicates / tellSuspendedByReport
    fun resolve(ctx: ActionContext, kind: String, table: String, id: String?): Any
}
interface ListingStats {          // sellerstats.js bumpCalls / addRecentRating / removeRecentRating / callSummary
    fun bumpCall(kind: String, listingId: String, callerUid: String, nowMs: Long)
    fun addRecentRating(kind: String, listingId: String, callerUid: String, rating: Int, comment: String)
    fun removeRecentRating(kind: String, listingId: String, callerUid: String)
    fun summary(kind: String, listingId: String): Map<String, Any?>
}
interface AccountDeletionStep {   // account.js schedule / cancel / deleteNow, split per package
    val order: Int
    fun hide(uid: String) {}                        // at delete_account
    fun deleteNow(uid: String, email: String?) {}  // at runDue (30 days later); notify counterparties first
}
interface DemoSeedPart {          // demo/blood.js, services.js, books.js, rx.js, health.js
    val step: Int; val label: String
    fun seed(ctx: DemoSeedContext)                   // ctx.user(key), ctx.extraUser(key, profile), ctx.near(dx, dy), ctx.track(table, key)
}
```

Merge order: **P6-kernel → (P1 … P5 in any order) → P6-rest** (the admin overview and demo seeding call into every package).

---

## P1 — Blood donation (`blood`) — 34 actions + 6 HTTP endpoints

* **Node:** `requestcreate.js`, `myrequests.js`, `donors.js`, `donationdate.js`, `requestend.js`, `emergency.js` + `emergency-rules.js`,
  `bloodbank.js`, `guest.js`, `corrections.js`, blood parts of `clientdata.js`, `geo.js` (geohash bounds), `api/notify-request.js`,
  `api/respond.js`, `api/close-request.js`, `api/edit-request.js`, `api/admin/corrections.js`, `api/admin/resolve.js`, `api/share.js`.
* **Actions:** `request_create`, `request_reopen`, `request_delete`, `blood_request_get`, `blood_requests_mine`, `blood_requests_near`,
  `blood_response_get`, `blood_responses_list`, `blood_contact_get`, `report_request`, `response_update`, `confirm_donor`,
  `donation_date_set`, `guest_requests`, `emergencies_active`, `emergency_respond`, `emergency_donated`, `admin_emergency_create`,
  `admin_emergency_more`, `admin_emergency_group`, `admin_emergency_close`, `admin_emergencies`, `bloodbanks`, `bloodbank_mine`,
  `bloodbank_save`, `bloodbank_delete`, `bloodbank_called`, `bloodbank_stats`, `admin_bloodbanks`, `admin_bloodbank_resolve`,
  `admin_bloodbank_add`, `admin_bloodbank_edit`, `admin_bloodbank_delete`, `admin_districts`.
* **HTTP:** `POST /api/notify-request`, `/api/respond`, `/api/close-request`, `/api/edit-request`, `/api/admin/corrections`,
  `/api/admin/resolve` (all with `AuthService.verifyUser` / admin + web cookie), `GET /api/share` + routes `/r/{id}`, `/rx/{code}`,
  `/shop/{code}`, `/.well-known/assetlinks.json`, QR SVGs (needs a QR library, e.g. `io.nayuki:qrcodegen`).
* **Cron:** step 1 (expire requests + notify), 2 (eligible-again reminders), 4 (close expired emergencies). Step 7 (geo maintenance) is dropped.
* **Tables:** `blood_requests`, `blood_request_contacts`, `blood_request_notified`, `blood_request_close_marks`,
  `blood_request_close_mark_donors`, `blood_responses`, `blood_request_reports`, `correction_requests`, `emergencies`,
  `emergency_groups`, `emergency_donors`, `blood_banks`, `blood_bank_calls`, `listing_stats*` (kind `bloodbank`),
  `user_profiles` (donor search via `donor_geo`, `last_donation_ms`), `user_commitments`, `user_state` (push counters), `user_quotas`
  (`request`, `respond`, `create`, `dateChange`, `bloodbank`), `donation_date_log`.
* **Provides:** `GeoQuery` (port of geofire `geohashQueryBounds` + `near`/`nearDonors` over SQL ranges), `RequestEnd`
  (notifyEnded/donorLeft/releaseOthers — P6's account deletion calls `donorLeft`).
* **Depends on:** kernel; P6 contracts (Sanctions, VerifyDocs, ListingReview, ListingStats, Moderation for bloodbank); P2
  `HospitalDirectory` for `admin_emergency_create` hospital lookup (or read `hospitals` + bundled `data/hospital-dir.json` directly).
* **Node tests to port:** `demo-blood`, `emergency` (emergency-rules unit), `clientdata` (blood parts), `guest` (guest_requests,
  emergencies_active), `gaps`, `rules` (rules.js unit: eligibility, pickDonors, isActive, compatibility…), `geo` (bounds),
  `e2efixes` (request parts). Also write ITs for notify-request/respond/close/edit (no Node unit tests — e2e covered them).
* **Demo seed part:** `demo/blood.js` (+ the blood requests in `demo/health.js`).
* **Pitfalls:** blood-request JSON must stay `requestOut` (ms numbers, `notifiedAt` null vs number); the many legacy Bangla
  messages of the 4 HTTP endpoints go through `I18n.toEnglish`; notify radius loop `RADII_KM` until `ENOUGH_DONORS`; same-world
  filtering on every donor/request query (`is_demo`).

## P2 — Doctors, community content & caregivers (`appointments`, `homecare`, `care`, `content`) — 42 actions

* **Node:** `appointments.js`, `queue.js`, `apptcancel.js`, `reminders.js`, `homecare.js`, `care.js` (chamber/schedule/filter helpers),
  `careaccess.js`, `hospitals.js` + `hospital-match.js` + `hospital-dir.js` + `dghs.js`, badge + Q&A + tips parts of
  `api/community.js`, `community-db.js`.
* **Actions:** `providers`, `provider_me`, `provider_save`, `book`, `my_appointments`, `appointment_update`, `queue_today`,
  `queue_control`, `appointment_queue`, `appointment_late`, `appointment_late_reply`, `home_providers`, `home_called`, `home_rate`,
  `home_stats`, `care_list`, `care_invite`, `care_respond`, `care_remove`, `family_get`, `family_set`, `badge_apply`, `admin_badges`,
  `admin_badge_resolve`, `me`, `questions`, `question`, `ask`, `answer`, `edit_question`, `edit_answer`, `delete_question`,
  `delete_answer`, `tips`, `post_tip`, `edit_tip`, `delete_tip`, `hospitals`, `hospital_request`, `admin_hospital_requests`,
  `admin_hospital_resolve`, `admin_hospitals`.
* **Cron:** step 3 (appointment expiry/completion + today's reminders).
* **Tables:** `providers`, `provider_assistants`, `provider_chambers`, `provider_chamber_times`, `provider_home_times`,
  `provider_queue_stats`, `appointments`, `serial_counters`, `queues`, `home_calls`, `listing_stats*` (kind `home`), `badges`,
  `care_links`, `user_family_members`, `questions`, `answers`, `tips`, `hospitals`, `hospital_requests`, `hospital_request_requesters`.
* **Provides:** `ProviderSuspension` (apptcancel `cancelStale`/`suspendProvider`/`liftProvider` — P6 uses it for admin actions,
  moderation and deletion), `HospitalDirectory` (findHospital, district lists, duplicate detection), `Badges.approvedBadge(uid)`
  (P3 doctors, P6), `Chambers` helpers (chamberOpenOn, takesAppointments, pickListFilters).
* **Depends on:** kernel; P6 contracts (Moderation for edits/reports counts, VerifyDocs for badge docs, ListingStats for home calls,
  Sanctions for home-care blocks).
* **Node tests to port:** `queue`, `doctor-filters`, `demo-health` (Q&A, badges, appointments, care), `demo-services` (home care),
  `demo-night` (providers/book at night), `e2efixes` (book/appointment_update), `labtests` + `rxflow` (care_* / family_* parts),
  `rules` (`me` parts).
* **Demo seed part:** provider/appointment/queue parts of `demo/rx.js` and `demo/services.js`, care + Q&A parts of `demo/health.js`.
* **Pitfalls:** chamber index order is part of the API (`chamberIndex`, `chamberKey` check → 409); serial numbers are assigned in
  the confirm transaction (lock `serial_counters` row); `providers` list is cached 2 min (Spring Cache, evict on save/badge change);
  legacy provider documents (`active` flag, no `chambers[]`) must still save via `legacySave`.

## P3 — Prescriptions & labs (`rx`, `lab`) — 43 actions

* **Node:** `prescriptions.js`, lab + review parts of `rxnet.js` (`labSeg`, `labView`, lab orders, home collection, reports, reviews,
  `rx_activity`), `labprices.js`, `labtests.js`.
* **Actions:** `rx_me`, `rx_team`, `rx_team_set`, `rx_assisting`, `rx_patients`, `rx_patient_history`, `rx_patient_add`,
  `rx_create`, `rx_cancel`, `rx_doctor_list`, `rx_mine`, `rx_claim`, `rx_get`, `rx_activity`, `labs`, `lab_me`, `lab_save`,
  `lab_delete`, `rx_lab_options`, `rx_send_lab`, `lab_orders`, `lab_open_code`, `lab_order_status`, `lab_home_confirm`,
  `lab_home_decline`, `lab_home_cancel`, `lab_home_change`, `lab_report`, `lab_order_get`, `lab_form`, `lab_ranges_set`,
  `lab_prices_get`, `lab_prices_set`, `lab_prices_public`, `labtests_get`, `admin_labtests_set`, `admin_labs`, `admin_lab_resolve`,
  `review_settings`, `review_pay`, `reviews_doctor`, `review_confirm`, `review_done`.
* **HTTP:** prescription verify page/QR data used by `/api/share?rx=` (expose `PrescriptionVerify.verify(code)` for P1).
* **Cron:** step 8 (follow-up reminders), 9 (refill reminders).
* **Tables:** `rx_patients`, `prescriptions`, `prescription_medicines`, `prescription_dispensings` (read), `labs`, `lab_ranges`,
  `lab_prices`, `lab_price_items`, `lab_orders`, `lab_order_price_items`, `lab_report_items`, `lab_report_photos`, `rx_reviews`,
  `lab_test_templates`, `user_state` (lab_home_*), `user_quotas` (`rx`, `rxPatient`, `rxClaim`, `rxSend`, `rxPharm`, `lab`).
* **Provides:** `Prescriptions` (rxByCode, rxView, code generation, `CODE_RE`) — P4 reads prescriptions for dispensing;
  `LabCatalog` (lab test templates merged with `data/labtests-defaults.json`).
* **Depends on:** kernel; P2 `Badges.approvedBadge` + provider assistants (doctor/assistant checks); P6 contracts (VerifyDocs,
  Sanctions, ListingReview for labs).
* **Node tests to port:** `prescriptions` (unit), `rxflow`, `labhistory`, `labprices`, `labtests`, `demo-rx`, `demo-lab`,
  `doctorpatients`, `worksegs` (lab segments), `privacy-gaps` (lab home address purge), `pharmacyestimate` (lab estimate parts).
* **Demo seed part:** prescription + lab parts of `demo/rx.js`.
* **Pitfalls:** `lab_order_get` answers 402 to a doctor until the review is paid; report photos are small base64 JPEGs today (keep
  `data`, move to R2 later); lab home address/phone must be stripped when the lab goes away (`stripHomes`).

## P4 — Pharmacy, orders & shop books (`pharmacy`, `books`, pharmacy side of `rx`) — 45 actions

* **Node:** `pharmacy.js`, `medfind.js` (+ `data/drugs.json`), pharmacy parts of `rxnet.js` (`myPharmacy`, dispensing, online orders,
  stock, missed list, `pharmacyEstimate`, contact purge), `shopbooks.js`, pharmacy helpers of `care.js` (opening hours).
* **Actions:** `pharmacy_me`, `pharmacy_save`, `pharmacy_pause`, `pharmacy_delete`, `pharmacies`, `pharmacy_called`, `pharmacy_rate`,
  `pharmacy_stats`, `admin_pharmacies`, `admin_pharmacy_resolve`, `drug_search`, `med_find`, `med_finds_mine`, `med_find_close`,
  `med_find_requests`, `med_find_answer`, `pharmacy_find_setting`, `rx_pharmacy_open`, `rx_dispense`, `rx_pharmacy_options`,
  `rx_shop_find`, `rx_send_pharmacy`, `rx_order_withdraw`, `rx_orders_pharmacy`, `rx_order_update`, `rx_pharmacy_home`,
  `rx_pharmacy_online_set`, `rx_missed_clear`, `stock_get`, `stock_set`, `books_items`, `books_item_save`, `books_item_delete`,
  `books_item_stock`, `books_purchase_add`, `books_purchases`, `books_purchase_delete`, `books_sale_add`, `books_rx_preview`,
  `books_sales`, `books_sale_delete`, `books_adjust`, `books_adjusts`, `books_today`, `books_report`.
* **Cron:** step 5 (medicine-find cleanup), 14 (online-order contact purge; the backfill half is dropped), 15 is dropped.
* **Tables:** `pharmacies`, `pharmacy_calls`, `pharmacy_ratings`, `listing_stats*` (kind `pharmacy`), `pharmacy_stock`,
  `pharmacy_stock_items`, `pharmacy_missed`, `med_finds`, `med_find_targets`, `rx_orders`, `prescription_dispensings`,
  `prescription_dispensed_items`, `shop_items`, `shop_purchases`, `shop_purchase_lines`, `shop_purchase_undo`, `shop_sales`,
  `shop_sale_lines`, `shop_adjustments`, `shop_days`, `shop_day_sources`, `shop_day_items`, `user_quotas` (`pharmacy`,
  `medFind`, `medTry`, `rxSend`, `rxPharm`).
* **Depends on:** kernel; P3 `Prescriptions` (rxByCode/rxView; prescriptions are read-only here except `dispensed`); P6 contracts
  (VerifyDocs, Sanctions, ListingReview, ListingStats, Moderation).
* **Node tests to port:** `pharmacyrate`, `pharmacy24`, `pharmacylist-rx`, `pharmacyestimate`, `shopbooks`, `medfind` (unit),
  `demo-pharmacy`, `demo-night` (med_find at night), `worksegs` (order segments), `privacy-gaps` (contact purge, sale names),
  `rxflow` (dispense/order parts), `e2efixes` (pharmacies).
* **Demo seed part:** `demo/books.js` + pharmacy parts of `demo/services.js` and `demo/rx.js`.
* **Pitfalls:** every books write updates the day aggregates in the same transaction (see SCHEMA.md); one sale per dispensing
  (`shop_sales_rx_idx`); stock keys/prices/MRP must stay in step with `shop_items` (`stockWrite`); area pharmacy lists are cached 1 min.

## P5 — Ambulance (`ambulance`) — 24 actions

* **Node:** `ambulance.js`, `ambreq.js`, `ambplace.js` (+ `data/upazila-centers.json`, hospital directory), `ambfare.js`,
  ambulance parts of `sellerstats.js`.
* **Actions:** `ambulances`, `ambulance_mine`, `ambulance_save`, `ambulance_available`, `ambulance_delete`, `ambulance_called`,
  `ambulance_rate`, `ambulance_stats`, `admin_ambulances`, `admin_ambulance_resolve`, `ambreq_create`, `ambreq_count`,
  `ambreq_status`, `ambreq_cancel`, `ambreq_driver`, `ambreq_accept`, `ambreq_agree`, `ambreq_budget`, `ambreq_decline`,
  `ambreq_step`, `ambreq_byphone`, `ambreq_replace`, `amb_facilities`, `maps_link_resolve`.
* **Cron / async:** step 12 ("still available?" reminders), 13 (request cleanup + 24 h purge); the lazy `advance()` state machine
  (60 s widen, 3 min expiry, 2 min quotes, 12 h auto-close). Recommended: keep `advance()` on read (parity) **and** schedule a JobRunr
  job at each deadline so pushes happen on time; publish status changes on `SseHub` topic `ambreq:{id}`.
* **Tables:** `ambulances`, `ambulance_fare_rows`, `ambulance_calls`, `listing_stats*` (kind `ambulance`), `ambulance_requests`,
  `ambreq_targets`, `ambreq_quotes`, `ambreq_calls`, `ambreq_fallback`, `ambreq_replaced`, `ambreq_patient_state`,
  `ambreq_create_log`, `user_quotas` (`ambulance`).
* **Depends on:** kernel; P6 contracts (VerifyDocs, Sanctions, ListingReview, ListingStats, Moderation); P2 `HospitalDirectory`
  for destinations (or read `data/hospital-dir.json` directly).
* **Node tests to port:** `ambulance-fare`, `ambulance-near`, `ambulance-place`, `ambulance-request`, `demo-ambulance`.
* **Demo seed part:** ambulance parts of `demo/services.js`.
* **Pitfalls:** fare matching (`ambfare.js`: night surcharge, oxygen/ICU extras, destination rows) is pure — port it with its unit
  cases first; `maps_link_resolve` makes outbound HTTP (timeout, per-uid limit → `RateLimiter`); one active request per patient.

## P6 — Platform, trust & admin (`users`, `notify`, `admin`, `demo`, `jobs`) — 34 actions

* **Node:** `account.js` (schedule/runDue/deleteNow), `phone.js`, `inbox.js`, `devices.js`, `moderation.js`, `sanctions.js`,
  `verify.js`, `listingreview.js`, `sellerstats.js`, `support.js`, `adminweb.js`, `useractivity.js`, `catalog.js`
  (+ `data/catalog-defaults.json`), `demo/index.js`, `admin_*` parts of `api/community.js` (`admin_list/add/remove`, `admin_overview`).
* **Actions:** `delete_account`, `phone_send_code`, `phone_verify_code`, `inbox`, `inbox_read`, `device_register`, `device_unregister`,
  `report`, `admin_reports`, `admin_report_resolve`, `verify_docs_upload`, `admin_verify_docs`, `admin_request_docs`, `ticket_create`,
  `tickets_mine`, `ticket_reply`, `admin_tickets`, `admin_ticket`, `admin_ticket_reply`, `admin_stats`, `admin_user_find`,
  `admin_user`, `admin_user_action`, `admin_log`, `admin_user_section`, `admin_overview`, `admin_list`, `admin_add`, `admin_remove`,
  `catalog_get`, `admin_catalog_set`, `admin_demo_seed`, `admin_demo_wipe`, `admin_demo_status`.
* **First PR (day 1):** the shared contracts above + implementations (Moderation, Sanctions, VerifyDocs, ListingReview,
  ListingStats, AccountDeletionStep/DemoSeedPart interfaces) and the catalog `ActionHook` (Node loads the catalog before every
  request; cache 5 min in Spring Cache `catalog`).
* **Cron:** step 6 (phone limits), 10 (scheduled account deletion — runs every package's `AccountDeletionStep`), 11 (inbox retention).
* **Tables:** `accounts` (delete_* / disabled_reason), `user_state` (phone code, inbox_seen_at), `rate_counters` (phone),
  `device_tokens`, `inbox_items`, `mod_reports`, `sanctions`, `verify_docs`, `tickets`, `ticket_messages`, `admin_log`,
  `admin_emails`, `catalog_items`, `catalog_meta`, `demo_state`, `demo_accounts`, `demo_manifest_rows`, `job_markers`.
* **Depends on:** kernel; for `admin_overview`, `admin_user`, `admin_user_section`, `admin_user_action`, `admin_report_resolve`,
  deletion and demo seeding: services of P1–P5 (merge last).
* **Node tests to port:** `deletion`, `demo`, `guest` (`me`/admin parts), `privacy-gaps` (deletion parts), `demo-health` (tickets,
  inbox, admin_overview, admin_user_section), `demo-services` (reports, verify docs), plus an IT for each admin action
  (none of the Node tests cover `admin_user_action`, `admin_stats`, `admin_log`).
* **Pitfalls:** `admin_*` results with `unchanged: true` are not logged (dispatcher already does this); owner-only actions
  (`admin_list/add/remove`, demo) use `AdminService.isOwner`; `admin_add/remove` must call `AdminService.clearCache()`;
  disabling an account = Firebase `updateUser(disabled)` + `revokeRefreshTokens` + `accounts.disabled_reason`.

---

## After phase 2

1. Run the existing e2e suites (`e2e/`, Maestro Android flows, web e2e) against the Spring backend (BASE_URL switch) and diff
   against the Node server.
2. VPS deploy (Contabo, Ubuntu 24.04): nginx (`X-Forwarded-For`, static `/admin` and `/w`), systemd unit for the boot jar,
   PostgreSQL 16, Redis 7, env file; Sentry DSN; SES domain verification; R2 bucket.
3. Cutover: point the app's `ServerApi.kt` BASE_URL and the web build to the new host; keep Vercel read-only for rollback.
