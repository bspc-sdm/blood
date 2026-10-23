# Invoices (রসিদ) + Windows desktop app: security and abuse review

Review of the owner-approved invoices CONTRACT (23 Oct 2026, mirrored in `backend/docs/INVOICES.md` on `spring-p16`)
against the code already on `main` (books, ambulance fare chart, care links, assistants, demo world, auth). When this
was written, `spring-p16`, `spring-p17`, `desktop-app`, `web-invoices` and `app-invoices` had no implementation yet, so
this reviews the design rather than the code. This is a docs-only review. No code was changed, and nothing here has
been proven by a test yet. Each item gives a concrete fix and the test that should prove it. Manual tests are in
[manual/18-invoices-desktop.md](manual/18-invoices-desktop.md).

**সংক্ষেপে (মালিকের জন্য):** ১৫টা বিষয়: 🔴 Critical ১টা, 🟠 High ৮টা, 🟡 Medium ৪টা, 🟢 Low ১টা, আর ১টা আইন/ট্যাক্সের নোট।
সবচেয়ে বড় ঝুঁকিগুলো: (১) ল্যাপটপ চুরি হলে "কম্পিউটার বাতিল" শুধু অফলাইন সিঙ্ক বন্ধ করে — চোর তবুও সেই কম্পিউটার থেকে মালিকের পুরো অনলাইন অ্যাকাউন্টে
ঢুকতে পারে (I4, Critical); (২) দোকান যেকোনো ফোন নম্বর লিখলে রসিদ যদি সেই নম্বরের রোগীর "আমার রসিদ"-এ চলে যায়, তাহলে যে কেউ অন্যের নামে ওষুধ বা পরীক্ষার
রেকর্ড বসাতে পারবে (I3); (৩) পুরোনো "বিক্রি মুছুন" বোতাম, কর্মচারীর বাতিল আর স্টক "নষ্ট" দেখিয়ে আয় লুকানো যায় (I2, I7); (৪) অফলাইন বিক্রির নম্বর আর
সময় কম্পিউটারই ঠিক করে, সার্ভার তা যাচাই করে না (I5); (৫) চালক চার্ট আগে বাড়িয়ে বা "অন্যান্য" খরচ দেখিয়ে বেশি ভাড়ার সতর্কতা এড়াতে পারেন (I8);
(৬) ডেমো দোকানের রসিদও যাচাই পাতায় "বৈধ" দেখাবে (I9)। ছোট ফার্মেসির ভ্যাট (মূসক) চালান লাগবে কি না সেটা নিশ্চিত নয় — I15-এ যা জানা গেছে আর যা যাচাই
করতে হবে, দুটোই লেখা।

Severity scale: as in [06-phases-and-exit-criteria.md](06-phases-and-exit-criteria.md#তীব্রতা-severity). 🔴 Critical,
🟠 High, 🟡 Medium, 🟢 Low. Critical and High items block the desktop installer from being published at
`lifolo.com/download/` and block invoices from being turned on in production.

## Summary

| # | Topic | Severity |
|---|---|---|
| I1 | Fake receipts: edited paper, look-alike QR domains, offline receipts that do not verify yet | 🟠 High |
| I2 | Hiding income: the legacy `books_sale_delete`, voids, stock write-offs, unreceipted sales | 🟠 High |
| I3 | Linking a receipt to a patient by phone number alone: fake medical records, spam, account enumeration | 🟠 High |
| I4 | Lost or stolen laptop: `desktop_revoke` stops sync but not the persisted Firebase session; the offline DB is plaintext | 🔴 Critical |
| I5 | Forged offline ops: no device secret, device-chosen `no` and `at`, cross-business references, replay | 🟠 High |
| I6 | Customer phone numbers: printed, cached on desktops forever, searchable after masking | 🟡 Medium |
| I7 | Owner vs assistant and staff: who may void, discount, register or revoke devices; pharmacies share the owner login | 🟠 High |
| I8 | Ambulance overcharge workarounds: edit the chart first, move money into extras, under-state the receipt | 🟠 High |
| I9 | Demo and real: demo receipts verify as "valid"; demo devices; demo customer phones | 🟠 High |
| I10 | Sanctioned or deactivated businesses keep issuing receipts offline for up to 7 days | 🟡 Medium |
| I11 | Offline total ≠ server-recomputed total: the printed paper disagrees with the verify page | 🟡 Medium |
| I12 | The 7-day offline limit and op times rely on the PC clock | 🟡 Medium |
| I13 | Desktop supply chain: installer signing, updater key, Tauri IPC exposed to remote pages | 🟠 High |
| I14 | Verify-code guessing and what the verify page reveals | 🟢 Low |
| I15 | Bangladesh tax and legal notes (VAT Mushak invoice, retention, privacy) | ℹ️ Info (needs an expert) |

---

## I1: Fake receipts and the verify page (High)

**What the contract allows.** A receipt is paper with a QR to `lifolo.com/v/<verifyCode>`, and the page shows business,
kind, no, date, total, paid/due and status. That is a good base, but:
* Paper is trivially editable. A forger keeps a real QR and changes the total or the lines (for insurance claims,
  office reimbursement, or to show a patient a higher bill). The page only protects people who scan **and compare**.
* A forger can print a QR to a look-alike domain (`lifolo-bd.com/v/…`, `lifolo.co/v/…`) that always says "valid".
* Desktop receipts are printed before the server has seen them. Until sync, a genuine offline receipt shows
  "not found", which teaches people to ignore "not found", exactly what a forger needs.
* The Lifolo logo on every receipt lends the brand to any business, including ones whose listing is later sanctioned.

**Fix.**
1. Verify page: show `no`, date and **total in large type** with "কাগজের সাথে নম্বর, তারিখ আর মোট মিলিয়ে নিন"; show the
   number of lines (not their names) so an added line is detectable; show "বাতিল" with the void date in red.
2. Print the plain domain under the QR exactly as `lifolo.com/v/<code>` and say on `lifolo.com/v` (no code) "শুধু
   lifolo.com ঠিকানাই আসল". Register obvious look-alike domains if cheap.
3. Offline receipts carry a printed line "অফলাইন রসিদ — ইন্টারনেট এলে যাচাই হবে". For an unknown code the page says
   "পাওয়া যায়নি। দোকানে ইন্টারনেট না থাকলে কিছুক্ষণ পরে আবার দেখুন; ২৪ ঘণ্টা পরেও না পেলে রসিদটা সন্দেহজনক।" Desktops sync
   in the background every minute while online, so the window is short.
4. A void, a sanction or account deletion never makes a code "not found": the page keeps answering with status
   (see I2, I10).

**Test.** `InvoiceVerifyIT`: unknown code → 404 body without data; void → `status:"void"`; deleted business →
still answers. Manual: M-INV-23, M-INV-24, M-INV-26, M-DSK-09.

## I2: Editing or deleting sales to hide income (High)

**What exists today.** `books_sale_delete` deletes a sale and returns stock ("এই বিক্রি মুছবেন?" in web-vue). The
contract says invoices are immutable and have no delete, and that `invoice_void` reverses stock "the same way
`books_sale_delete` does", but it does not say what happens to `books_sale_delete` itself. Other ways to hide income:
* Void after the customer has left, with any reason. On the desktop this can be done offline, before the server
  ever sees the sale as issued.
* Sell without a receipt, then write the stock off with `books_adjust` / the offline `adjust` op ("damaged",
  "expired").
* Heavy line or bill discounts, or a price typed lower than the item price.
* Simply never issuing a receipt. Software cannot stop that; stock reconciliation is the only check.

**Fix.**
1. `books_sale_delete` on a sale that has an invoice returns 409 `invoice_exists` ("রসিদ দেওয়া বিক্রি মোছা যায় না —
   বাতিল করুন"). The web and app replace the delete button with "বাতিল (কারণসহ)". Sales from before invoices existed
   may still be deleted, but every delete writes to the audit log.
2. Void needs a reason from a fixed list plus free text ("ভুল পণ্য", "ক্রেতা ফেরত দিয়েছেন", "ভুল দাম", "অন্যান্য").
   A void more than 24 h after issue, or by a non-owner, notifies the owner (push + desktop tray).
3. A daily **"দিনের শেষ" summary** for the owner, per device and per person: receipts, voids (with reasons), discounts
   above 10%, prices below the item price, stock write-offs, and stock-short warnings. It is sent as a push at closing
   time and shown in the books report.
4. The offline `void` op keeps both events: the server stores the invoice as issued **and** voided, with the device's
   times, so an offline void is never invisible.
5. Write-offs (`adjust` with a loss) above a threshold, or more than N per day, are flagged in the same summary.

**Test.** `BooksInvoiceIT`: `books_sale_delete` on an invoiced sale → 409; void → stock restored, audit row, summary
counts it. Manual: M-INV-27, M-INV-28, M-INV-31, M-DSK-28.

## I3: Linking a receipt to a patient by phone number alone (High)

**What the contract leaves open.** The invoice has `patientUid?` and `customer {name?, phone?}`. When the invoice
links to a patient, the patient gets an `invoice_new` push and the invoice appears in `my_invoices` / `my_expenses`.
The contract does not say **how** a counter sale gets a `patientUid`. If an agent implements the obvious "look up the
account by the typed phone number":
* Any approved business, including a demo or rogue one, can put fake pharmacy, psychiatric or HIV-test receipts into
  a stranger's "আমার রসিদ" and push them a notification. For a health app that is a privacy and harassment problem.
* The response (a linked patient name, or a different code path) tells the business whether a number has a Lifolo
  account: account enumeration.
* Caregivers then see those fake records too.

**Fix.**
1. Set `patientUid` **only** from a relation the patient created: rx order, lab order, appointment, ambulance
   request, or a prescription code the patient handed over at the counter (`books_rx_preview`). Never by phone match.
2. Optional, later: the patient can claim a receipt by scanning its QR while logged in ("এটা আমার রসিদ"), which links
   it after their own action.
3. The `books_sale_add` / `lab_bill_create` / `chamber_fee_collect` responses never reveal whether a phone belongs to
   an account.
4. The patient can hide a receipt from "আমার রসিদ" and caregivers ("লুকান") without deleting it from the business.

**Test.** `InvoiceLinkIT`: a counter sale with a registered user's phone → `patientUid` null, no push, identical
response shape. Manual: M-INV-35, M-INV-44.

## I4: Lost or stolen laptop with offline data (Critical)

**What the contract allows.** "The Firebase session persists in WebView2." "A revoked device stops syncing at once
(403 `device_revoked`)." The desktop loads the whole website. So:
* `desktop_revoke` only blocks `offline_sync`. The WebView2 profile still holds the owner's Firebase refresh token,
  so a thief can use **every online feature**: patient orders and prescriptions, books, settings, devices, account
  deletion. The backend already verifies ID tokens with `checkRevoked = true` (`AuthService.kt`), but nothing calls
  `revokeRefreshTokens` on device revoke. The advertised remedy does not remove access, hence Critical.
* The SQLite file holds item stock, customer names and phones, and for chambers patient names for 7 days ahead.
  If it is not encrypted, anyone who removes the disk or logs in as another Windows user can copy it.
* The PIN is 4–6 digits. If its hash sits in the same SQLite file, it can be brute-forced offline in seconds. The PIN
  is only a shoulder-surfing lock; it is not disk protection.
* A revoked device that stays offline keeps working for up to 7 days.

**Fix.**
1. `desktop_revoke` also calls `identity.revokeRefreshTokens(ownerUid)` and records `revokedAt`. Every session of the
   owner then ends (phone and web included, which is the honest message: "সব জায়গা থেকে লগআউট হবে, ফোনে আবার
   লগইন করুন"). Do the same for an assistant whose device is revoked. Show this on the revoke confirm.
2. Better, in the next step: bind desktop sessions to the device. At `desktop_register`, issue a device secret (see
   I5) and require `X-Device-Id` plus an HMAC on every desktop API call. A revoked device then fails online at once
   without logging the owner out elsewhere.
3. Encrypt the SQLite DB (SQLCipher or an equivalent) with a random key stored with Windows DPAPI
   (`CryptProtectData`, current-user scope). On `device_revoked`, or on 10 wrong PINs in a row, wipe the key and the
   DB. Pending ops that are not synced yet are lost then, so say so in the warning.
4. Store the PIN as a slow hash (PBKDF2/Argon2, per-device salt). After 5 wrong attempts, add a growing delay.
5. Data minimisation: the chamber snapshot carries patient first name + serial only, no phone. The pharmacy snapshot
   carries no customer data at all. Purge local invoices older than 30 days once synced.
6. Setup screen and help: "কম্পিউটারে Windows পাসওয়ার্ড দিন; সম্ভব হলে BitLocker/Device encryption চালু করুন". The app
   warns if the Windows account has no password (where detectable).
7. The device list shows "last seen" and "last sync" for each device, so the owner notices a lost one.

**Test.** `DesktopRevokeIT`: after revoke, an old ID token gets 401 on `books_items`. Manual: M-DSK-20, M-DSK-21,
M-DSK-03, M-DSK-04.

## I5: Forged offline ops, replay, and device series spoofing (High)

**What the contract allows.** `offline_sync {deviceId, ops}` with the user's ID token. "The device assigns `no`
within its own series, and the server accepts it." Ops up to 30 days old are accepted. `opId` makes retries
idempotent. Gaps:
* **No device credential.** `deviceId` is an identifier, not a secret. Any session of the business owner (a phone, a
  stolen token, a script) can post ops as `D1` or `D2`.
* **Series spoofing.** If the server trusts the `no` in the op, `D1` can submit `D2-000040` or `W-000099` and collide
  with or pre-empt someone else's number. Restoring an old SQLite backup re-issues numbers that were already used.
* **Backdating.** `at` comes from the device. Sales can be moved into last month (after the books were "closed") or
  outside opening hours, up to 30 days back, which is longer than the 7-day offline limit.
* **Cross-business references.** `pay`, `void`, `checkin` and `lab_bill` ops carry ids (`invoiceId`, `appointmentId`,
  `labOrderId`). Each must belong to the device's `bizId`; otherwise this is an IDOR through the sync path, which
  bypasses the online action checks.
* **Replay.** A replay with the same `opId` is harmless. If `opId` uniqueness is global rather than per device,
  device B sending A's `opId` receives A's result (id, no): a small leak. A captured op with a fresh `opId` is a new
  sale; that needs the device secret to prevent it.
* **Kind mismatch.** A pharmacy device sending `fee` or `lab_bill` ops.

**Fix.**
1. `desktop_register` returns a 32-byte `deviceSecret`, stored with DPAPI. Each `offline_sync` request carries
   `HMAC-SHA256(deviceSecret, deviceId|batchHash|ts)`. The server rejects a bad signature or `ts` skew > 10 min.
   Revoke deletes the secret.
2. The server derives the series from `deviceId`. It rejects a `no` whose series does not match (`series_mismatch`)
   and enforces `UNIQUE(kind, biz_id, no)`. On a duplicate with a different `opId`, it accepts the op, assigns the
   next free number with a suffix (`D1-000040-b`), returns the new `no` with `warn:"renumbered"`, and puts it in the
   owner's summary.
3. Accept `at` only within `[device.lastSyncServerTime − 10 min, now + 10 min]`. Anything outside is still accepted
   (it is real money), but stored with `issuedAt = receivedAt`, the device time kept as `deviceAt`, and
   `warn:"clock"` (see I12). Reject ops older than **8 days** (the 7-day limit plus a margin) rather than 30 days.
4. Every op is checked as if it were the online action: owner/assistant of `bizId`, kind matches the device, and
   referenced ids belong to `bizId` and are not void. One shared validator serves both paths, so they cannot drift.
5. Make `opId` unique per `(deviceId, opId)` and return stored results only to that device.
6. `pay` ops cannot exceed the due amount as the server sees it. The surplus is rejected with `error:"overpay"`.

**Test.** `OfflineSyncIT`: wrong HMAC → 401; series mismatch → rejected; duplicate `no` → renumbered; foreign
`invoiceId` in `pay` → error; `at` 20 days old → rejected; same op twice → same result. Manual: M-DSK-11, M-DSK-13,
M-DSK-15, M-DSK-18.

## I6: Privacy of customer phone numbers (Medium)

The contract promises that the phone is optional, never on the verify page, and masked in business lists after 30
days. Remaining gaps:
* The receipt layout does not say whether the customer phone is **printed**. A thermal slip left on the counter or
  shared as a photo then leaks it.
* The desktop keeps invoices in SQLite indefinitely, unmasked (see I4.5).
* `invoice_list_biz {q}` can still find an old receipt by the full number (a confirmation oracle) even if the number
  is masked in the result.
* The 6-year retention "with personal fields removed" must remove name and phone, but also the free-text `ref` and
  `voidReason`, which may contain names.

**Fix.** Never print the customer phone; print the name only if given. Apply the same 30-day mask on the desktop and
purge it locally. After 30 days, `q` does not match phone digits. On deletion, null `customer`, `payments[].ref` and
`voidReason`, and keep amounts, dates and the business. Write the retention purpose into the privacy policy (I15).

**Test.** IT: an invoice older than 30 days does not match `q` by phone; the phone is masked in the list. Manual:
M-INV-39.

## I7: An assistant vs owner permissions (High)

**What the contract allows.** For chambers, the doctor **or assistant** issues receipts, and `invoice_pay` /
`invoice_void` are "issuer only". Pharmacies and labs have no staff role at all, so in practice the shop shares the
**owner's login** with counter staff on the desktop.
* An assistant can collect ৳500, record ৳300 or void the receipt, and the doctor only learns of it by reading lists.
* "Issuer only" is ambiguous. Is the issuer the business (doctor) or the person (assistant)? If it means the person,
  the doctor cannot void an assistant's mistaken receipt. If it means the business, any assistant can void the
  doctor's receipts.
* With a shared owner login, staff can also register and revoke devices, change prices, delete the account, and read
  online patient orders.
* The desktop has one PIN, so everyone at the counter knows it.

**Fix.**
1. Define the matrix in `INVOICES.md`. **Issue + collect due:** owner/doctor and assistants. **Void:**
   owner/doctor any; an assistant only their own receipts, within 2 hours, and the doctor is notified with the
   reason. **Fee amount:** an assistant cannot go below the fee for the visit type without a doctor-set discount;
   deviations show in the daily summary. **`desktop_register` / `desktop_revoke` / price lists / fee chart:** owner
   only.
2. Every invoice stores `issuedBy` (the real person, also when `actingBy` applies) and the receipt list shows it.
3. Pharmacy/lab "staff" (counter) accounts, added by verified email like doctor assistants, so owners stop sharing
   their login. Until then, the desktop gets two PIN levels: a counter PIN for selling and an owner PIN for void,
   discount above 10%, price change, stock adjust and settings.

**Test.** `InvoicePermsIT`: assistant voids the doctor's receipt → 403; their own within 2 h → ok + notify; assistant
`desktop_register` → 403. Manual: M-INV-11, M-INV-13, M-INV-31, M-INV-42.

## I8: Ambulance overcharge workaround (High)

**What the contract allows.** `ambreq_finish {fare, extras?:[{label, amount}], method}` sets `overFare` "when the fare
is above the fare chart". The fare chart is owner-editable at any time (`AmbFare`, `ambulance_fare_rows`), and the
app already records an **agreed fare** for in-app trips (`ambulance_calls.agreed_fare`, plus the `overcharged`
rating). Workarounds:
* Raise the chart row just before finishing. The fare is then "not above the chart".
* Keep `fare` at the chart and put the rest into free-text extras ("অপেক্ষা ১৫০০", "টোল", "হেল্পার").
* Take ৳5000 cash and write ৳3000 on the receipt. The patient's "আমার খরচ" is then wrong, and the receipt "proves"
  a fair fare.
* Never call `ambreq_finish`, so there is no receipt and no flag.

**Fix.**
1. Compare against a **snapshot taken when the patient agreed**: the agreed fare if there was one, else the chart
   row and extras at agree time. Store the snapshot on the request; never read the live chart at finish.
2. `overFare = fare + Σextras > snapshot fare + snapshot extras actually used`. Extras are limited to the chart's
   `oxygen` / `icu` / `night`, plus at most one "অন্যান্য" capped at ৳500 that is shown separately to the patient.
3. The patient's push says "ভাড়া ৳3500 — রাজি হয়েছিলেন ৳2000-এ" when over, with one tap to "বেশি নিয়েছে" (the existing
   `overcharged` rating) and "রসিদের চেয়ে বেশি দিয়েছি" for the under-stated case. Both feed the listing's overcharge
   count and admin review.
4. If a trip is `arrived` but not finished after 6 h, remind the driver; the patient can request the receipt.

**Test.** `AmbFinishIT`: edit the chart after agree → still `overFare`; extras beyond the chart → `overFare`; a fare
equal to the agreed fare → no flag. Manual: M-INV-14, M-INV-15, M-INV-16.

## I9: Demo and real mixing (High)

**What the contract allows.** "Demo and real accounts never mix" (`DemoWorld.sameWorld`). But demo accounts are
public: anyone can log in as "ডেমো লাইফ ফার্মেসি". A demo receipt has a real `verifyCode`, and the public page would say
"valid" under a business name. If demo profiles can be renamed (check), a scammer gets a free "Lifolo-verified"
receipt generator for any name. Demo devices could also register as desktops and fill real tables.

**Fix.**
1. Demo invoices: the verify page shows a large "ডেমো — আসল রসিদ নয়" and never "বৈধ". Demo `verifyCode`s start with
   a fixed prefix (for example `DEMO`) so support can tell at a glance. The printed receipt carries a "ডেমো" watermark
   on every print path, thermal included.
2. Demo businesses cannot change their name, address or phone (or the change only affects the demo session).
3. Demo `customer.phone` is ignored for linking and notifications (also covered by I3.1).
4. `desktop_register` for a demo business is allowed (it is useful for showing the app) but capped at 1 device,
   wiped nightly with the demo reset, and its ops never touch real stock or numbering.
5. Real patients never see demo receipts in `my_invoices`, and the reverse.

**Test.** `DemoInvoiceIT`: demo invoice verify → `demo:true`, no "valid"; demo rename → 403; a demo sale with a real
user's phone → no link. Manual: M-INV-44.

## I10: Sanctioned or deactivated businesses offline (Medium)

Sanctioned businesses cannot issue receipts, and deactivated accounts get 423. A desktop that is offline does not
know this and keeps printing receipts for up to 7 days.

**Fix.** On sanction or deactivation, mark all devices `suspended`. Their next sync returns 423 or 403 and the app
locks. Ops from the offline window are **accepted for the record** (customers paid real money) but tagged
`afterSanction`, and the verify page for them and for any receipt of a sanctioned business says
"প্রতিষ্ঠানটি এখন Lifolo-তে স্থগিত". The 7-day window shrinks to 24 h for a business with an open moderation case.

**Test.** IT: sanction → sync 403, earlier ops stored with a tag. Manual: M-INV-40, M-INV-41.

## I11: Offline totals vs the server's recomputed totals (Medium)

"The server recomputes all totals." The desktop prints a receipt with its own totals before the server sees it. If
rounding (2 decimals, per-line vs per-bill discount allocation) or a price changed meanwhile makes the server compute
a different total, the paper and the verify page disagree. That looks like forgery (I1), and the books no longer
match the cash drawer.

**Fix.** For offline ops, the server **keeps the device's line prices and totals** (the customer paid them). It
recomputes only to validate them, and on a mismatch above ৳0.01 it stores the device values with
`warn:"total_mismatch"` for the owner's summary. Share one money module (TypeScript, used by web and desktop) with
the same rounding as `BooksMath` on the server, plus golden tests that run the same cases on both.

**Test.** A shared fixture of 50 bills (discounts, fractions) gives identical totals in the Kotlin and TS tests.
Manual: M-DSK-08, M-DSK-10.

## I12: The 7-day limit and op times rely on the PC clock (Medium)

Offline use is "up to 7 days since the last online check", measured on the Windows clock. Setting the clock back
extends offline use forever and backdates sales (I5.3).

**Fix.** Store `lastOnlineServerTime` and the highest clock value ever seen (`maxSeen`). If the clock goes back by
more than 10 minutes below `maxSeen`, lock with "কম্পিউটারের সময় ঠিক করুন". Count the 7 days from
`lastOnlineServerTime` using `max(now, maxSeen)`. Show a warning at 6 days.

**Test.** Unit test on the offline engine with a fake clock. Manual: M-DSK-17, M-DSK-18, M-DSK-19.

## I13: Desktop supply chain: installer, updater, Tauri IPC (High)

* **Unsigned installer.** SmartScreen shows "Unknown publisher", and users learn to click "Run anyway", which is
  also how fake "Lifolo" installers spread.
* **Updater key.** The Tauri updater trusts whoever holds the update-signing private key. If it sits in CI secrets
  next to everything else, or on a laptop, a leak means a malicious update to every pharmacy PC.
* **IPC exposure.** The WebView loads the bundled web-vue, but the site links out (bKash, maps, `lifolo.com` pages,
  Firebase auth popups). If navigation to a remote origin keeps the Tauri IPC bridge, a remote page (or an XSS)
  can call the SQLite or print commands.

**Fix.**
1. Sign the installer and binaries with an OV or EV Authenticode certificate in the company's name. Publish the
   SHA-256 next to the download, and say in the app and on the site "শুধু lifolo.com/download থেকে নামান".
2. Generate the updater key offline and keep it only in a protected CI environment that requires manual approval
   for release jobs. Updates are served over HTTPS from `lifolo.com` only, and the updater rejects downgrades.
3. Tauri 2 capabilities: allow the IPC commands only for the bundled origin (`tauri://localhost`). Open every
   external link in the system browser (`shell.open`) rather than in the WebView. Use a strict CSP (no `unsafe-eval`,
   `connect-src` limited to `lifolo.com` and Firebase). Expose no generic SQL command, only the typed `OfflineStore`
   operations.
4. Print commands accept an invoice id, never raw ESC/POS bytes from the page.

**Test.** Manual: M-DSK-01, M-DSK-25. Code review checklist on `desktop/src-tauri/capabilities/*.json`.

## I14: Verify-code guessing and what the page reveals (Low)

10 random characters (≥ 50 bits with a 32-symbol alphabet) plus a guest rate limit make guessing impractical, as long
as the code comes from a CSPRNG (on the desktop too: `crypto.getRandomValues`, never `Math.random`) and the server
rejects a device-supplied code that collides or has the wrong format. The page reveals the business, kind and amount
to whoever holds the paper. For a chamber receipt that implies "this person saw Dr X". That is acceptable (the holder
has the receipt) but keep it to the listed fields, add `noindex` and `Cache-Control: private`, and never put the code
in analytics URLs.

**Test.** Manual: M-INV-24, M-INV-25.

## I15: Bangladesh tax and legal notes (Info; needs a VAT consultant)

This is research for the owner, not legal advice. Thresholds have changed several times since 2019, and I could not
confirm the current values from an official NBR source.

* **A Lifolo receipt is a payment receipt, not a VAT invoice.** Under the VAT and Supplementary Duty Act 2012
  (মূসক ও সম্পূরক শুল্ক আইন, ২০১২), VAT-registered businesses issue the tax invoice **Mushak-6.3** (কর চালানপত্র).
  Lifolo should not look like one. Never print "VAT", "মূসক", "চালানপত্র" or a VAT amount line. Add a small footer:
  "টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়".
* **Do small pharmacies need Mushak-6.3?** Probably not, but this is uncertain.
  * Registration thresholds: a January 2025 ordinance reportedly lowered the VAT registration threshold from
    ৳3 crore to ৳50 lakh annual turnover, with turnover-tax enlistment for ৳30–50 lakh and below ৳30 lakh exempt
    (DFDL, TBS, January 2025; bdnews24, 2026). A shop that is not VAT-registered does not issue Mushak-6.3.
    **Uncertain:** later budgets may have changed these figures.
  * Medicines: since 1 July 2019, NBR has said VAT on medicines is collected at the manufacturing stage on the
    government-set retail price, and consumers pay no additional VAT at retail (TBS, July 2019). One 2026 practitioner
    page instead says a 2.4% truncated rate applies to medicine traders and retail pharmacies, with Mushak-6.3 needed
    for B2B sales. **Uncertain and conflicting.** A pharmacy above the threshold should ask its VAT circle or a
    consultant. Where NBR has required EFD/SDC (electronic fiscal devices) for certain retailers, Lifolo is not one.
  * Doctors, diagnostics, ambulances: health services are commonly treated as VAT-exempt. **Not verified** against
    the current First Schedule. Doctors' fees matter mainly for income tax, and the receipts help the doctor's own
    records.
* **What Lifolo should do now.** No VAT fields in phase 1. Offer a CSV export of receipts per month so the business's
  accountant can keep VAT and income-tax books. Get a written opinion from a Bangladeshi VAT consultant before adding
  BIN or VAT lines. If VAT-registered businesses want Mushak-6.3 later, make it a separate, opt-in document with the
  BIN, built to the NBR format.
* **Retention.** The contract's 6 years after deletion, with personal fields removed, is a conservative choice to
  cover VAT and income-tax record-keeping periods (roughly 5–6 years; exact periods not verified). State the purpose
  and period in the privacy policy, and include desktop copies (I4.5, I6).
* **Privacy law.** Bangladesh was finalising a personal data protection law in 2025; its status and requirements were
  not verified here. Health-adjacent purchase data (which medicines, which tests) should be treated as sensitive
  either way. That is the reason for I3 and I6.

Sources: [DFDL — Bangladesh VAT law amendments (Jan 2025)](https://www.dfdl.com/insights/legal-and-tax-updates/bangladesh-updates-on-amendments-to-the-vat-law/),
[TBS — shop owners on the VAT exemption limit (Jan 2025)](https://www.tbsnews.net/node/1054656),
[bdnews24 — registration threshold lowered from Tk 3 crore to Tk 50 lakh (2026)](https://bdnews24.com/economy/be5da7580bc7),
[TBS — prices of medicines become VAT inclusive (Jul 2019)](https://www.tbsnews.net/node/3807),
[rakibhassan.eu — VAT on medicine in Bangladesh (2026)](https://rakibhassan.eu/vat-on-medicine-bangladesh/).

---

## Things the contract already gets right
Immutable invoices with void-with-reason only and full audit; the server recomputes money; a public verify page with
no patient or customer data and a rate limit; customer phone optional and masked after 30 days; idempotent `opId`;
per-device series so two offline PCs never clash; negative stock accepted with a warning rather than losing a real
sale; revoked devices refused at sync; a 7-day offline cap and a 10-minute idle PIN; a 5-device cap; caregivers
read-only via `asUid`; no receipts for blood donation; sanctioned businesses blocked; demo and real separated.
