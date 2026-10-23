# Lifolo — "আমার কাজ" home: backend (final)

Shared contract: the "আমার কাজ" redesign (owner approved 24 Oct 2026). This file is the **final** backend shape; where it
differs from the contract draft (section 4), this file wins (see "Differences from the draft" at the end).

Everything is additive: no old field or action changed meaning, so released apps keep working.

## Final names (top)

| what | name | notes |
|---|---|---|
| the home in one call | **`work_today {}`** | an action on `POST /api/community`, like every other call |
| pharmacy break | `pharmacy_open_override {open: false, until}` | `until` is new and optional |
| lab break | `lab_open_override {open: false, until}` | `until` is new and optional |
| chamber break (today) | `chamber_day_set {doctorUid?, chamberIndex \| chamberKey, closed: true, until}` | `until` is new and optional; answer adds `pausedUntil` |
| chamber break state | `chamber_days` items `pausedUntil` | ms or null |
| queue tab counts | `queue_today` chambers `counts: {waiting, now, done}` · `ambreq_driver` `counts: {new, active}` | new |
| queue tab counts (already there) | `rx_orders_pharmacy.counts {new, active, closed}` · `lab_orders.counts {new, active, reported, closed}` | unchanged |
| migration | `V693__chamber_pause.sql` | new table `chamber_pause` |

## `work_today`

Logged in, any user (no role → `items: []`). Not a caregiver action (a caregiver acting for a patient is refused by the
dispatcher, like every non-care action). Read-only. Each part reuses the code of the screen it summarizes, so the
numbers are the numbers on those screens: `queue_today` (doctor), `rx_orders_pharmacy`, `med_find_requests`,
`lab_orders`, `ambreq_driver`, the books' day totals (`shop_days`). A part that fails is left out (logged), the rest still
answers.

```
{ now: ms, date: <UTC-midnight ms of today, Dhaka>,
  roles: ["doctor","homecare","pharmacy","lab","bloodbank","ambulance"],   // kinds present, in chip order
  items: [Biz],                                                           // in the same order
  tasks: [Task],                                                          // all businesses merged, max 5
  taskCount: int }                                                        // all open tasks ("সব দেখুন")
```

### Biz

| field | type | notes |
|---|---|---|
| `kind` | `"doctor"\|"homecare"\|"pharmacy"\|"lab"\|"bloodbank"\|"ambulance"` | `doctor` = any chamber professional (doctor, nutritionist, trainer, physio, psychologist) and an assistant; `homecare` = nurse |
| `id` | string | doctor/homecare: the doctor's uid; pharmacy/lab: the owner's uid; ambulance: the owner's uid (vehicles inside); bloodbank: the bank id (one Biz per bank) |
| `name` | string | |
| `role` | `"owner"\|"assistant"` | an assistant gets one `doctor` Biz per doctor they assist |
| `proType` | string | doctor/homecare only: the badge type |
| `listingStatus` | `"approved"\|"pending"\|"suspended"\|"rejected"\|null` | doctor/homecare: `"approved"` (approved badge); ambulance: approved when any vehicle is |
| `status` | `"open"\|"paused"\|"closed"\|null` | see below; null for blood bank and home care |
| `pausedUntil` | ms \| null | only with `paused`: when it opens again by itself ("আবার খুলবে hh:mm") |
| `counts` | object | see below; a key of a switched-off/unavailable part is absent |
| `tasks` | [Task] | this business's open tasks, most urgent first, max 20 |
| `setup` | `[{key, done}]` | `[]` when not applicable (assistant); the client hides the checklist when every `done` is true |
| `chambers` | doctor only | `[{chamberIndex, chamberKey, name, nameBn, online, sitsToday, closedToday, sessionNow, status, pausedUntil}]` |
| `vehicles` | ambulance only | `[{id, name, vehicleNo, types, listingStatus, available, availableUntil}]` |
| `stockUpdatedAt`, `stockStale` | bloodbank only | ms \| null; bool (older than 48 h) |

### status

- **pharmacy:** a break (`pharmacy_pause`, `closedUntil`) → `paused` until its end. Else an active "খোলা/বন্ধ এখন"
  override: `open: true` → `open`; `open: false` → `paused` when the hours are open at its end (a break: it opens
  again by itself), otherwise `closed` ("বন্ধ" lasting to closing time — it does not "open again" then). Else the hours.
- **lab:** the same with `lab_open_override`; hours the server cannot read (free text) and no override → `open`
  (requests are taken at any time).
- **doctor:** per chamber — does not sit today or "আজ বন্ধ" (`chamber_day_closed`) → `closed`; a break
  (`chamber_pause`) → `paused`; else `open` ("আজ সিরিয়াল চলছে"). `sessionNow` = inside the session hours now. The
  Biz `status` = `open` if any chamber is open, else `paused` if any is (with the earliest `pausedUntil`), else `closed`.
  Online chambers appear only while `online_chamber` is on for the caller and the doctor.
- **ambulance:** per vehicle `available` ("খালি আছি" / "ট্রিপে আছি", only approved vehicles); Biz `open` if any is
  available. No break.
- **blood bank, home care:** `null` (blood bank: use `stockUpdatedAt` / `stockStale`).

### counts

| kind | keys |
|---|---|
| doctor | `today` (today's serials: confirmed + completed), `waiting` (not seen yet today, "পরে আসব" included), `requests` (pending requests from today on — doctor only, the assistant cannot confirm) |
| pharmacy | `new` (orders "sent"), `active` (ready/accepted/out) — approved only; `salesToday` (counter sales today), `salesTodayTk` (net ৳ today); `finds` (unanswered medicine-find requests — approved, finding not turned off) |
| lab | `new`, `active`, `reported` (the `lab_orders` segments) — approved only |
| ambulance | `calls` (open calls to this owner), `trips` (accepted, not finished) — when any vehicle is approved |
| bloodbank, homecare | none |

### Task

`{kind, bizId, type, id, title, sub, at, action, link}` — `kind` = the Biz kind (the row's role tag), `at` = ms (when it
came in), `link` = the web route that opens it (Android maps by `kind` + `type` + `id`). `title`/`sub` are in the
request's language (`lang`), Bangla digits for Bangla, Latin for codes.

| type | action (button) | id | title · sub | link |
|---|---|---|---|---|
| `call` (ambulance) | `accept` (গ্রহণ করুন) | request id | "অ্যাম্বুলেন্স দরকার" · pickup · district | `/work/ambulance` |
| `queue_next` (doctor) | `next` (ডাকুন) | chamberKey | chamber · "পরের সিরিয়াল #৬ · অপেক্ষায় ৪ জন" | `/work/pro` (assistant: `/work/rx/patients?doctor=<uid>`) |
| `late_ask` (doctor) | `reply` (উত্তর দিন) | appointment id | patient · "#৫ · ২০ মিনিট দেরি হবে" | as above |
| `order_new` (pharmacy) | `ready` (তৈরি, shop) / `accept` (গ্রহণ করুন, online) | order id | patient · "দোকানে এসে নেবেন" / "বাসায় পৌঁছে দিন" · code | `/work/pharmacy/orders/rx/<code>` |
| `lab_new` (lab) | `receive` (নমুনা নিয়েছি) | order id | patient · tests | `/work/lab/orders/<id>` |
| `lab_report` (lab) | `report` (রিপোর্ট পাঠান) | order id | patient · tests | `/work/lab/orders/<id>` |
| `appt_request` (doctor) | `accept` (গ্রহণ করুন) | appointment id | patient · chamber · day ("আজ"/"কাল"/date) | `/work/pro` |
| `find` (pharmacy) | `answer` (উত্তর দিন) | find id | medicine · area | `/work/pharmacy/finds` |
| `stock` (bloodbank) | `stock` (স্টক দিন) | bank id | bank · "স্টকের খবর এখনো দেওয়া হয়নি" / "পুরনো তথ্য" | `/work/bloodbank` |

Order: the table's order (call → queue_next/late_ask → order_new/lab_new → lab_report/appt_request → find → stock),
then newest first. `queue_next` only while patients wait and the chamber is running (queue started, or inside its
hours) and not closed today.

### setup keys

| kind | keys |
|---|---|
| doctor (owner) | `photo` (profile photo), `chamber` (at least one), `time` (a chamber sits on some day of the week), `address`, `fee` (a fee amount or "no fee") |
| homecare | `photo`, `homeVisit` (home visits switched on) |
| pharmacy | `approved`, `hours` (24 h or both times), `address`, `location` (map point) |
| lab | `approved`, `hours`, `address`, `prices` (a price list) |
| bloodbank | `approved`, `hours`, `address`, `stock` (set at least once) |
| ambulance | `approved` (every vehicle), `base` (every vehicle has its waiting place) |

## Breaks with `until` (auto-resume)

Every break is a row with its end time; every read compares it with now, so it **ends by itself** — no job, no write
at the end.

- `pharmacy_open_override` / `lab_open_override`: optional `until` (ms) with `open: true|false`; now < until ≤ now +
  24 h, else 400 "আগামী ২৪ ঘণ্টার মধ্যে সময় দিন" (also `until` with `open: null`). Without `until`: as before (the next
  scheduled change). Answer unchanged: `{ok, openNow, openOverride: {open, until}}`; `until` is folded into `openNow`
  everywhere (lists, filters, `pharmacy_me`, `lab_me`) exactly like the old override.
  - Client mapping: ৩০ মিনিট → `{open: false, until: now + 30 min}`, ১ ঘণ্টা → `+60 min`, আজকের মতো →
    `{open: false}` (to closing time; shows as `closed`), খোলা → `{open: null}` (or `{open: true}` outside the hours).
- `chamber_day_set {…, closed: true, until}`: a break of **today's** chamber; now < until ≤ the next Dhaka midnight,
  only for today (`date` absent or today), only on a day the chamber sits; else 400 "বিরতি আজকের মধ্যেই শেষ হতে হবে".
  While it lasts the doctor's card is not `openNow` for that chamber. It does **not** refuse bookings or move
  `nextSlot` (unlike `closed: true` without `until` = "আজ বন্ধ"). `closed: false` (today) ends a break and a closed
  day. Answer: `{ok, chamberIndex, chamberKey, date, closed, pausedUntil, requests}` (`closed` = whether the day itself
  is closed). The running serial queue is not paused by it (its own "বিরতি" button stays in the queue).
- Logged in `listing_switch_log` (`field` = `pause`, `until`), counted in the daily quick-switch limit (200).
- Table `chamber_pause (provider_uid, chamber_key) PK, until_at, paused_by, at` (V693, ON DELETE CASCADE with the
  account). Ended rows are ignored and overwritten by the next break.

## Differences from the draft

- `GET /api/work/today` → the action **`work_today`** on `/api/community`: the action path already gives the auth,
  caregiver and deactivated-account checks, the language and the client helper every screen uses; a separate REST
  route would have to repeat all of it.
- Ambulance is one Biz per owner with `vehicles[]` (calls go to the owner, not to a vehicle); blood bank is one Biz per
  bank (stock is per bank); doctor has `chambers[]` (the switch is per chamber).
- Task has `bizId` and `type` besides `kind` (the role tag), so a row can be acted on without another lookup.
