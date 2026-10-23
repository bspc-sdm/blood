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

This file is the source of truth from here on. Service signatures for the offline sync agent (spring-p17) follow in the next
commit on `spring-p16`; until then, do not depend on Kotlin names.
