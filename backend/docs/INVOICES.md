# Lifolo — Invoices (রসিদ) + Desktop app — shared contract

The owner approved this on 2026-10-23. Six agents build against this file. If you must change a name, write the change into
`backend/docs/INVOICES.md`, which the backend-invoices agent owns. That file wins once it is on main.

## Who issues receipts
| kind | issuer (role) | source |
|---|---|---|
| `pharmacy` | approved pharmacy owner (+ desktop device) | `books_sale_add` (counter / rx sale); online rx order delivered |
| `lab` | approved lab owner (+ desktop device) | new `lab_bill_create` (from a lab order or walk-in) |
| `chamber` | doctor or assistant (+ desktop device) | new `chamber_fee_collect`; online chamber `appt_pay_confirm` auto-issues one |
| `ambulance` | ambulance owner/driver (phone) | new `ambreq_finish` (trip fare) |

No receipts for blood donors (donation is voluntary). Blood banks come later.

## Invoice object (server JSON)
```
{ id, no, kind, bizId, bizName, bizPhone?, bizAddress?,
  patientUid?, customer:{name?, phone?},
  refType?:"book_sale"|"rx_order"|"lab_order"|"appointment"|"ambreq", refId?,
  lines:[{name, qty, unit?, price, disc, amount, code?}],
  subtotal, disc, fees:[{label, amount}], total, paid, due,
  payments:[{at, amount, method:"cash"|"bkash"|"nagad"|"card"|"other", ref?}],
  status:"issued"|"void", voidReason?, issuedAt, issuedBy, deviceId?, offline:boolean,
  verifyCode, verifyUrl }
```
- Money is BDT with 2 decimals, and the server recomputes all totals. Discounts are line + bill.
- `no` is human readable, unique per (kind, bizId): `<series>-<seq 6 digits>`. The series is `W` for server/web/phone, and `D1`, `D2`… for each registered desktop device.
- `verifyCode` is 10 random chars. The public page `https://lifolo.com/v/<verifyCode>` shows only: business name, kind, no, date, total, paid/due and status (valid / void). It never shows patient or customer data.
- An invoice is immutable after issue. The only changes allowed are adding payments and voiding with a reason. There is no delete, and everything is audited.

## Actions
- `invoice_get {id}`: the issuer, the linked patient, or that patient's caregivers.
- `invoice_verify {code}`: guest, rate-limited.
- `invoice_list_biz {kind, bizId, period:"today"|"7d"|"30d"|"month", q?, cursor?}`: the issuer.
- `invoice_pay {id, amount, method, ref?}`: collects a due amount. Issuer only.
- `invoice_void {id, reason}`: issuer only. For pharmacy invoices it also reverses stock, the same way `books_sale_delete` does.
- `my_invoices {asUid?, cursor?}`: the patient, or a caregiver via `asUid`.
- `my_expenses {asUid?, month:"YYYY-MM"}` → `{total, byKind:{pharmacy,lab,chamber,ambulance}, count}`.
- `books_sale_add` gains in its response: `invoice`. Input gains `customer {name?, phone?}` and `payments?`. Default is fully paid in cash.
- `rx_order_update` with `accepted` may carry `lines`. Status `out` or a new `delivered` status issues the invoice.
- `lab_bill_create {labOrderId?, lines?, customer?, fees?, disc?, payments?}`: lines are prefilled from the order's price snapshot.
- `chamber_fee_collect {appointmentId? | walkIn:{name, phone?, chamberKey, date}, visitType:"new"|"followup"|"report", amount, method, ref?}`. Doctor or assistant.
- `ambreq_finish {reqId, fare, extras?:[{label, amount}], method}`: driver/owner. Notifies the patient. Sets an `overFare` flag when the fare is above the fare chart.
- Push/inbox: when an invoice links to a patient, push type `invoice_new` with `cat:"my_request"` and `{invoiceId}`.

## Offline (desktop) sync
- `desktop_register {kind, bizId, name}` → `{deviceId, series:"D<n>"}`. Max 5 devices per business. `desktop_devices` lists them and `desktop_revoke {deviceId}` revokes one.
- `offline_bootstrap {deviceId}` → snapshot for the business + `asOf`:
  - pharmacy: items with price/stock/expiry
  - lab: price list + fees
  - chamber: appointments today and the next 7 days, fees per visit type
- `offline_sync {deviceId, ops:[{opId, type, at, data}]}` → `{results:[{opId, ok, id?, no?, warn?, error?}], delta}`.
  - `opId` is a UUID. The same opId always returns the same result, so syncs are idempotent.
  - Op types: `sale` | `purchase` | `adjust` | `lab_bill` | `fee` | `pay` | `void` | `checkin` | `walkin`.
  - The device assigns `no` within its own series, and the server accepts it.
  - If stock goes negative, the op is still accepted with `warn:"stock_short"` and the item is flagged for review.
  - Ops older than 30 days or from a revoked device are rejected.
- A revoked device stops syncing at once (403 `device_revoked`).
- Desktop offline login: the Firebase session persists in WebView2. Offline use is allowed for up to 7 days since the last online check. A local PIN locks the app after 10 minutes of idle.

## Desktop app
- Tauri 2, Windows 10/11 x64. Lives in `desktop/` and loads the bundled `web-vue` build (all of the website). Online features call `https://lifolo.com`.
- Offline engine lives in `web-vue/src/offline/**` and is owned by the desktop agent. It is TypeScript and stores data in SQLite through Tauri.
  The `OfflineStore` interface used by screens:
  ```
  isDesktop(): boolean
  ready(kind, bizId): Promise<{deviceId, series, asOf, pending}>
  items(q), labPrices(), chamberDay(date)              // cached reads
  record(op): Promise<{localId, no, invoice}>           // applies locally, queues, returns printable invoice
  pending(): number; syncNow(); onChange(cb)
  print(invoice, {paper:"58"|"80"|"A4"})
  ```
  On the plain website, `isDesktop()` is false and screens call the API directly.
- Printing: ESC/POS thermal 58/80 mm (Windows printer name) and A4 via WebView print. Bangla text is rendered as an image for thermal printers.
- Tray icon, Windows notifications, auto-update (Tauri updater), installer published at `https://lifolo.com/download/` next to the APK.

## Receipt look (all clients)
- Header: business name, address, phone; Lifolo logo small.
- Lines, then totals, then paid/due/method.
- QR to `verifyUrl` and the text "lifolo.com/v/<code> — রসিদ যাচাই করুন".
- Digits are Bangla by default, with an English toggle.
- A voided receipt prints with a "বাতিল" stamp.

## Rules
- Deactivated accounts get 423. Demo and real accounts never mix. Sanctioned businesses cannot issue receipts.
- Customer phone is optional. It is never shown on the verify page and is masked in business lists after 30 days.
- Retention: invoices are kept 6 years (accounting) after account deletion, with personal fields removed.

---

## Backend notes (owned by the backend-invoices agent, branch `spring-p16`)

This section is the source of truth for the backend. Package `com.lifolo.invoices`, migrations `V630`–`V649`.

### Service methods for the offline sync agent (spring-p17)

The desktop op `data` is **the same JSON body as the online action** (`books_sale_add`, `lab_bill_create`,
`chamber_fee_collect`, `invoice_pay`, `invoice_void`). The sync agent checks the device (registered, not revoked, op age,
`opId` idempotency) and then calls these in its own transaction (they join it — `@Transactional`, REQUIRED):

```kotlin
package com.lifolo.invoices

/** a receipt the desktop numbered itself: `no` = "<series>-<seq 6 digits>" in the device's own series (D1, D2 …) */
data class DeviceStamp(
    val deviceId: String,
    val no: String,                    // the number the server stores (p17 renumbers before calling issue)
    val at: Long,                      // epoch ms, when the device recorded it
    val verifyCode: String? = null,    // the code the device printed: ^[A-Za-z0-9]{10}$ else 400 bad_verify; used → 409 verify_collision
    val deviceNo: String? = null,      // the number the device printed, when it differs from `no` → invoices.device_no, JSON `deviceNo`
)

class InvoiceCreators {
    // books_sale_add: sale + stock + invoice. Returns {id, sale, invoice} (+ warn:"stock_short", stockShort:[itemId] when an
    // item had less stock than sold — the sale is still accepted and the stock stops at 0, like the online sale).
    fun pharmacySale(lang: String?, actor: AuthUser, data: Body, stamp: DeviceStamp? = null): Map<String, Any?>
    // lab_bill_create → {invoice}
    fun labBill(lang: String?, actor: AuthUser, data: Body, stamp: DeviceStamp? = null): Map<String, Any?>
    // chamber_fee_collect → {invoice} (actor = the doctor or an assistant: AuthUser.email/emailVerified are checked)
    fun chamberFee(lang: String?, actor: AuthUser, data: Body, stamp: DeviceStamp? = null): Map<String, Any?>
}

class InvoiceService {
    // any kind, low level (the creators call it): issuer rights, sanctions, demo/real, totals recomputed, number
    // (series W, or stamp.no accepted as is — 409 `no_taken` if that no already exists for (kind, bizId)), audit, push.
    fun issue(lang: String?, actor: AuthUser, cmd: IssueCmd, stamp: DeviceStamp? = null): Invoice
    // invoice_pay / invoice_void (issuer only). With a stamp the payment/void time is stamp.at (stamp.no is ignored).
    fun pay(lang: String?, actor: AuthUser, invoiceId: String, payment: PaymentIn, stamp: DeviceStamp? = null): Invoice
    // void: reason ∈ wrong_item | returned | wrong_price | duplicate | other (+ note; "other" needs one)
    fun void(lang: String?, actor: AuthUser, invoiceId: String, reason: String, note: String? = null, stamp: DeviceStamp? = null): Invoice
    // owner only (not an assistant) — 403 owner_only: use for desktop_register / desktop_revoke (security review I7)
    fun requireOwner(lang: String?, actor: AuthUser, kind: String?, bizId: String?): Biz
    // an invoice by its number — `no`, else the `device_no` the desktop printed (offline ops that pay/void an invoice
    // made offline refer to it by its number)
    fun findByNo(kind: String, bizId: String, no: String): Invoice?
}
// Invoice.toMap() is the "Invoice object" JSON above.
```

Errors are `ApiException` (status + bilingual message + `code`), like the actions.


### Changes after the security review (docs/test-plan/INVOICES-SECURITY-REVIEW.md) — these win over the contract above

**Permissions (I7)**

| what | pharmacy / lab owner | ambulance owner | doctor | doctor's assistant (verified email) | patient / caregiver (`asUid`) | guest |
|---|---|---|---|---|---|---|
| issue (sale, lab bill, chamber fee, trip) | ✓ | ✓ (`ambreq_finish`, the driver who took the trip) | ✓ | ✓ (`doctorUid`) | – | – |
| `invoice_pay` (collect due) | ✓ | ✓ | ✓ | ✓ | – | – |
| `invoice_void` | ✓ any | ✓ any | ✓ any | only receipts they issued, within 2 h; the doctor gets a push (`invoice_void`) | – | – |
| `invoice_list_biz` / `invoice_get` (business) | ✓ | ✓ | ✓ | ✓ | – | – |
| `invoice_export_csv`, `desktop_register`, `desktop_revoke` | ✓ | ✓ | ✓ | – (403 `owner_only`) | – | – |
| `invoice_get` (own), `my_invoices`, `my_expenses`, `invoice_report` | – | – | – | – | ✓ | – |
| `invoice_verify` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (30/min/IP) |

`issuedBy` is always the real person (the assistant, not the doctor).

**Patient link (I3).** `patientUid` comes only from something the patient created: an online rx order, a prescription
code (rx sale), a lab order, an appointment, an ambulance request. Never from a typed phone; the response is the same
either way.

**Verify (I1, I9, I10, I14).** `invoice_verify {code}` → `{invoice: {bizName, kind, no, date, total, paid, due, lines
(count), status: "valid"|"void", voidedAt?, demo, sanctioned?, footer}}`. A void, an anonymised (account deleted) or a
purged receipt still answers (purged: `archived: true`, from `invoice_tombstones`). Unknown code → 404.

**Void (I2).** `invoice_void {id, reason, note?}` with `reason` ∈ `wrong_item` (ভুল পণ্য), `returned` (ক্রেতা ফেরত দিয়েছেন),
`wrong_price` (ভুল দাম), `duplicate` (একই রসিদ দুবার), `other` (অন্যান্য — `note` required). The invoice JSON gets
`voidReason` (the code), `voidNote?`, `voidedAt`. `books_sale_delete` on a sale that has a receipt → 409 `invoice_exists`;
void is the only way (stock goes back).

**দিনের শেষ (I2).** At 21:30 Dhaka every business owner with activity gets one push + bell item (`type:
"invoice_summary"`, `cat: business`, `day`): receipts and total, voids, receipts with discounts above 10 % of the gross,
stock write-offs (৳).

**Ambulance (I8).** When the trip is accepted (`ambreq_accept` / `ambreq_agree`) the chart estimate and the chart's
applicable extras are frozen on the request (`snap_*`). `ambreq_finish {reqId, fare, extras?: [{label: "oxygen"|"icu"|
"night"|"other", amount}], method}`: each extra ≤ its frozen chart value (not in the chart → 400 `extras_cap`), `other`
≤ ৳500; `overFare = fare + chart extras > agreedFare ?: frozen estimate`; response `{invoice, overFare, chartFare,
agreedFare}`. The patient's push says "রাজি হয়েছিলেন ৳X-এ" when over. New `invoice_report {id, paid?, note?}` (patient or
caregiver, once, within 30 days): "রসিদের চেয়ে বেশি নিয়েছে" → `invoice_reports`, an ambulance's `overcharge_count` + 1.

**Demo (I9).** Demo receipts carry `demo: true` (JSON and verify). Demo pharmacies, labs and ambulances can't change
their name (403 `demo_locked`).

**Privacy (I6).** `invoice_list_biz` masks the customer phone after 30 days and `q` matches phone digits only within
those 30 days. Account deletion also clears `voidNote` and payment refs.

**VAT (I15).** Never print VAT / মূসক / চালানপত্র or a VAT line. Every invoice JSON (and verify) has
`footer: "টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়"`; clients print it at the bottom. `invoice_export_csv {kind, bizId,
month: "YYYY-MM"}` (owner only) → `{filename, mime: "text/csv", count, csv}` (UTF-8 BOM; columns no, date, status,
customer, lines, subtotal, discount, fees, total, paid, due, methods, void_reason, device — no phone).

**Lists.** `invoice_list_biz` period also accepts `365d`. Response `{items, cursor, sum: {count, total, paid, due}}`.

**Links.** `invoiceId` (the latest receipt that is not void, else null) on: rx orders (`rx_activity` orders,
`rx_orders_pharmacy` items), lab orders (every lab order view, e.g. `lab_order_get` item), appointments
(`my_appointments` items), ambulance requests (`ambreq_status` patient view; the driver's own trip).
`appt_pay_confirm` returns `invoice` (or null).

**Chamber keys.** `assistant_chambers {doctorUid?}` (doctor or assistant) → `{doctorUid, doctorName, chambers: [{chamberKey,
name, nameBn, fee, noFee}]}` — the in-person chambers, for walk-in fees on a day without serials. (`provider_me` has
`hospitalId`/`hospitalName` per chamber; the key is `hospitalId || hospitalName`.)

**Pushes.** `invoice_new {invoiceId}` (patient + caregivers, cat my_request); `invoice_void {invoiceId, kind, bizId}`
(the doctor, when an assistant voids); `invoice_summary {day, kind, bizId}` (one per business with activity).

**Feature switches (owner rule, flags contract).** Keys `inv_pharmacy`, `inv_lab`, `inv_chamber`, `inv_ambulance`,
`my_expenses`; all OFF until switched on. Off (checked for the business owner / the patient):
- `inv_pharmacy`: `books_sale_add` answers `{id, sale}` exactly as before (no receipt, `customer`/`payments` ignored);
  `rx_order_update` has no `delivered` (404) and ignores `lines`; no receipts.
- `inv_lab` / `inv_chamber` / `inv_ambulance`: `lab_bill_create` / `chamber_fee_collect` + `assistant_chambers` /
  `ambreq_finish` → 404 `feature_off`; `appt_pay_confirm` issues no receipt; `ambreq_step` unchanged.
- a kind off: `invoice_get` (business side), `invoice_pay`, `invoice_void`, `invoice_list_biz`, `invoice_export_csv`
  → 404 `feature_off`; no end-of-day push for it.
- `my_expenses` off: `my_invoices`, `my_expenses`, `invoice_report`, the patient's `invoice_get` → 404 `feature_off`, no
  `invoice_new` push. On: only receipts of kinds that are on for the patient (`byKind` lists only those kinds).
- `invoice_verify` is always public.
Until `Features` (spring-p19) is on main, `invoices/InvoiceFeatures` is a shim: `lifolo.features-shim-on` (off in
production, on in the test profile).
