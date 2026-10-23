# PostgreSQL schema

The Firestore data of the Node server, normalized for PostgreSQL 16. Migrations: `src/main/resources/db/migration/`
(Flyway, applied at startup):

| file | contents |
|---|---|
| `V1__init.sql` | accounts, profiles, per-user server state, quotas, rate counters, login codes, config (admins, catalog, lab-test templates), admin log, inbox, device tokens, caregivers, sanctions, demo manifest, job markers |
| `V2__blood.sql` | blood requests (+ contact, notified set, close/undo marks), responses, request reports, date corrections, emergencies (+ groups, donors), hospitals, hospital requests |
| `V3__providers_appointments.sql` | badges, providers (+ assistants, chambers, chamber times, home-care times, queue stats), appointments, serial counters, queues |
| `V4__listings.sql` | pharmacies, labs (+ ranges, price lists), ambulances (+ fare rows), blood banks, calls/ratings, listing stats, verification documents, medicine finds, pharmacy stock/missed, "call an ambulance" requests (+ targets, quotes, calls, fallback, replaced, patient state, create log) |
| `V5__rx_lab.sql` | doctor's patient register, prescriptions (+ medicines, dispensings), pharmacy orders, lab orders (+ price items, report items, photos), report reviews |
| `V6__shop_books.sql` | shop items, purchases (+ lines, undo), sales (+ lines), adjustments, **daily aggregates** (days, sources, per-item) |
| `V7__content_support.sql` | questions, answers, tips, moderation reports, tickets (+ messages) |
| `V100`–`V601` | package additions (P1–P6) |
| `V602__r2_files.sql` | photos on R2: `stored_objects` (key, size, content type, sha256), `stored_objects_trash` (keys to delete), `user_profiles.photo_key`/`photo_thumb_key`, release triggers on `user_profiles`, `verify_docs`, `lab_report_photos` |

**104 tables, ~200 indexes.** Checked by applying all migrations to PostgreSQL 16.14, and by the Testcontainers tests (Flyway runs
before every test context).

## Conventions

* **Ids are `TEXT`.** Firebase uids are kept as they are. Document ids keep the Firestore format the apps already handle
  (20 random alphanumerics, `common/Ids.newId()`), so ids seen by clients do not change shape. Documents whose Firestore id was a
  composite (`{uid}_{listingId}`, `{patientUid}_{caretakerUid}`, `{requestId}_{uid}`, `{providerUid}_{date}_{key}` …) become
  **composite primary keys**. Where the API exposes such an id, the port rebuilds the string.
* **Time.** Server-generated instants are `TIMESTAMPTZ`; the API layer converts them to epoch milliseconds, because every
  client reads numbers (`createdAt`, `updatedAt`, …). Values that clients *send* as epoch-ms and expect back unchanged stay
  `BIGINT *_ms` (`needed_by_ms`, `needed_at_ms`, `birth_date_ms`, `last_donation_ms`, correction `current_ms`/`requested_ms`,
  queue `advances`). Dhaka calendar days — Node "YYYYMMDD" day keys and the "UTC-midnight of the Dhaka date" millis
  (`appointments.date`, `todayDhakaUtcMidnight`) — are `DATE` (`common/DhakaTime` converts both ways).
* **Enums** are `TEXT` + `CHECK (… IN …)` with the exact strings the clients use (`status`, `kind`, `mode`, `closed_by`, …).
* **JSONB only for free-form blobs:** `inbox_items.data` (push payload map), `*.last_approved` (snapshot of the approved
  listing for change review), `lab_test_templates.params` (admin-edited report form), `sanctions.info`, `demo_manifest_rows.row_key`.
  Everything with a known shape became columns or child tables (chambers, medicines, fare rows, report items, quotes …).
* **Arrays** (`TEXT[]`, `SMALLINT[]`, `INTEGER[]`) for small value lists without identity: specialties, services, types,
  closed days, `days` of a chamber session, tests of a prescription, `verification_checks`, `docs_have`.
* **Money**: whole taka as `INTEGER` where Node kept integers (fares, lab prices, fees); shop books use `NUMERIC(12/14, 2/4)`.
  Free-text money fields the clients show as entered (`fee: "৫০০ টাকা"`, `rx_orders.price`) stay `TEXT`.
* **Geo**: `lat`/`lng DOUBLE PRECISION` + the 10-char geofire geohash (`common/Geo.hash`, byte-identical to geofire-common).
  Radius queries keep the Node algorithm: geohash prefix ranges (`text_pattern_ops` indexes) then exact distance in code.
  `blood_requests` uses a partial index `WHERE status = 'open'` instead of the `geoOpen` field; donors use
  `user_profiles.donor_geo = "group|geohash"` (one range scan per blood group).
* **Foreign keys** point at `accounts(uid)` for every user-owned row. `ON DELETE CASCADE` where Node's account deletion
  deletes the data, `SET NULL` where it keeps the record without the person (`prescriptions.patient_uid`,
  `rx_patients.linked_uid`). App code still runs the Node deletion steps first (it notifies counterparties).
* `accounts` gets a row for every uid that acts (upsert on the first authenticated request — `users/AccountService`), so FKs
  hold even for users who never wrote a profile.

## Firestore → PostgreSQL mapping

| Firestore | table(s) | notes |
|---|---|---|
| Auth user + `serverState/{uid}`.deleteAt, deleteRequestedAt, disabledReason | `accounts` | soft-delete/grace: `delete_requested_at`, `delete_at` (+30 days); `disabled_reason` for the suspended message; `is_demo` generated |
| `users/{uid}` | `user_profiles` | only fields the app writes + server-maintained `geohash`, `donor_geo`, `location_updated_at`, `updated_at` |
| `users.family[]` | `user_family_members` | |
| `users.commitments{}` | `user_commitments` | the donor's "processing" list |
| `users.respondedTo{}`, `serverState.respondedTo{}` | — | derived from `blood_responses.donor_uid` |
| `users.emergencyComing{}` | — | derived from `emergency_donors` |
| `users.fcmTokens[]`, `fcmToken` | `device_tokens` | one row per device token |
| `serverState/{uid}` (inboxSeenAt, verifiedPhone, phoneCode, labHome, pushDay/Count) | `user_state` | |
| `serverState/{uid}.{kind}Day/{kind}Count` (ask, answer, tip, book, …, request, respond, create) | `user_quotas` | one row per (uid, kind); day resets the count |
| `serverState.donationDateLog[]` | `donation_date_log` | |
| `serverState.answerPaths[]` | — | derived from `answers.uid` |
| `serverState/ip_*`, `serverState/_emailGlobalExisting`, `_emailGlobalNew`, `phoneLimits/h_*` | `rate_counters` | `scope` says which |
| `serverState/_adminStats` | — | Redis cache (30 min) in phase 2 |
| `serverState/_demo` {paths, uids, emails, partial, at} | `demo_state`, `demo_accounts`, `demo_manifest_rows` | |
| `emailCodes/{sha256}` {codes[], lastSentAt, sendDay/Count, failDay/Count} | `email_codes`, `email_code_entries` | only HMACs of codes |
| `config/admins.emails[]` | `admin_emails` | owners stay in `ADMIN_EMAILS` |
| `config/catalog.lists` | `catalog_items`, `catalog_meta` | merged with `catalog-defaults.json` at read time |
| `config/labTests.tests[]` | `lab_test_templates` | merged with `labtests-defaults.json` |
| `config/migrations`, `maintenance/*` | `job_markers` | one-off job progress |
| `adminLog/{id}` | `admin_log` | |
| `inbox/{uid_rev_rand}` | `inbox_items` | same id format; 21-day retention |
| `careLinks/{patient}_{caretaker}` | `care_links` | PK (patient_uid, caretaker_uid) |
| `sanctions/{hash}` | `sanctions` | survive deletion by design (no FK) |
| `requests/{id}` | `blood_requests` | `geoOpen` → partial index; `createdAt`/`createdAtMillis` → `created_at` |
| `requests/{id}/private/contact` | `blood_request_contacts` | read only by requester / accepted donors |
| `requests/{id}/meta/notified` | `blood_request_notified` | |
| `requests/{id}/meta/close` | `blood_request_close_marks`, `blood_request_close_mark_donors` | undo for `request_reopen` |
| `requests/{id}/responses/{uid}` | `blood_responses` | PK (request_id, donor_uid) |
| `reports/{requestId}_{uid}` | `blood_request_reports` | |
| `correctionRequests/{id}` | `correction_requests` | |
| `emergencies/{id}` (+ `groups{}`) | `emergencies`, `emergency_groups` | |
| `emergencies/{id}/donors/{uid}` | `emergency_donors` | |
| `hospitals/{id}` | `hospitals` | DGHS directory stays bundled JSON (`hospital-dir.json`) |
| `hospitalRequests/{id}` (+ requesterUids[]) | `hospital_requests`, `hospital_request_requesters` | |
| `badges/{uid}` (+ pendingChange, verification, docsHave) | `badges` | correction = `change_*` columns |
| `providers/{uid}` | `providers` | homeVisit / reportReview objects → `home_*` / `review_*` columns |
| `providers.assistantEmails[]` | `provider_assistants` | |
| `providers.chambers[]` (+ `times[]`) | `provider_chambers`, `provider_chamber_times` | `idx` keeps the order (`chamberIndex`) |
| `providers.homeVisit.times[]` | `provider_home_times` | |
| `providers.queueStats{}` | `provider_queue_stats` | |
| `appointments/{id}` | `appointments` | `date` → `visit_date DATE` |
| `serials/{uid_date_key}` | `serial_counters` | serial numbers assigned in the confirm transaction |
| `queues/{uid_date_key}` | `queues` | |
| `pharmacies/{uid}` (+ online{}, aspects{}) | `pharmacies` | aspects = sums/counts per aspect |
| `labs/{uid}` (+ ranges{}) | `labs`, `lab_ranges` | |
| `labPrices/{uid}` (+ items[]) | `lab_prices`, `lab_price_items` | |
| `ambulances/{id}` (+ fareChart{rows[]}) | `ambulances`, `ambulance_fare_rows` | |
| `bloodBanks/{id}` | `blood_banks` | `owner_uid` NULL = added by admin |
| `ambulanceCalls/{uid_id}` | `ambulance_calls` | ratings, overcharge, agreed fare |
| `pharmacyCalls/{uid_id}` | `pharmacy_calls` | |
| `pharmacyRatings/{uid_id}` | `pharmacy_ratings` | |
| `bloodBankCalls/{uid_id}` | `blood_bank_calls` | |
| `homeCalls/{uid_provider}` | `home_calls` | |
| `ambulanceStats`, `pharmacyStats`, `bloodBankStats`, `homeStats` {total, callers, d{}, recent[]} | `listing_stats`, `listing_stats_daily`, `listing_recent_ratings` | `kind` column |
| `verifyDocs/{kind_id_type}` | `verify_docs` | `data` base64, or `storage_key` (R2, V602) when R2 is configured |
| `modReports/{id}` | `mod_reports` | UNIQUE (target_path, reporter_uid) |
| `medFinds/{id}` (+ notified[], answers{}) | `med_finds`, `med_find_targets` | `qty`/`unit`, `have_qty`/`have_unit`/`dismissed_at` (V692, MEDFIND.md) |
| `pharmacyStock/{uid}` {names, keys, prices, mrp} | `pharmacy_stock`, `pharmacy_stock_items` | one row per product key |
| `pharmacyMissed/{uid}.items{}` | `pharmacy_missed` | |
| `ambulanceRequests/{id}` | `ambulance_requests` | maps → child tables below |
| … `sentTo{}`, `activeOwners[]`, `declined[]`, `lapsed[]`, `est{}`, `km{}` | `ambreq_targets` | one row per driver |
| … `quotes{}` | `ambreq_quotes` | |
| … `calledIds[]`, `calledInfo{}` | `ambreq_calls` | |
| … `fallback[]`, `replaced[]` | `ambreq_fallback`, `ambreq_replaced` | |
| `ambReqLimits/{uid}` {activeId, at, times[]} | `ambreq_patient_state`, `ambreq_create_log` | |
| `rxPatients/{id}` | `rx_patients` | |
| `prescriptions/{id}` (+ medicines[], dispensed[], doctor{}, patient{}, findings{}) | `prescriptions`, `prescription_medicines`, `prescription_dispensings`, `prescription_dispensed_items` | `code` UNIQUE |
| `rxOrders/{id}` | `rx_orders` | contact purge columns |
| `labOrders/{id}` (+ home{}, prices{}, report{}) | `lab_orders`, `lab_order_price_items`, `lab_report_items`, `lab_report_photos` | |
| `rxReviews/{labOrderId}` | `rx_reviews` | |
| `shopItems/{uid}/items/{id}` | `shop_items` | UNIQUE (shop_uid, key) |
| `shopBooks/{uid}/purchases/{id}` (+ lines[], undo{}) | `shop_purchases`, `shop_purchase_lines`, `shop_purchase_undo` | |
| `shopBooks/{uid}/sales/{id}` (+ lines[], totals{}, rx{}) | `shop_sales`, `shop_sale_lines` | UNIQUE (shop_uid, rx_code, rx_at): one sale per dispensing |
| `shopBooks/{uid}/adjust/{id}` | `shop_adjustments` | |
| `shopBooks/{uid}/days/{YYYYMMDD}` (+ src{}, items{}) | `shop_days`, `shop_day_sources`, `shop_day_items` | see below |
| `questions/{id}` | `questions` | |
| `questions/{id}/answers/{id}` | `answers` | |
| `tips/{id}` | `tips` | |
| `tickets/{id}` (+ messages[]) | `tickets`, `ticket_messages` | |

**Dropped:** `medOrders`, `medOrderPhotos`, `homeVisits` (legacy features; nothing writes them any more — only account deletion
and moderation still mention them; there is no data to migrate), `config/migrations` + `maintenance/*` one-off backfills
(no legacy data in PostgreSQL), the `demo: true` marker on every seeded document (replaced by `is_demo` / the demo manifest).

## Listing columns

`pharmacies`, `labs`, `ambulances`, `blood_banks` repeat the same review/verification columns (the Node `listingreview.js`
flow): `status` (`pending|approved|suspended|rejected`), `status_reason`, `approved_name` + `last_approved` (snapshot used to
show "what changed" and to re-verify on key changes), `verified_by`, `verified_by_email`, `verified_at`,
`verification_checks[]`, `docs_have[]`, `docs_sent_at`, `docs_requested_at`, `docs_request_note`, `reviewed_at`, `reviewed_by`,
`hidden_for_deletion` (status before an account-deletion hide), `edit_rejected_at`/`edit_rejected_reason`. `badges` has the same
verification/docs columns. Phase 2 can share one Kotlin `ListingReview` service over the four tables.

## Demo separation (`sameWorld`)

Demo accounts have uids starting with `demo-`. `accounts.is_demo` and an `is_demo` **generated column** on every table whose
Node code calls `demo.sameWorld()` (blood_requests, appointments, providers, pharmacies, labs, ambulances, blood_banks,
med_finds, ambulance_requests, prescriptions, rx_patients, rx_orders, lab_orders) let queries filter by world in SQL
(`WHERE is_demo = :callerIsDemo`). `emergencies.is_demo` is a plain column (seeded demo calls are created by an admin uid and
must be hidden from guests). Seeding/wiping (phase 2) uses `demo_state`, `demo_accounts` and `demo_manifest_rows` (rows that are
not owned by a demo account); wiping deletes demo accounts through the normal account-deletion path, like Node.

## Account deletion (30-day grace)

`accounts.delete_requested_at`/`delete_at` mark a pending deletion (Node: serverState.deleteAt). While pending, listings are
hidden (`status = 'suspended'`, previous status in `hidden_for_deletion`), the provider is suspended
(`providers.admin_suspended`, `suspend_reason = 'deletion'`, `listed_before_suspend`), device tokens are removed and refresh
tokens revoked. Logging in again cancels (phase 1: `users/DeletionService.cancel`). The daily job deletes due accounts
(`accounts_delete_at_idx`): app code notifies counterparties, then deleting the `accounts` row cascades.

## Shop books daily aggregates

Every purchase/sale/adjustment write also updates `shop_days` (+ `shop_day_sources`, `shop_day_items`) in the **same
transaction** — exactly what `shopbooks.js` does with `days/{YYYYMMDD}` — so `books_today` and `books_report` read only the
aggregates (no scan of sales). Deleting a sale/purchase reverses its contribution. `nc` = net of lines with a known cost (base
for profit margin); `woq` = written-off quantity; item keys `k<key>` are sales, `x<key>` write-offs (Node keys kept).

## Indexes (the queries the Node code runs)

Each `CREATE INDEX` in the migrations is next to its table with the query it serves. The important ones:
open-request geohash ranges (`blood_requests_open_geo_idx`), donor search (`user_profiles_donor_geo_idx`),
listings by area/status (`*_area_idx`), "mine" lookups by owner (`*_owner_idx`, `*_uid_idx`), appointments by
patient/provider/status/date, inbox by uid + time, cron scans (`accounts_delete_at_idx`, `ambulances_remind_idx`,
`ambulance_requests_purge_idx`, `rx_orders_purge_idx`, `prescriptions_follow_up_idx`, `prescriptions_refill_idx`,
`inbox_items_created_idx`, `med_finds_cleanup_idx`, `correction_requests_pending_idx`), moderation by target
(`mod_reports_open_idx`), assistants by email (`provider_assistants_email_idx`).

## Not in PostgreSQL

* **Redis**: rate-limit counters (`rl:*`, Lua INCR), guest response cache (`guest:*`), Spring caches (`admins`, `catalog`, …).
* **JobRunr** creates its own `jobrunr_*` tables (recurring daily job, future delayed jobs).
* **Cloudflare R2** (V602, only when the `R2_*` env vars are set — [deploy/R2.md](../../deploy/R2.md)): profile photos/thumbs,
  verification documents and lab report photos. The rows keep the key (`photo_key`, `photo_thumb_key`, `storage_key`) and
  `stored_objects` the size, content type and sha256; the API still sends and receives the same base64 text. A key that leaves a
  row (deleted, cascaded, replaced) goes to `stored_objects_trash` by trigger, and the `storage-trash` job deletes the object.
  Old base64 rows move with `POST /api/cron/storage/migrate`.
* **Bundled JSON** (unchanged from Node): districts/upazilas/unions (`geo.json`, `districts.js`), DGHS hospital directory
  (`hospital-dir.json`), drug list (`drugs.json`), catalog and lab-test defaults.
