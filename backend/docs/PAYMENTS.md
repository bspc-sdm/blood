# Lifolo Pay — manual MFS payments, gateway-ready — shared contract

Approved by the owner on 2026-10-23. The backend-payments agent owns `backend/docs/PAYMENTS.md`, which is final once it is on main.

## Principles
- Money goes **directly from patient to business**. Lifolo never holds or settles anyone else's money; the reason is licensing risk.
- No payment of any kind in the **blood** context. The server rejects it with 400 `no_payment_blood`.
- Ambulance: payment is only possible **after** the trip is finished, and it is never required.
- Cash always remains possible. A pay request is never a precondition for emergency services.
- Lifolo never stores card data or PINs.
- Every receipt goes through InvoiceService (see INVOICES.md). Confirming a payment issues the receipt, or adds an `invoice_pay` to an existing one.
- All of this ships OFF behind feature switches (see FEATURES.md).

## Feature keys (add them to Features)
| key | unlocks | needs |
|---|---|---|
| `pay_manual` | master switch for manual MFS payments | — |
| `pay_chamber_advance` | in-person chamber advance booking fee (doctor opts in) | pay_manual |
| `pay_delivery` | pharmacy home-delivery prepay | pay_manual, inv_pharmacy |
| `pay_lab` | lab home-collection and test-bill prepay | pay_manual, inv_lab |
| `pay_ambulance` | pay the fare after the trip via MFS | pay_manual, inv_ambulance |
| `pay_due` | patient pays an invoice's due amount | pay_manual |
| `pay_bkash_pgw` | bKash Tokenized Checkout, only for Lifolo's own income | — |

The online chamber fee stays under `online_chamber`, but its flow moves onto this layer. `appt_pay_claim`, `appt_pay_confirm` and `appt_pay_reject` stay as thin compatibility wrappers.

## Business payment settings
- `pay_methods_get {kind, bizId}`
- `pay_methods_set {kind, bizId, methods:[{method:"bkash"|"nagad"|"rocket"|"upay", number, type:"personal"|"merchant", active}]}`
  - Each number must be a valid BD mobile number.
  - Each changed number gets a 24 h cool-off; existing requests keep their snapshot.
  - Every change sends a push to the owner and writes an audit row.
- The online chamber's `bkashNumber` is migrated to and read from these methods. The doctor's existing data stays valid.
- Doctors can opt in to the advance booking fee with `{advance:{amount, refundableUntilHoursBefore}}` per chamber.

## Pay request (object)
```
{ id, kind, bizId, bizName, payerUid, refType, refId, purpose:"chamber_fee"|"booking_advance"|"delivery"|"lab"|"ambulance_fare"|"invoice_due",
  amount, methods:[{method, number, type}],   // snapshot at creation
  status:"open"|"claimed"|"confirmed"|"rejected"|"expired"|"refunded"|"cancelled",
  claim?:{method, trxId, senderLast4, at}, confirmedAt?, confirmedBy?, rejectReason?, rejectNote?,
  refund?:{trxId, amount, at, ackAt?}, invoiceId?, expiresAt, createdAt }
```

## Actions
**Created by the system or the business:**
- `pay_request_create {refType, refId, purpose, amount?}`. It validates the relation and that the purpose matches the refType's state:
  - booking_advance: when a booking is made at a chamber that has the advance fee on
  - delivery: when a pharmacy accepts an online order with prepay
  - lab: when a lab confirms home collection or creates a bill
  - ambulance_fare: only after `ambreq_finish`
  - invoice_due: the patient starts it from a receipt that has a due amount (`pay_due_start {invoiceId}`)

**Patient (or caregiver via `asUid`):**
- `pay_request_get {id}`
- `pay_claim {id, method, trxId, senderLast4}`
  - trxId: bKash exactly 10 alphanumeric characters; nagad, rocket and upay 6–20 alphanumeric characters.
  - senderLast4 is 4 digits.
  - A trxId may be used only once per business: 409 `pay_ref_used`.
- `pay_refund_ack {id}`
- `pay_report {id, reason, note?}`
- `my_payments {asUid?, cursor?}`

**Business (owner; doctor assistants for chamber requests):**
- `pay_confirm {id}`: issues or updates the receipt and returns `{ok, invoice}`.
- `pay_reject {id, reason:"not_received"|"wrong_amount"|"wrong_number"|"other", note?}`
- `pay_refund {id, trxId, amount?}`
- `pay_list_biz {kind, bizId, status?, period?}`
- `pay_summary {kind, bizId, day?}`: the day's collected amount, open count and pending claims.
- `pay_export_csv {kind, bizId, month}`

**Admin:** `admin_pay_reports`, `admin_pay_report_set`, and `admin_pay_flags`, which lists users with repeated rejected claims and businesses with long-pending claims.

## Jobs
- A claim not confirmed after 24 h sends a reminder push to the business.
- After 72 h it appears in admin flags.
- Expiry per purpose: open requests expire as follows.
  - booking_advance: at the slot time
  - delivery and lab: 24 h
  - ambulance_fare and invoice_due: 7 days

## Abuse controls
- If a user has 3 or more rejected claims in 30 days, they may have only one open claim at a time and get an admin flag.
- Rate limits on claims: 5 per request per hour, and 20 per user per day.
- Demo and real accounts never mix. Deactivated accounts get 423. Sanctioned businesses cannot create requests.

## Push and SSE
These are sent as push and SSE, with `cat` = appointments, my_request or business as fits:
- `pay_requested`
- `pay_claimed` (to the business)
- `pay_confirmed`
- `pay_rejected`
- `pay_refunded`
- `pay_reminder`
The lock-screen text has no amounts or numbers: "পেমেন্ট আপডেট — লাইফলো".

## Gateway-ready
- Interface `PaymentProvider { createCheckout, execute, query, refund }`.
- `BkashTokenizedProvider` is implemented against the bKash **sandbox**. It is configured by env BKASH_BASE_URL, BKASH_APP_KEY, BKASH_APP_SECRET, BKASH_USERNAME and BKASH_PASSWORD; without them it answers 503 `pgw_unavailable`.
- It is used only for Lifolo's own income: purpose `lifolo_subscription` is a placeholder endpoint plus a test. It is never used for third-party fees.
- Flow: `pgw_checkout {purpose, amount}` → `{bkashURL}`. The callback goes to `/api/pay/bkash/callback`, which executes, records and redirects to `lifolo://pay/result` or the web result page.

## Clients
- One reusable **PaySheet** UI:
  - amount, purpose and the business name
  - a method picker showing only the active methods
  - the number with a copy button, and type-specific steps (personal → Send Money, merchant → Payment)
  - TrxID and last-4 inputs, then "পাঠিয়েছি"
  - status timeline; refund acknowledgement; report
- Business "পেমেন্ট" section:
  - settings: methods and the doctor's advance fee
  - the pending-claims inbox with confirm/reject (confirm needs a confirm step)
  - lists, today's total, CSV
  - refund action
- Patient: payments are merged into "আমার রসিদ ও খরচ", with a payments tab.
- Every entry point is gated by its key.

---

## Backend (spring-p20) — final shapes; these win over the contract above

Package `com.lifolo.payments`, migration `V681__payments.sql` (above main's V680 practice systems). Includes the owner's decisions on
`docs/test-plan/PAYMENTS-SECURITY-REVIEW.md` (P1–P13). Tests: `payments/PayIT`, `PayAuthzIT`, `PayAmbulanceIT`, `PgwIT`,
`BkashTokenizedProviderTest`, `unit/MoneyWarnTest`, plus the online-chamber suites (`OnlineChambersIT`, `OnlinePaySafetyIT`).

**Legal line (P10), shown in PRIVACY.md and TERMS.md:** "লাইফলো টাকা গ্রহণ বা সংরক্ষণ করে না; পেমেন্ট সরাসরি সেবাদাতার কাছে যায়"
/ "Lifolo does not receive or hold any money; payments go directly to the service provider." No per-payment fee, never
hold funds, no SMS / wallet reading or automatic verification.

### Switches (FEATURES.md keys, in `Features.KEYS` / `Features.NEEDS`)
`pay_manual`, `pay_chamber_advance`, `pay_delivery` (+ `inv_pharmacy`), `pay_lab` (+ `inv_lab`), `pay_ambulance`
(+ `inv_ambulance`), `pay_due`, `pay_bkash_pgw`; the online chamber fee (`chamber_fee`) stays under `online_chamber`.
**Whose switch (P13):** creating a request — and a new claim on an open one — checks the **business** (its owner's uid).
A pilot business's patients can pay whoever they are. When a switch goes off nothing new starts (`pay_request_create`,
`pay_due_start`, `pay_claim`, `pay_methods_set` → 404 `feature_off`), but `pay_confirm`, `pay_reject`, `pay_refund`,
`pay_refund_ack`, `pay_report`, `pay_request_get`, `my_payments`, `pay_list_biz`, `pay_summary`, `pay_export_csv` keep
working on existing requests. `pgw_checkout` checks the caller.

### Payment numbers (P4, P8)
- `pay_methods_get {kind, bizId?}` (owner, or a doctor's current assistant) → `{kind, bizId, bizName, methods:[{method,
  number, type, active, activeNumber, pendingUntil}], frozen:bool, frozenSince, advance?:[{chamberKey, amount,
  refundableUntilHoursBefore}], chambers?:[{chamberKey, name, nameBn}], history?:[{method, change, number:"•••••••1234",
  oldLast4, newLast4, by, at, cooloffUntil}]}` (`history` for the owner only). `bizId` defaults to the caller (not for
  `ambulance`: the ambulance id).
- `pay_methods_set {kind, bizId?, methods?:[{method, number, type, active}], advance?:[{chamberKey, amount,
  refundableUntilHoursBefore}]}` → `{ok, changes, …the get view}`. **Owner only** (the doctor for chambers; assistants 403
  `owner_only`). Changing `methods` needs a **login within 10 minutes** (token `auth_time`; else 401 `reauth_required`),
  is refused while frozen (409 `pay_frozen`). `methods` replaces the list (max 4, each method once, valid BD mobile;
  400 `number` / `method` / `type` / `methods`). `advance` needs `pay_chamber_advance` and in-person chamber keys.
- **Cool-off:** a number reaches patients only after it has been **active for 24 h without a break** — new methods,
  removed and re-added, re-activated and edited numbers all wait. While an edited number matures, the matured number it
  replaced stays in use (`activeNumber`); a switched-off or removed method is gone at once; a type change is only audited.
  New requests snapshot only matured numbers; none → 409 `no_methods` ("নগদে দিন").
- **One code path:** the online chamber's `bkashNumber` (provider_save) is the doctor's `bkash` method — same cool-off,
  recent-login, demo and freeze rules, same notice. Bookings and `provider_me.bkashActive / bkashPendingUntil` read it
  from the methods (a first number now also waits 24 h; until then online bookings carry no bKash number).
  `bkash_number_log` is still written.
- **Notice:** every change → a push (`type:"pay_methods_changed"`, cat `account`, `action:"pay_methods_freeze"`,
  last 3 digits only) **and** an email to the account address with the "আমি করিনি" link.
- **Freeze ("আমি করিনি"):** `pay_methods_freeze {kind, bizId?}` (owner, no recent login needed) or the email link
  (`GET /api/pay/freeze?t=` shows a button; `POST /api/pay/freeze` freezes; signed token, 14 days). Frozen = no number
  reaches anyone, no new request (409 `pay_frozen`), no number change. Only `admin_pay_unfreeze {kind, bizId}` lifts it;
  `admin_pay_flags.frozen` lists them.
- **Demo (P8):** demo businesses accept only `0120000XXXX` numbers (400 `demo_number`), real ones never those.
  Demo and real never meet (404).

### Pay request object
As in the contract, plus: `status` adds `refund_claimed`; `claim.by` (who said "পাঠিয়েছি" — a caregiver pays from their
own wallet); `rejectedAt`; `refund.ackAt`; `refundableUntil` (booking advance); `refundDue:{at, why}` while a confirmed
payment is owed back; `demo:true`; `expiresAt` null for `chamber_fee`.
`refType` ∈ `appointment | rx_order | lab_order | ambreq | invoice`. Purpose → refType: `chamber_fee`, `booking_advance` →
appointment; `delivery` → rx_order (online, accepted/out); `lab` → lab_order (home collection confirmed) or a lab invoice;
`ambulance_fare` → ambreq; `invoice_due` → invoice. Any `blood…` refType/purpose → 400 `no_payment_blood`.

### Actions
| action | who | body → answer |
|---|---|---|
| `pay_request_create` | the business of that very source | `{refType, refId, purpose}` → `{ok, request, created}`. **The amount always comes from the source** (order lines + delivery fee, lab prices − discount + home fee, receipt due, fare due, chamber fee, advance) — a client `amount`/`bizId`/`kind` is ignored (P6); none → 409 `no_amount`. An open request for the same source+purpose is returned (`created:false`); a paid one → 409 `already_paid`. Errors: 404 (not yours — checked before anything about the source), 403 `biz_sanctioned`/`biz_not_approved`, 409 `pay_frozen`, `pay_paused`, `no_methods`, `not_finished`, `nothing_due`, `no_advance`, `too_late`, `state`, `amount_cap` (> ৳5,00,000) |
| `pay_due_start` | patient / caregiver (`asUid`) | `{invoiceId}` → `{ok, request, created}` (amount = the due) |
| `pay_request_get` | payer / caregiver / business | `{id}` or `{refType, refId, purpose?}` → `{request, side:"payer"\|"owner"\|"assistant", policy, demoNote?, events? (business)}`. `policy` = the refund policy + "Lifolo takes no money … send only to this number … never PIN/OTP" (P11, P2). For an online appointment it makes the `chamber_fee` request if missing |
| `pay_claim` | payer / caregiver | `{id, method, trxId, senderLast4}` → `{ok, request}`. TrxID normalised (spaces/dashes dropped, upper case); bKash exactly 10, others 6–20 → 400 `trx_format`; `method` must be in the snapshot (400 `method`); 400 `sender_digits` (error codes are `[a-z_]+`). 409 `pay_ref_used` ("…রিপোর্ট করুন — আবার টাকা পাঠাবেন না", never who), 409 `pay_ref_rejected` (this payer's TrxID the business rejected on another request), 429 `pay_claim_rate` (5/request/hour, 20/person/day), 409 `pay_claim_limit`, 409 `expired` |
| `pay_confirm` | owner or the doctor's current assistant | `{id, checks:{trxId:true, amount:true, last4:true}}` (all three, else 400 `checks_required`) → `{ok, request, invoice}`. From `claimed`, or from `rejected` within 7 days (P3, event `reconfirm`). Again → the same answer |
| `pay_reject` | owner / assistant | `{id, reason: not_received\|wrong_amount\|wrong_number\|other, note?}` (`other` needs a note) → `{ok, request}`. The claim stays visible |
| `pay_refund` | **owner only** (403 `owner_only`) | `{id, trxId, amount?}` (≤ the paid amount) → `confirmed → refund_claimed`. Receipt: in full → voided with the new reason `refunded`; in part → a refund line (`invoice.refunded`) |
| `pay_refund_ack` | payer / caregiver | `{id}` → `refund_claimed → refunded` |
| `pay_report` | payer / caregiver | `{id, reason: not_confirmed\|no_refund\|wrong_number\|asked_more\|asked_other_number\|scam\|other, note?}` → `{ok, id}`; a report on a rejected claim **disputes** it (it stops counting for the 3-strike rule) |
| `my_payments` | patient / caregiver | `{cursor?}` → `{items, cursor}` |
| `pay_list_biz` | owner / assistant | `{kind, bizId?, status?, period?: today\|7d\|30d\|month\|365d}` → `{items, sum:{count, amount, confirmed, pendingClaims}}` |
| `pay_summary` | owner / assistant | `{kind, bizId?, day?}` → `{day, kind, bizId, collected, confirmedCount, refunded, openCount, pendingClaims}` |
| `pay_export_csv` | owner only | `{kind, bizId?, month?}` → `{filename, mime, count, csv}` (BOM; `id,created,purpose,amount,status,method,trx_id,sender_last4,confirmed,refund_amount,refund_trx,receipt_no`; no phone numbers) |
| `pay_methods_get` / `_set` / `_freeze` | see above | |
| `admin_pay_reports` | admin | `{status?}` → `{items:[{id, reporterUid, reason, note, status, outcome, disputesReject, slaBreached (72 h), createdAt, resolvedAt, request, events}]}` |
| `admin_pay_report_set` | admin | `{id, status, outcome?: business_fault\|payer_fault\|no_fault}` (`payer_fault` makes a disputed rejection count again) |
| `admin_pay_flags` | admin | `{users (3+ counting rejections / 30 d), businesses + claims (claimed ≥ 72 h), frozen, numbersChanged (7 d), refundsOverdue, reportsLate, disputedRejects}` |
| `admin_pay_unfreeze` | admin | `{kind, bizId}` |

Caregivers (`asUid`): `pay_request_get`, `pay_claim`, `pay_refund_ack`, `pay_report`, `my_payments`, `pay_due_start`.
"claimed" never counts as paid anywhere (no receipt, no order state moves) — only `confirmed`.

### Receipts (InvoiceService)
Confirm → `chamber_fee`: the online fee receipt (`onlineFeePaid`); `booking_advance`: a chamber receipt "অগ্রিম বুকিং ফি";
`delivery`: the order's receipt (new, from its lines + delivery fee, or the payment added); `lab`: the lab bill from the
order (or the payment added to the bill); `ambulance_fare`, `invoice_due`: the payment added. Methods: bkash/nagad as
is, rocket/upay → `other` with ref `rocket:<TrxID>`. A receipt problem never undoes a confirmation.

### Online chamber (appt_pay_* compatibility)
`appt_pay_claim / appt_pay_confirm / appt_pay_reject` are wrappers: the appointment's `chamber_fee` request and the
appointment's pay columns change together in one transaction (same TrxID-once-per-doctor, 5/hour, `appt_pay_log`, old
pushes). The new actions on a `chamber_fee` request keep the appointment in step the same way. Existing online
appointments were migrated to requests (V681).

### Booking advance, ambulance
- `book` answers `payRequestId` when a request was made: the online fee, or the chamber's advance (`pay_chamber_advance`
  on for the doctor, matured numbers). An unpaid advance never cancels the booking (M-PAY-34); it expires at the slot.
- `ambreq_finish {…, payLater:true}` (pay_ambulance on for the driver): the receipt is issued **unpaid** and the answer
  carries `payRequest` (the `ambulance_fare` request). Before the trip is finished with a receipt → 409 `not_finished`;
  a fare paid in cash → 409 `nothing_due`.

### Pushes / SSE (lock screen: "পেমেন্ট আপডেট — লাইফলো", body without amounts, numbers or names; data only `{type, payRequestId, purpose, cat}`)
Payer (+ caregivers): `pay_requested`, `pay_confirmed`, `pay_rejected`, `pay_refunded` (cat `appointments` for
chamber_fee/booking_advance, else `my_request`). Business (cat `business`): `pay_claimed`, `pay_reminder`,
`pay_refund_due` (owner). Account: `pay_methods_changed`. SSE events with the same names on `user:<uid>`:
`{id, status, purpose}`.

### Jobs (every 15 min, `pay-jobs`)
Expiry (booking_advance at the slot, delivery/lab 24 h, ambulance_fare/invoice_due 7 days; open or rejected);
requests of cancelled appointments → `cancelled`; claim unchecked 24 h → `pay_reminder`; 72 h → admin flag;
**refund_due** (P11) on confirmed payments when the business declined/cancelled the appointment, the patient cancelled
an advance within the refundable time, the order was cancelled/withdrawn or not delivered within 5 days, or the home
collection was cancelled — the owner is pushed then once a day; owed > 7 days → the business cannot create requests
(409 `pay_paused`) and `admin_pay_flags.refundsOverdue`. Also the gateway reconcile below.

### Chat (P2, CHAT.md addition)
A text message with a phone-like number (01…, Bangla or English digits, separators allowed) and money words
(টাকা/বিকাশ/নগদ/রকেট/উপায়/৳/send/bkash/nagad…) carries `warn:"money_number"` (message object everywhere); `chat_send`
also answers `warnText` (blood threads: "রক্তের জন্য কোনো টাকা দিতে হয় না — টাকা পাঠাবেন না, রিপোর্ট করুন"; else "অ্যাপের পেমেন্ট
পাতা ছাড়া টাকা পাঠাবেন না — রিপোর্ট করুন"). Report reason `asked_other_number` added to `chat_report` / `call_report`.

### bKash gateway (P7) — Lifolo's own income only
- `pgw_checkout {purpose:"lifolo_subscription", client?:"app"|"web"}` → `{id, bkashURL, paymentID, amount}`. The price is
  the server's (`PGW_SUBSCRIPTION_PRICE`, default ৳299); any other purpose → 400 `pgw_purpose`; demo → 403 `demo_locked`;
  no credentials → 503 `pgw_unavailable`.
- `GET /api/pay/bkash/callback?paymentID=&status=` → 302 to the fixed `lifolo://pay/result?id=<id>` or
  `https://lifolo.com/pay/result/<id>` (nothing from the request). Success only when execute (or query, after an execute
  error/timeout or a cancel/failure branch) returns statusCode 0000, transactionStatus Completed, the paymentID we
  created, amount = our row, currency BDT, intent sale, merchantInvoiceNumber = our row id, and a trxID never granted
  before. Idempotent: a second callback answers from the stored row. Unknown paymentID → 404.
- `pgw_status {id}` (the row's own account) → `{id, purpose, amount, status, trxId, createdAt, completedAt}`.
- Reconcile job: pending checkouts older than 2 min are queried; after 24 h → failed. `admin_pgw_refund {id}`.
- Env: `BKASH_BASE_URL` (sandbox `https://tokenized.sandbox.bka.sh/v1.2.0-beta`), `BKASH_APP_KEY`, `BKASH_APP_SECRET`,
  `BKASH_USERNAME`, `BKASH_PASSWORD`; `PAY_PUBLIC_API` (default `https://lifolo.com`, the callback and freeze-link host).
  No real sandbox run in CI: bKash sandbox credentials are issued per merchant (not public), and completing a sandbox
  payment needs the wallet's browser page (OTP/PIN).

### Retention / deletion
Account deletion: demo rows go; real requests keep the business's record (TrxID, method, amount, dates) with the payer,
`claimed_by` and (on unconfirmed ones) the last 4 digits removed; the business's numbers and advance settings go.
