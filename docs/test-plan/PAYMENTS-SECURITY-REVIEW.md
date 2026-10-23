# Lifolo Pay (manual MFS payments + bKash gateway): abuse, security and legal review

Review of the owner-approved Lifolo Pay CONTRACT (23 Oct 2026, mirrored in `backend/docs/PAYMENTS.md` on `spring-p20`)
against what is already on `main`: online-chamber pay (`appt_pay_*`, V621), invoices, care links, assistants, the demo
world and feature switches (`spring-p19`). When this was written, `spring-p20`, `app-pay` and `web-pay` had no
implementation yet, so this reviews the design rather than the code. It is a docs-only review. No code was changed,
and nothing here has been proven by a test yet. Each item gives a concrete fix and the test that should prove it.
Manual tests are in [manual/19-payments.md](manual/19-payments.md).

The legal items (P10, P11, P12) are **not legal advice**. They summarise public sources found on 23 Oct 2026 and mark
what is uncertain. A Bangladeshi fintech/payments lawyer has to confirm them.

**সংক্ষেপে (মালিকের জন্য):** ১৫টা বিষয়: 🔴 Critical ৩টা, 🟠 High ৭টা, 🟡 Medium ৪টা, 🟢 Low ১টা।
সবচেয়ে বড় ঝুঁকিগুলো: (১) **আইন (P10)** — বাংলাদেশ ব্যাংক ২৭ সেপ্টেম্বর ২০২৬-এ ২২টা "অনুমোদনহীন পেমেন্ট অ্যাগ্রিগেটর"-এর নাম দিয়ে সতর্ক করেছে, যার কয়েকটা ঠিক
আমাদের মতো — টাকা সরাসরি দোকানের নিজের bKash/Nagad-এ যায়, প্ল্যাটফর্ম শুধু যাচাই করে। Lifolo টাকা ধরে না, তবুও "টাকার অনুরোধ + দাবি + নিশ্চিত + রসিদ" কোথায় গিয়ে
লাইসেন্স লাগার মতো "পেমেন্ট সেবা" হয়ে যায়, সেটা আইনে পরিষ্কার নয়। আইনজীবীর লিখিত মত না পাওয়া পর্যন্ত `pay_manual` শুধু নিজেদের পরীক্ষার দোকানে পাইলটে রাখুন।
(২) **নম্বর দখল (P4)** — কেউ একটা ডাক্তার/দোকানের অ্যাকাউন্টে ঢুকে গেলে টাকার নম্বর বদলে দিতে পারে; ২৪ ঘণ্টার অপেক্ষা আছে, কিন্তু "নতুন পদ্ধতি যোগ" বা "মুছে আবার যোগ"
দিয়ে সেটা এড়ানো যায় কি না চুক্তিতে পরিষ্কার নয়, আর সতর্কবার্তা যায় সেই দখল হওয়া অ্যাকাউন্টেই। (৩) **গেটওয়ে (P7)** — bKash থেকে ফেরার লিংকে `status=success` লেখা থাকে;
সার্ভার সেটা বিশ্বাস করলে বিনা টাকায় সাবস্ক্রিপশন; আর চুক্তিতে টাকার অঙ্ক অ্যাপ পাঠায়। (৪) দোকান টাকা পেয়েও "পাইনি" বললে রোগীর একই TrxID আটকে যায়, আর সেই
"বাতিল" রোগীকেই শাস্তির তালিকায় তোলে (P3)। (৫) ফেরতের TrxID বানিয়ে লিখলেও "ফেরত" দেখায়, আর ফেরতের পর রসিদ "পরিশোধিত"-ই থাকে (P5)।

Severity scale: as in [06-phases-and-exit-criteria.md](06-phases-and-exit-criteria.md#তীব্রতা-severity). 🔴 Critical,
🟠 High, 🟡 Medium, 🟢 Low. Critical and High items block `pay_manual` (and `pay_bkash_pgw` for P7) from leaving
**pilot** in production. P10 additionally limits the pilot itself (see its fix 1).

## Summary

| # | Topic | Severity |
|---|---|---|
| P1 | Fake claims: invented, reused or someone else's TrxIDs; the "claimed" state read as "paid" | 🟠 High |
| P2 | Social engineering: "send to this other number", fake support, impostor businesses, numbers in free text | 🟠 High |
| P3 | A business rejects a genuine claim (extortion); the TrxID stays locked; the 3-rejections rule punishes the victim | 🟠 High |
| P4 | Pay-number hijack after account takeover: cool-off bypasses, alerts only to the hijacked account | 🔴 Critical |
| P5 | Refund fraud both ways; a refund never touches the receipt | 🟠 High |
| P6 | IDOR on request ids, `bizId`, `refType/refId`, `asUid` and `amount` | 🟠 High (must be proven) |
| P7 | bKash gateway: trusting callback params, client-chosen amount, replay, open redirect | 🔴 Critical |
| P8 | Demo and real mixing; demo businesses showing real numbers | 🟠 High (🔴 while demo is live in production) |
| P9 | Money-laundering red flags (refunds to other numbers, round-tripping, velocity) | 🟡 Medium |
| P10 | Bangladesh Bank licensing (PSO/PSP, aggregators) for the direct-to-business model | 🔴 Critical (legal; needs a lawyer) |
| P11 | Consumer protection and refund duties | 🟠 High |
| P12 | Retention of TrxIDs and payment records | 🟡 Medium |
| P13 | Feature-switch semantics: whose pilot, and switching off mid-flight | 🟡 Medium |
| P14 | Assistant and caregiver scope | 🟡 Medium |
| P15 | Leaks via lock screen, CSV, logs and admin views | 🟢 Low |

---

## P1: Fake claims and fake TrxIDs (High)

**What the contract allows.** Format checks (bKash 10 alphanumerics; others 6–20), one use per business (409
`pay_ref_used`), `senderLast4`, rate limits (5/request/h, 20/user/day), and the 3-rejections rule. These are good. The
real defence is still a person checking their own wallet app, and the gaps are around that person:
* A busy pharmacist sees "পাঠিয়েছেন" and sends the delivery before confirming. If any screen or push shows "claimed"
  in green, or the order moves on to "প্রস্তুত" when it is claimed, the claim works as payment.
* **Screenshot front-running.** A real TrxID seen in a family WhatsApp group, a shared screen, or the business's own
  CSV can be claimed first by someone else at the same business. The real payer then gets 409 and looks like a fraudster.
* A TrxID real at business A is accepted at business B (uniqueness is per business). That is correct, but B must not
  believe "a valid-looking TrxID" means anything.
* Amount mismatch (৳9 sent for ৳10). It is caught only if the confirm screen puts the expected amount next to the claim.
* Nagad, Rocket and Upay formats are loose (6–20). Typos pass.

**Fix.**
1. "Claimed" is never shown as paid. Use a neutral colour and the text "রোগী বলছেন পাঠিয়েছেন — আপনার অ্যাপে মিলিয়ে নিন".
   No downstream state (delivery, lab collection, chamber check-in) unlocks on `claimed`, only on `confirmed`.
2. The confirm dialog shows, in large type, **amount, TrxID, method, senderLast4 and claim time**, plus three
   checkboxes (TrxID found, amount equal, last 4 equal). The confirm button stays disabled until all three are ticked.
3. On a 409 `pay_ref_used`, say "এই TrxID দিয়ে এই দোকানে আগে দাবি হয়েছে। আপনি নিজে পাঠিয়ে থাকলে 'রিপোর্ট' করুন — আবার টাকা পাঠাবেন না",
   offer `pay_report` with the TrxID, and record a `ref_conflict` row for admins. Never reveal who claimed it.
4. Normalise the TrxID (trim, uppercase, strip spaces and dashes) before the format and uniqueness checks, so
   `ab12…` and `AB12…` are the same key.
5. Collect real TrxID samples per method during the pilot (M-PAY-10, M-PAY-26) and tighten the 6–20 rule for Nagad,
   Rocket and Upay to what they actually issue. Until then, warn on unusual lengths ("ঠিক লিখেছেন তো?").

**Test.** `PayClaimIT`: format per method, normalisation, 409 across patients, claimed never unlocks a delivery or a
receipt, `ref_conflict` row. Manual: M-PAY-17, M-PAY-20, M-PAY-26 to M-PAY-30.

## P2: Social engineering, "send to this other number" (High)

The biggest real-world loss in Bangladesh MFS is not technical: a call or message says "the app number has a
problem, send to 01…", "Lifolo support needs your OTP", or "pay the advance first" from someone pretending to be
a doctor. The contract keeps the payee number in a snapshot, which is correct, but:
* The in-app call and chat features (calls, `chat`) give a business, or an impostor who took over one, a trusted
  channel to the patient.
* The chamber `note` and business descriptions are free text (CALLS-SECURITY-REVIEW R6). Any free text shown before
  booking can carry a number.
* Blood requesters are the most vulnerable people on the platform (panic, a relative in hospital). "Pay the donor's
  transport first" scams already exist outside apps.
* A copied number in the clipboard can be swapped by malware on the patient's phone (rare, but the copy button makes
  it the common path).

**Fix.**
1. The PaySheet has no editable number field, and it always shows the fixed warning "এখানে দেখানো নম্বর ছাড়া অন্য কোথাও
   টাকা পাঠাবেন না। Lifolo কখনো PIN, OTP বা টাকা চায় না।" Next to the copy button: "পাঠানোর আগে নম্বরটা আবার মিলিয়ে নিন"
   (this covers a swapped clipboard).
2. Chat: when a message contains `01\d{9}` together with bKash/বিকাশ/নগদ/Nagad/টাকা/send, show the recipient a banner
   "অ্যাপের পেমেন্ট পাতা ছাড়া টাকা পাঠাবেন না — রিপোর্ট করুন". In the blood context, show "রক্তের জন্য কোনো টাকা দিতে হয় না".
3. Reject or hide phone numbers in every public free-text field (`note`, descriptions), as R6 fix 2 already proposes.
4. Add a `pay_report` reason `asked_other_number`. Two such reports against a business auto-pause its pay methods
   until an admin reviews them.
5. Never send SMS that contain payment links. Pushes stay generic ("পেমেন্ট আপডেট — লাইফলো").

**Test.** Unit test of the chat number detector. Manual: M-PAY-49, M-PAY-57.

## P3: A business marks a genuine payment unpaid (High)

**Scenario.** The patient pays ৳500 to a pharmacy for a delivery. The pharmacy rejects with `not_received` and asks
for ৳500 again, or says "pay ৳200 more and I will confirm". The money left outside Lifolo, so the patient has only
the claim. Three design details make it worse:
* The TrxID is now used for this business. If the patient tries again with the same real TrxID (the right thing), they get
  409. If rejected TrxIDs were released instead, a fraudster could retry forever, so it must stay reserved, but the
  patient needs a path forward.
* The 3-rejections rule counts **rejections received**. A dishonest business can push an honest patient into the
  "one open claim at a time + admin flag" state.
* There is no "un-reject". A business that rejected by mistake (it looked at the wrong wallet) cannot fix it.

**Fix.**
1. A rejection of a claim is never deleted. The patient's view keeps TrxID, amount, method, claim time, reject time,
   reason and note, and offers "রিপোর্ট করুন" with an optional screenshot upload (stored with the report and
   visible only to admins).
2. Allow `pay_confirm` on a **rejected** request within 7 days (the business fixes a mistake). It is audited, and the
   patient is pushed.
3. On a 409 for a TrxID that the same payer claimed and the business rejected, answer `pay_ref_rejected`
   ("এই TrxID দোকান বাতিল করেছে — রিপোর্ট করুন") and never `pay_ref_used`.
4. Count rejections for the restriction only if they are **not disputed**, or count only rejections from at least two
   different businesses. A disputed rejection counts against the business in `admin_pay_flags` instead
   (businesses with a high reject rate, or with ≥ 2 disputed rejections in 30 days).
5. An admin resolving a report as "business at fault" can mark the claim `confirmed_by_admin`. That status issues no
   receipt, but it shows on the business record and can lead to a sanction. Lifolo still never moves money.
6. In the terms, state that Lifolo does not guarantee or refund payments, but that it suspends businesses that do not
   honour verified payments.

**Test.** `PayRejectIT`: re-confirm after reject, `pay_ref_rejected`, the 3-rejections rule ignores disputed rejections.
Manual: M-PAY-19, M-PAY-25, M-PAY-32, M-PAY-58.

## P4: Pay-number hijack via account takeover (Critical)

**Why Critical.** One hijacked doctor or pharmacy account can redirect every patient's money to the attacker. Patients
cannot tell the difference, because the number comes from Lifolo, which they trust. The 24 h cool-off is the only
control, and the contract leaves bypasses open:
* "Each **changed** number gets a 24 h cool-off". Does a newly **added** method count? Does deleting bKash and adding
  bKash with a new number count? Does activating a method whose number was added earlier and kept inactive? Does
  changing `type` count?
* "Sends a push to the owner": the attacker holding the session can see and dismiss it. If the takeover came from
  a stolen phone or a SIM swap, the push goes to the attacker.
* The online chamber's legacy `bkashNumber` path (V621 has its own 24 h logic and `bkash_number_log`). Two code paths
  means two sets of rules.
* Who may call `pay_methods_set`? Assistants must not.
* During the cool-off, the attacker can still reject real claims, refund to themselves (P5), or mark things confirmed.

**Fix.**
1. Cool-off applies to **every number that was not active for at least 24 h** for this business: added, re-added,
   re-activated, or edited. The key is (method, number), and its "first seen active" time is stored. A changed `type`
   on the same number does not need a cool-off, but it is audited.
2. Until a new number matures, new requests snapshot only matured numbers. If there are none, the PaySheet says
   "এই প্রতিষ্ঠান এখনো অনলাইন পেমেন্ট নিচ্ছে না — নগদে দিন". Never fall back to the immature number.
3. `pay_methods_set` requires a **recent login** (≤ 10 min, or a fresh OTP/password re-auth) and is owner-only (the
   doctor for chamber kinds). Assistants, caregivers and admins impersonating users get 404.
4. Alerts go out over **two channels**: a push to every device, and an email to the account's verified email
   (and an SMS to the account phone if SMS is available). Each alert has "আমি করিনি" which immediately freezes the
   pay methods (no new requests), revokes other sessions and opens an admin ticket.
5. One code path: the online chamber reads only `pay_methods`. The migration copies `bkashNumber` with its existing
   maturity time, and the legacy setter calls the same service. Keep the `bkash_number_log` rows.
6. Admins see "number changed in the last 7 days" on the business and in `admin_pay_flags`. A business with a new number
   **and** a reject spike is auto-paused.
7. Optional, strong: ask the business to prove ownership of a new number by entering the TrxID of a ৳1 Send Money
   from that number to a Lifolo-controlled number. This is close to collecting money, so check it against P10 first.

**Test.** `PayMethodsIT`: each bypass in fix 1 snapshots the old number; assistant 404; stale login 401
`reauth_required`; the email is sent; "আমি করিনি" freezes the methods. Manual: M-PAY-03, M-PAY-04, M-PAY-05, M-PAY-07.

## P5: Refund fraud (High)

**Business side.** `pay_refund {id, trxId, amount?}` is a claim exactly like the patient's, but nobody checks it:
* A fake refund TrxID marks the request `refunded`. It looks resolved to admins and in the CSV, and it removes the
  item from "pending" views.
* Refunding to "a different number" (the patient asks, or an attacker asks) is a laundering and fraud path (P9).
* `refundAck` is optional. With no ack, the status is still `refunded`.

**Patient side.** The patient gets the refund and then reports "not received" to pressure the business. The business
has its own wallet history as proof, so this is mainly an admin workload issue.

**Receipts.** Confirming issues a receipt or adds `invoice_pay`. Nothing in the contract or INVOICES.md undoes that on
refund, so the receipt stays "paid", the patient's monthly expenses count money they got back, and the business's
day summary overstates income. That is an accounting error and a tool for hiding refunds.

**Fix.**
1. Split the status: `refund_claimed` (the business says it sent money back) → `refunded` only after `pay_refund_ack`,
   or automatically after 7 days without a dispute. The patient sees "পেয়েছি" / "পাইনি". "পাইনি" opens a
   report linked to both TrxIDs.
2. The refund amount must be ≤ the confirmed amount minus earlier refunds. Partial refunds are allowed, and the sum
   is tracked.
3. The refund goes to the **sender** number. The UI says "যে নম্বর থেকে টাকা এসেছিল (শেষ ৪: ১২৩৪) সেখানেই ফেরত দিন". Offer no
   field for another number.
4. Refunds are **owner-only**. The doctor refunds chamber requests, never an assistant (P14).
5. Receipts: a full refund calls `invoice_void` with a new reason `refunded` (stock goes back for pharmacy, as in a
   return). A partial refund adds a negative `invoice_pay` line (or a credit note), so the receipt, the verify
   page, `my_expenses` and `pay_summary` all agree. Do this in one transaction with the refund.
6. `admin_pay_flags` also lists businesses whose refunds are disputed, and refunds older than 7 days without an ack.

**Test.** `PayRefundIT`: amount > paid → 400; `refund_claimed` → ack → `refunded`; auto after 7 days; the invoice is
voided or credited; the summaries agree. Manual: M-PAY-21, M-PAY-22, M-PAY-23.

## P6: IDOR on request ids and business ids (High; must be proven, not assumed)

Every action takes client ids. The rules that each need an explicit test:
1. `pay_request_get`, `pay_claim`, `pay_refund_ack` and `pay_report`: the caller is the stored `payerUid`, or an
   **active** caregiver of the payer via `asUid` (`care_links.status='active'`, checked on every call, not cached for
   longer than the feature cache). Everyone else gets 404 with the same body whether or not the id exists.
2. `pay_confirm`, `pay_reject`, `pay_refund`: the caller owns the stored row's `(kind, bizId)`, read from the row
   and never from the body. For `kind=chamber`, a **current** assistant of that doctor may confirm and reject, but
   may not refund (P14).
3. `pay_list_biz`, `pay_summary`, `pay_export_csv`, `pay_methods_get/set`: `(kind, bizId)` belongs to the caller.
   `pay_methods_get` for a patient is answered only through a request snapshot, never directly (otherwise a patient
   could read any business's number without a booking, which defeats R6).
4. `pay_request_create {refType, refId, purpose, amount?}`: the caller is the party allowed to create it for that
   ref (the pharmacy that accepted **this** order, the lab of **this** order, the driver of **this** finished trip,
   the system for bookings). `refType` decides the table, so ids from two tables can never be confused (R5.2).
   The `amount` comes from the ref (order total, bill, fare, invoice due, chamber advance). A client `amount` is
   accepted only if it is ≤ the ref's amount and the caller is the business. A patient never sets an amount.
5. `pay_due_start {invoiceId}`: the invoice's customer is the caller or their active care patient, and its `due > 0`.
   Another person's receipt id gives 404. The amount equals the due.
6. Ids are random (UUID v4 or ≥ 128-bit), never sequential.
7. One open request per `(refType, refId, purpose)`. A second create returns the existing one.

**Test.** `PayAuthzIT`: a matrix of every action against {payer, caregiver active/removed, another patient, owner,
another owner, current assistant, removed assistant, another doctor's assistant, demo user, admin}, plus a
client-sent `amount` and `bizId`. Manual: M-PAY-39, M-PAY-41, M-PAY-42, M-PAY-43.

## P7: bKash gateway callback and checkout (Critical)

The flow is `pgw_checkout {purpose, amount}` → `bkashURL` → bKash redirects the browser to
`/api/pay/bkash/callback?paymentID=…&status=success|failure|cancel` → execute, record, redirect. Risks:
* **Trusting `status`.** Anyone can type `status=success` into the callback URL. The callback is unauthenticated (it
  comes from the user's browser, not from bKash).
* **Client amount.** `pgw_checkout` takes `amount` from the app, so a user can buy a subscription for ৳1.
* **Foreign `paymentID`.** Someone else's successful paymentID replayed on a different account, or the same one replayed
  twice, grants two subscriptions.
* **Execute timeouts.** When `execute` times out, the payment may still have completed. Without a later `query`, users
  pay and get nothing, or retry and pay twice.
* **Open redirect.** If the result redirect takes a `return`/`next` parameter, it can send users to a phishing page
  that says "payment failed, pay again here".
* **Credentials.** The app key and secret, username, password and the grant token (valid about 1 h) must never reach
  logs, clients or Sentry. Sandbox and production are different base URLs. A production key pointed at the sandbox
  (or the reverse) fails in confusing ways.

**Fix.**
1. The `status` param only chooses the branch. Success is decided only by our server calling **execute** (and, on
   error or timeout, **query**) with the `paymentID` that **our server created**, and checking `statusCode == "0000"`,
   `transactionStatus == "Completed"`, `amount` and `currency` equal to our stored checkout row,
   `merchantInvoiceNumber` equal to our row id, and `intent == "sale"`. The public bKash client libraries follow the same
   pattern ([karim007/laravel-bkash-tokenize](https://packagist.org/packages/karim007/laravel-bkash-tokenize)).
2. `pgw_checkout {purpose}` with no client amount. The price comes from a server table per purpose
   (`lifolo_subscription` only). Any other purpose → 400 `pgw_purpose`. Purposes of third-party fees are refused in
   code, which also enforces the P10 boundary.
3. A checkout row `(id, uid, purpose, amount, paymentID, status, trxID, createdAt)` with unique `paymentID` and unique
   `trxID`. Execute is idempotent: a second callback for the same paymentID returns the stored result. A paymentID not in
   our table → 404. The benefit is granted to the row's `uid`, never to the session in the callback.
4. A job queries every `pending` checkout older than 2 min, up to 24 h, then marks it `failed`.
5. The redirect goes to a fixed `lifolo://pay/result?id=<checkoutId>` or `https://lifolo.com/pay/result/<checkoutId>`,
   with no other parameters. The app and web read the status from the server (`pgw_status {id}`, owner only), never
   from the URL.
6. Secrets come only from env and are redacted in logs. Add `BKASH_ENV=sandbox|production`, and refuse to start a
   checkout when it disagrees with the base URL. Production keys exist only on the production server.
7. Demo accounts cannot reach `pgw_checkout` in production (`pay_bkash_pgw` is never on for demo there).

**Test.** `BkashCallbackIT` with a mocked bKash: forged `status=success` with failed execute → not granted; unknown
paymentID → 404; replay → one grant; amount mismatch → not granted; timeout → query job completes it; the redirect has no
open parameters. Manual: M-PGW-02 to M-PGW-06, M-PGW-08.

## P8: Demo and real mixing (High; Critical while demo is live in production)

The contract says "demo and real accounts never mix". Specific paths that must be closed:
* A demo doctor or pharmacy (anyone can log in as one) sets a **real** bKash number. If any real user can see that
  business (lists, links, a shared chamber page), it is a ready-made scam with a verified badge (the same as R6).
* Demo patients see real businesses' numbers through a request created across worlds.
* The pilot "ডেমো অ্যাকাউন্টে চালু" toggle turns payments on for every demo account at once, including in production.

**Fix.**
1. In demo worlds, `pay_methods_set` accepts only numbers from a fixed fake allowlist (for example `0130000000x`).
   Demo seed data uses only those. A real-looking number → 400 `demo_number`.
2. `pay_request_create`, `pay_claim` and `pay_due_start` check `sameWorld(payer, biz)` on the stored rows → 404.
3. A demo PaySheet shows a full-width "ডেমো — আসল টাকা পাঠাবেন না" banner, and a demo receipt says "ডেমো".
4. In production, keep `demo:false` for every `pay_*` key until the demo world is removed (S1).
5. `admin_pay_flags`, the CSV and the summaries never mix demo and real rows.

**Test.** `PayDemoIT`: demo plus a real number → 400; cross-world create, claim and get → 404. Manual: M-PAY-55.

## P9: Money-laundering red flags (Medium)

Lifolo never holds money, so it is very likely not a reporting organisation under the Money Laundering Prevention
Act. The businesses' MFS providers are, and they suspend accounts that look like mules: BFIU-instructed suspensions
of tens of thousands of personal MFS accounts for gambling and hundi have been reported
([The Financial Express, 9 May 2024: 48,586 accounts](https://thefinancialexpress.com.bd/trade/48586-mfs-accounts-suspended-for-online-gambling-wasika)).
Lifolo can still be used to give laundering a cover story ("medical payments"):
* **Round-tripping via refunds:** A pays B, B "refunds" to a different number (blocked by P5 fix 3).
* Patterns such as many new accounts paying one personal number in round amounts with no service, or invoice dues
  inflated by the business and paid by third parties.
* Very high per-request amounts on personal numbers. Personal MFS accounts have regulator-set limits (tightened
  since 2017, see [Future Startup, Jan 2017](https://futurestartup.com/2017/01/12/bb-tightens-mfs-transaction-rules-personal-level-clouding-e-commerce-payment-scenario/)),
  and businesses that receive many payments on a personal number risk having it frozen.

**Fix.**
1. Amounts always come from the ref (P6.4). Set caps per purpose: advance ≤ the chamber fee, delivery and lab ≤ ৳20,000,
   ambulance ≤ the fare-chart ceiling (I8), due ≤ the invoice due. Above a cap, cash only.
2. Refunds only to the sender (P5.3), and never above the paid amount.
3. In `admin_pay_flags`, add businesses with > 30 confirmed payments/day on a **personal** number, payers who pay
   ≥ 5 different businesses in 24 h, refund rate > 30 %, and new accounts (< 7 days) paying > ৳5,000 in total.
4. Recommend merchant or Personal Retail Account numbers to businesses (the bKash PRA is built for small-business
   collections: [bKash PRA](https://www.bkash.com/en/page/personal-retail-account)). Show "personal" numbers with a
   note to the business that its MFS provider may limit or freeze a personal account used for business.
5. Keep the records (P12) so that law enforcement requests can be answered. Write a short internal procedure for
   handling such requests.

**Test.** `PayFlagsIT` for each flag rule. Manual: M-PAY-58.

## P10: Bangladesh Bank licensing for the direct-to-business model (Critical; legal; needs a lawyer)

**What the law says (public sources).**
* The **Payment and Settlement Systems Act, 2024** was passed on 2 July 2024. Operating a payment system or providing
  payment services needs a Bangladesh Bank licence. Violations carry up to 5 years in prison and/or a fine of up to
  Tk 50 lakh ([The Financial Express](https://thefinancialexpress.com.bd/national/parliament-passes-payment-and-settlement-system-bill-2024)).
* On **27 Sep 2026** Bangladesh Bank publicly warned against **22 unlicensed "payment aggregator" platforms**, citing
  sections 4(2), 15(2) and 37(1) of the 2024 Act. It said that BFIU and Bangladesh Bank instruct banks and MFS
  providers to suspend channels used by such platforms ([Dhaka Tribune](https://www.dhakatribune.com/business/420801/bb-warns-against-22-unauthorized-online-payment),
  [Bonik Barta](https://en.bonikbarta.com/business/VCizgIgEtlgnb4M7/)).
* At least one listed platform, UddoktaPay, describes itself as accepting payments "through your personal Bangladeshi
  accounts" and verifying them server-to-server
  ([WordPress plugin page](https://cn.wordpress.org/plugins/uddoktapay-gateway/)). The money goes to the merchant's own
  wallet, and the platform recognises and confirms the payment. That is **structurally close to Lifolo Pay**.
* The MFS Regulations 2022 (15 Feb 2022) govern MFS accounts and run alongside the PSS Regulations and trust-fund
  guidelines ([Prothom Alo](https://en.prothomalo.com/business/local/bangladesh-bank-issues-new-mfs-regulations)).
  bKash offers a dedicated small-business collection account, the PRA ([bKash](https://www.bkash.com/en/page/personal-retail-account)).

**What is uncertain.** The public material does not define where "showing a business's own wallet number, recording
a patient's TrxID claim and letting the business confirm it" stops being a booking and record feature and becomes a
"payment service". Points in Lifolo's favour: no funds flow, no automated wallet reading, no API access to the business's
wallet, no settlement, no fee per payment. Points against: Lifolo issues payment requests, tracks payment status, sends
reminders, issues receipts on confirmation, and is a branded payments product ("Lifolo Pay"). It is also unclear whether
MFS providers' terms allow personal accounts to be advertised by a third-party platform for commercial collections.

**Fix.**
1. Before `pay_manual` leaves pilot, and before it is enabled for any business outside Lifolo's own test businesses,
   get a **written opinion from a Bangladeshi payments lawyer** on this exact flow, and ideally an informal reading from
   Bangladesh Bank's Payment Systems Department. Bring this review and the contract.
2. Product guardrails that keep Lifolo on the "record, not payment service" side (keep them even after the opinion):
   * Never read SMS, notifications or wallet APIs of a business to verify payments automatically.
   * Never take a fee, commission or subscription **per payment** for this feature. Charge Lifolo's income only
     through `pay_bkash_pgw` for Lifolo's own services (P7 fix 2).
   * Never hold, pool, split or settle money, and never let the gateway carry a third-party fee.
   * Avoid the word "Pay" as a payment brand in user-facing text. Prefer "পেমেন্টের হিসাব" / "সরাসরি দোকানকে পরিশোধ",
     and say on every PaySheet "টাকা সরাসরি <business>-এর নম্বরে যায়; Lifolo টাকা রাখে না".
3. Ask bKash and Nagad (merchant or partnership teams) whether listing businesses' **personal** numbers is
   acceptable. Default businesses to merchant or PRA numbers, and consider allowing only merchant/PRA numbers after
   the pilot.
4. The bKash gateway for Lifolo's own income goes through a signed bKash merchant agreement (trade licence, TIN and so
   on). It is a licensed provider, so this part is low risk as long as fix 2 holds.
5. Long term: if Lifolo ever needs to collect for businesses, do it only through a **licensed PSP/aggregator** with
   split settlement. Never build it in-house.

**Test.** None automated. The owner signs off the written opinion in this file before `pay_manual` goes to "on".

## P11: Consumer protection and refund duties (High)

**Sources.** The Consumer Rights Protection Act 2009 covers services as well as goods and lists deception and
concealment as anti-consumer practices. Complaints go to DNCRP within 30 days, and the complainant receives 25 % of
the fine ([The Daily Star](https://www.thedailystar.net/star-weekend/spotlight/putting-consumers-last-1383844)). The
Digital Commerce Operation Guidelines 2021 require a complaint channel with resolution within 72 hours, delivery
within 5 days in the same city and 10 days in another, marketplace payment to sellers within 10 days, prior consent
for personal data, and records kept for at least 6 years
([The Daily Star overview](https://www.thedailystar.net/law-our-rights/news/overview-the-digital-commerce-operation-guidelines-2021-2128871)).
*Uncertain:* whether Lifolo counts as a "digital commerce marketplace" under the 2021 Guidelines when it only lists
businesses and never sells. Assume yes for complaint handling and record keeping, which is cheap. The online sale of
medicines has its own DGDA rules, which are outside this review.

**Gaps in the contract.**
* The advance-fee refund rule (`refundableUntilHoursBefore`) exists, but nothing **obliges** the doctor to refund
  when the doctor cancels, nor tells the patient before paying.
* A delivery or lab prepay with no delivery or collection: there is no automatic "refund due" state.
* An ambulance fare paid after the trip: the fare-chart ceiling (I8) must apply to `ambulance_fare` too.
* There is no complaint SLA, and `pay_report` has no deadline or status shown to the patient.

**Fix.**
1. Show the refund policy **before** payment: on the booking screen and on the PaySheet ("ডাক্তার বাতিল করলে পুরো টাকা
   ফেরত; আপনি <n> ঘণ্টা আগে পর্যন্ত বাতিল করলে ফেরত").
2. Add a state `refund_due` that is set automatically when the business cancels a confirmed booking, order or
   collection, when a confirmed delivery is not marked delivered within 5 days (10 for another district), or when a
   patient cancels inside the refundable window. Push the business daily until it records a refund (P5) or an admin
   resolves it. The patient sees "ফেরত পাওনা — <business>".
3. A business with `refund_due` older than 7 days cannot create new pay requests (auto-pause) until it is resolved.
4. `pay_report` shows the patient its status and a promise "৭২ ঘণ্টার মধ্যে উত্তর". Admins get an SLA timer. Help text
   names DNCRP (জাতীয় ভোক্তা-অধিকার সংরক্ষণ অধিদপ্তর) for complaints against the business (check its current hotline before
   printing it).
5. Terms and privacy policy: Lifolo is not the seller, does not hold money and does not refund; the business is
   responsible; Lifolo handles complaints and can suspend businesses; payment records are kept (P12).
6. The ambulance fare chart and overcharge rules (INVOICES I8) apply to `ambulance_fare` amounts.

**Test.** `PayRefundDueIT`: each trigger sets `refund_due`; auto-pause after 7 days. Manual: M-PAY-24, M-PAY-12, M-PAY-58.

## P12: Data retention of TrxIDs and payment records (Medium)

A pay request holds financial data (TrxID, method, amount, last 4 digits of the sender) linked to health context
(doctor speciality, lab tests, pharmacy). The **Personal Data Protection Ordinance 2025** (Ordinance No. 61 of 2025)
was gazetted in November 2025 ([Prothom Alo](https://en.prothomalo.com/bangladesh/government/teeopu4dfv),
[Digital Policy Alert](https://digitalpolicyalert.org/change/18756-personal-data-protection-ordinance-2025-ordinance-no-61-of-2025)).
Reported key points: explicit consent, purpose limitation, storage only as long as needed, enhanced protection for
financial and health data, and some provisions starting 18 months after gazettement
([The Daily Star, on the approved draft](https://www.thedailystar.net/tech-startup/news/bangladeshs-personal-data-protection-ordinance-2025-key-takeaways-4015401)).
*Uncertain:* the final text's retention and erasure clauses and the exact start dates. Check the gazette. This
conflicts with the 6-year record rule (P11) and the invoices' 6-year retention.

**Fix.**
1. **Confirmed** payments (with a receipt): keep the business-side record for 6 years like invoices (INVOICES.md
   retention), but on account deletion remove `payerUid` and the name, and keep `trxId`, method, amount, dates and
   business (needed by the business's own books and for disputes).
2. **Not confirmed** (rejected, expired, cancelled): delete `trxId`, `senderLast4`, `rejectNote` and screenshots
   after 180 days, unless a report is open. Keep counts for abuse rules.
3. Never put TrxIDs, numbers or amounts in application logs, Sentry, analytics or push payloads. Mask TrxIDs in admin
   lists (first 2 and last 2 characters) and log every full view in `admin_log`.
4. Report screenshots are stored privately, visible only to admins, and deleted 180 days after the report is resolved.
5. The privacy policy lists what is stored, why, for how long, and that Lifolo never stores PINs or card data.

**Test.** `PayRetentionIT`: the job clears unconfirmed fields after 180 days; deletion anonymises the payer; a log
scan finds no TrxID pattern. Manual: M-PAY-53 (the CSV is the business's own record).

## P13: Feature-switch semantics (Medium)

FEATURES.md checks `Features.isOn(caller)`. INVOICES and desktop check the **business** (`bizId`). For payments, the
contract does not say which side the check uses. This matters:
* If it checks the payer, a pilot patient sees pay buttons for non-pilot businesses that never set up methods.
* If it checks the business, `Features.require(ctx, key)` on a patient's `pay_claim` (the caller is the patient) fails
  for non-pilot patients of a pilot business. The business asked for prepay, and the patient cannot pay.
* Switching a key off mid-flight: if `pay_confirm` and `pay_refund` are gated, money already sent is stuck in "claimed"
  forever, and refunds are impossible.

**Fix.**
1. Gate **creation** on the business (`isOn(bizId, key)`, as in invoices). Gate the patient's actions on the request's
   existence, not on the patient's pilot status. A request exists only if its business was on.
2. When a key is off: block `pay_request_create`, `pay_due_start` and new claims on **open** requests. Always allow
   `pay_confirm`, `pay_reject`, `pay_refund`, `pay_refund_ack`, `pay_report` and the lists on existing requests (like
   `offline_sync`). Off means "no new money", never "stuck money".
3. Expire open requests when their key goes off, and push the patient "অনলাইন পেমেন্ট বন্ধ — নগদে দিন".

**Test.** `PayFeaturesIT`: pilot business + non-pilot patient can claim; the key goes off → confirm and refund still
work, and create gives 404. Manual: M-PAY-44 to M-PAY-47.

## P14: Assistant and caregiver scope (Medium)

* The contract lets "doctor assistants" act on chamber requests but does not list which actions. Settings and refunds
  move money or redirect it, so they must not be delegated.
* An assistant removed from the doctor's list must lose access immediately (cached roles).
* Caregivers (`asUid`) pay from their **own** wallet. Refunds must go to the caregiver's number, and both people should
  see that. A removed caregiver must lose `my_payments {asUid}` at once.

**Fix.**
1. Assistants: `pay_confirm` and `pay_reject` on chamber requests of their current doctor; read the lists and the
   summary. **Not** `pay_refund`, `pay_methods_*`, the advance-fee setting or the CSV. The doctor is pushed for every
   assistant confirm and reject, and the receipt names who confirmed.
2. Store `claimedBy` (`ctx.caller`) next to `payerUid`. The PaySheet and the business view show "কেয়ারগিভার <name>
   পাঠিয়েছেন". Refund instructions point to the claim's sender.
3. Role checks read `rx_team` / `care_links` on each call (no cache longer than 30 s).

**Test.** In `PayAuthzIT` (P6). Manual: M-PAY-38 to M-PAY-41.

## P15: Leaks via lock screen, CSV, logs and admin (Low)

The contract already uses a generic lock-screen text. Remaining points:
* Push **data** payloads (FCM) should carry only the request id, not the amount or business. The app fetches the details.
* The CSV contains TrxIDs and patient names. It is downloaded to shared shop PCs. Patient phone numbers must never be
  included. Name the file without the patient's name, and remind the business it is personal data.
* `pay_report` notes and screenshots are visible only to admins, and every view is logged.

**Fix.** As above. In tests, assert that the push payload has no `amount` field and that CSV columns are fixed.
Manual: M-PAY-53, M-PAY-54.

---

## Things the contract already gets right
Money never passes through Lifolo; no payments in the blood context (`no_payment_blood`); the ambulance fare only
after the trip and never required; cash always possible; no card data or PINs; methods snapshot at request creation;
a 24 h cool-off with a push and an audit row; TrxID unique per business with format checks; `senderLast4`; claim
rate limits and the repeated-rejection rule; reminders at 24 h and admin flags at 72 h; generic lock-screen text;
every receipt through InvoiceService; demo and real separated; sanctioned businesses cannot create requests; a
gateway only for Lifolo's own income, with 503 when it is not configured; everything ships off behind switches.
