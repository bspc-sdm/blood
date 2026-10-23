# Online (video) chambers + the bKash fee — backend API

Owned by spring-p15 (23 Oct 2026). This is the "Online chambers" part of the calls contract; `docs/CALLS.md` (spring-p14)
covers the calls themselves (`call_*`, `appt_join`, `appt_call_ready`) and should point here. Where the two differ, this
file wins for chambers, booking and the fee.

No money goes through Lifolo: the patient sends the fee to the doctor's own bKash number and says so in the app; the
doctor or an assistant marks it received. The doctor may still see (and call) an unpaid patient — his choice.

## Chambers (`provider_save` in, `provider_me` / `providers` out)

Every chamber now has `mode: "in_person" | "online"`; missing on input = `in_person` (old apps and old data unchanged).

```jsonc
// provider_save → chambers[i]
{ "mode": "online",
  "days": [0,1,2,3,4,5,6], "start": "18:00", "end": "21:00",   // or "times": [{days,start,end}, …] (≤ 4)
  "fee": "৪০০ টাকা",  "noFee": false,                         // a taka amount; 0 = no fee
  "bkashNumber": "01712345678",                               // any BD mobile format; required unless noFee
  "note": "রিপোর্টের ছবি তৈরি রাখুন",                            // optional, ≤ 200
  "appointments": true, "patientMin": 10, "serialPhone": "" }  // as for any chamber
```

Rules: doctors only (nutritionist/trainer → 400 "অনলাইন চেম্বার শুধু ডাক্তারদের জন্য"); at most **one** online chamber
(400 "অনলাইন চেম্বার একটাই রাখা যায়") besides up to 3 at a place; its times may not overlap another chamber's (the
same clash check as before); fee must contain a taka amount (400 "অনলাইন চেম্বারের ফি লিখুন (টাকা)"); a missing or
wrong bKash number → 400 "আপনার বিকাশ নম্বর দিন (01XXXXXXXXX)". `hospitalId`, `placeName`, `address`, `district`,
`room` are ignored for an online chamber.

Out (both `provider_me` and `providers`; key order may differ):

```jsonc
{ "mode": "online", "hospitalId": null,
  "hospitalName": "Online video consultation", "hospitalNameBn": "অনলাইন ভিডিও পরামর্শ",
  "district": null, "upazilaId": null, "unionId": null, "room": "",
  "days": [...], "start": "18:00", "end": "21:00", "times"?: [...], "schedule": "",
  "noFee": false, "fee": "৪০০ টাকা", "appointments": true, "serialPhone": "", "patientMin"?: 10,
  "note": "…",                     // provider_me only (R6)
  "bkashNumber": "01712345678",    // provider_me only — never in the public providers list
  "bkashActive": "01712345678",    // provider_me: the number new bookings get now (the old one for 24 h after a change)
  "bkashPendingUntil": null }      // provider_me: ms when a changed number takes over, else null
```

In-person chambers just gain `mode: "in_person"`. The chamber key (`chamberKey`, serial counters, queue) of the online
chamber is `"online"`.

**Safety (security review R6).** `note` must not contain a phone/bKash number (BD mobile `01[3-9]` + 8 digits or any
10+ digits, Bangla or English digits, spaces/dashes/dots between them) → 400 "নোটে ফোন বা বিকাশ নম্বর লেখা যাবে না";
it is shown only to the doctor (`provider_me`) and to booked patients (`onlineNote`). Every bKash number change is
logged (`bkash_number_log`: last 3 digits old/new, who, when — shown to admins) and pushed to the doctor "আপনার বিকাশ
নম্বর বদলানো হয়েছে" (category account, always on). A **changed** number reaches new bookings only after **24 h**
(the old one meanwhile); a first number works at once. Bookings keep the number and fee shown when they were made;
a new note is copied to open appointments. Demo doctors (`demo-…`) get 403 "ডেমো অ্যাকাউন্টে অনলাইন চেম্বার চালু
নেই" and their online chambers cannot be booked unless `DEMO_ONLINE_CHAMBERS=true` (off by default = production). Removing the online chamber (or its day) cancels its future appointments like any chamber change.

## Doctor list (`providers`)

* `online: true` → only doctors with an online chamber, wherever they are: `district` / `upazilaId` / `unionId` /
  `hospitalId` are ignored; specialty, gender, day, openNow, maxFee, hospitalQ still apply (to the online chamber).
  `matched` lists the online chamber's index.
* `online: false` → only chambers at a place.
* Not set → both; area filters never match an online chamber (it has no place), so an online-only doctor shows up
  only without an area filter or with `online:true`. `hospitalQ: "অনলাইন"` finds online chambers.

## Booking (`book`)

Same request; `chamberIndex` = the online chamber's index, `chamberKey` optional (`"online"`). The appointment row gets
`online = TRUE` (BOOLEAN column — spring-p14's `appt_join` reads it), `chamber_key = 'online'`, `chamber` / `chamber_bn` =
the names above, and copies of fee / noFee / bKash number / note. `payState` = `"unpaid"`, or null when the chamber has
no fee (noFee or 0 taka). The doctor's push says "নতুন অনলাইন অ্যাপয়েন্টমেন্টের অনুরোধ"; the confirmation
"অনলাইন অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে"; the day's reminder "আজ অনলাইন অ্যাপয়েন্টমেন্ট আছে"; the 2-before
queue push "আপনার আগে ২ জন — অনলাইন ভিডিও কলের জন্য তৈরি থাকুন". Serials and the queue work as for any chamber.

## Appointment fields

`my_appointments` items (both roles; values null for an in-person appointment):

| field | |
|---|---|
| `online` | boolean (always present) |
| `payState` | `"unpaid" \| "claimed" \| "paid"`; null = nothing to pay |
| `payRef` | bKash TrxID the patient gave, or null |
| `payNote` | the doctor's "not received" reason, or null (cleared by a new claim / paid) |
| `fee`, `noFee` | as booked |
| `onlineNote` | the doctor's note |
| `doctorJoined` | `true` once the doctor or an assistant joined the video call (`appt_join` → `doctor_joined_at`); hide "the doctor did not come" then |
| `bkashNumber` | **patient side only** (key absent on the provider side); null when nothing to pay |
| `start` | slot start "HH:MM" (existing field) |

`rx_patients.appointments[]` add `online`, `payState`, `payRef`, `start`, `doctorJoined`.

## Fee actions (`POST /api/community`)

| action | who | body | answer |
|---|---|---|---|
| `appt_pay_claim` | patient (caregiver via `asUid` allowed) | `{appointmentId, payRef?}` | `{ok, payState:"claimed", payRef}` |
| `appt_pay_confirm` | doctor or assistant | `{appointmentId}` | `{ok, payState:"paid"}` |
| `appt_pay_reject` | doctor or assistant | `{appointmentId, reason?}` | `{ok, payState:"unpaid", payNote}` |
| `appt_report` | patient (caregiver allowed), doctor or assistant | `{appointmentId, reason}` (≤ 300) | `{ok, id}` |
| `admin_appt_reports` | admin | `{status?: "open"\|"resolved"\|"dismissed"}` | `{items:[{id, reporterUid, reporterRole, reason, status, createdAt, resolvedAt, appointment:{…, payState, payRef, fee, bkashNumber}, payLog:[{by, from, to, payRef, note, at}], bkashChanges:[{oldLast3, newLast3, by, at}]}]}` |
| `admin_appt_report_set` | admin | `{id, status}` | `{ok}` |

* **claim** — appointment `pending` or `confirmed`; `unpaid`/`claimed` → `claimed`. `payRef`: spaces and dashes
  dropped, upper-cased, exactly **10** letters/digits (a bKash TrxID), else 400 "বিকাশ মেসেজের TrxID লিখুন (ইংরেজি অক্ষর ও সংখ্যা)"; a repeat
  without a TrxID keeps the earlier one. Pushes the doctor and every assistant (accounts with an assistant email)
  "রোগী ফি পাঠিয়েছেন — যাচাই করুন" (`type:"appointment", role:"provider", id, cat:"appointments"`; body has the TrxID),
  only when the state or TrxID changed. Already paid → 409 `state`. **R7:** a TrxID is accepted once per doctor
  (on any of his appointments) → else 409 `pay_ref_used`; at most **5 claims per appointment per hour** → 429
  `pay_claim_rate`. Nothing is ever marked paid by itself: the doctor checks his bKash app (`payRef` and
  `payClaimedAt` are on his `my_appointments` items).
* **confirm** — appointment `pending`/`confirmed`/`completed`; `unpaid` or `claimed` → `paid` (the doctor may mark
  money that came without a claim). Already paid → same answer, no push. Pushes the patient (+ caregivers)
  "ডাক্তার ফি পেয়েছেন" (`type:"appointment", role:"patient", id, cat:"appointments"`).
* **reject** ("পাইনি") — only `claimed` → `unpaid`, optional `reason` ≤ 200 kept as `payNote`. Pushes the patient
  (+ caregivers) "ডাক্তার এখনো ফি পাননি — বিকাশ লেনদেনটা আবার দেখুন" with the reason.

**R8.** Every pay transition is written to the append-only `appt_pay_log` (who, from → to, TrxID, note). The doctor
cancelling or declining an appointment that is `claimed` or `paid` must give `providerNote` → else 400
`reason_required`; the patient gets "টাকা ফেরতের জন্য ডাক্তারের সাথে যোগাযোগ করুন / সাহায্য ও আপিল" and the admins a
report. Every 15 min a job looks at `paid` online appointments whose window (slot + 3 h) has passed while
`doctor_joined_at` is empty (set by spring-p14's `appt_join`) and no prescription was written for it: the patient
gets "ডাক্তার যোগ দেননি — ফি ফেরত চাইতে ডাক্তারের সাথে যোগাযোগ করুন / রিপোর্ট করুন" (data `sub:"unserved"`) and
the admins a report — once per appointment. `appt_report` lets either side flag an appointment (one open report per
person per appointment, quota `report`). Lifolo never holds or refunds the fee.

Errors (all fee actions): 404 — not found, not yours (a stranger, the patient calling confirm/reject, the doctor calling
claim), or demo ↔ real; 403 — assistant of a doctor whose badge is no longer approved, or a caregiver on confirm/reject;
409 `not_online` — in-person appointment; 409 `no_fee` — nothing to pay; 409 `state` — wrong appointment status or pay
state; 423 `account_deactivated` — the caller's account is deactivated (as for every action).

## Demo

Only with `DEMO_ONLINE_CHAMBERS=true` (tests, staging; off in production — R6): the demo doctor (ডা. ডেমো হাসান) has a third chamber, online, every day 13:00–16:00 (Friday 13:00–14:00), ৳400,
bKash 01200000204. Seeded online appointments: a background patient today, serial 1, **paid**; the demo patient's
**unpaid** (confirmed, ≥ 2 days ahead) and **claimed** (confirmed, TrxID `DEMO8X2K4P`, later). Nothing for the demo
patient today (the tester books). Rebuilt every Dhaka day by DemoRefresh like the rest of the demo.

## Storage (V620)

`provider_chambers.mode / bkash_number / note`; `appointments.online BOOLEAN NOT NULL DEFAULT FALSE, pay_state, pay_ref,
pay_note, pay_claimed_at, pay_paid_at, pay_paid_by, bkash_number, fee, no_fee, online_note` (CHECK: a pay state only on
an online appointment). V621: `providers.online_bkash_prev / online_bkash_changed_at`, `bkash_number_log`, unique
`(provider_uid, pay_ref)`, `appointments.pay_claim_count / pay_claim_window_at / doctor_joined_at /
unserved_notified_at`, `appt_pay_log`, `appt_reports`.
