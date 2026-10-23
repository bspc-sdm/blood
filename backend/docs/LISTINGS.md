# Lifolo — unified service listings: backend data (final)

Shared contract: the listings redesign (owner approved 2026-10-23). This file is the **final** shape of the backend part;
where it differs from the first contract draft, this file wins (see "Differences from the draft" at the end).

All fields are **additive**: every old field of these responses stays as it was, so the released apps keep working.
Clients treat every new field as optional (null-safe).

## Final field list (top)

**Requests** — the five list actions: `ambulances`, `providers` (type doctor …), `pharmacies`, `labs`, `bloodbanks`.
All of them also accept:

| field | values | meaning |
|---|---|---|
| `lat`, `lng` | numbers (inside Bangladesh) | the viewer's point. Ambulances keep their old rule: the point counts as exact only with `gps: true`. Other lists: exact unless `gps: false`. |
| `upazilaId` | existing | without lat/lng its **centre** is the viewer's point (approximate) |
| `sort` | `"near"` \| `"rating"` \| `"open"` | absent / unknown = the list's old order |
| `q` | text, ≤ 60 chars | server-side search; every word must occur (case-insensitive, NFC) |

Extra filters (all optional, `true` to apply): pharmacies `delivery`; labs `homeCollection`, `openNow`; blood banks
`openNow`, `group` (`"A+"`… — banks that have it or have a little: `have`/`low`). The old filters stay (pharmacies
`openNow`, `open24`; blood banks `open24`; doctors `day`, `openNow`, `maxFee`, …).

**Every list item** (all five types):

| field | type | notes |
|---|---|---|
| `openNow` | `true` \| `false` \| `null` | Asia/Dhaka. Pharmacy/blood bank: hours, weekly closed days, pause. Lab: parsed from the free-text hours (null when not understood). Doctor: inside a chamber session now. Ambulance: always `null` — use `available`. |
| `opensAt`, `closesAt` | `"HH:MM"` \| `null` | 24 h; null when 24 hours, unknown, or doctor/ambulance |
| `is24h` | bool | |
| `distanceKm` | number (1 decimal) \| `null` | null when the request has no point (no lat/lng, no upazila) or the listing has no place |
| `distanceApprox` | bool | true when either end is an area centre (labs and blood banks have no coordinates → their upazila centre; doctors → the nearest chamber's upazila centre; an ambulance without a base → its district centre) |
| `ratingSummary` | `{avg: number (1 decimal), count: int}` \| `null` | null while nobody rated; always null for labs and blood banks (no ratings) |

**Per type:**

| type | fields |
|---|---|
| doctors (`providers`) | `nextSlot: {date, label, available, chamberIndex} \| null`, `feeAmount: number \| null`, `specialtyLabel: string \| null`, `systemLabel` (existing; null when `practice_systems` is off) |
| pharmacies | `delivery` (existing bool), `discount` (existing) |
| labs | `homeCollection` (existing bool) |
| blood banks | `stock: {"A+","A-","B+","B-","AB+","AB-","O+","O-": "have"\|"low"\|"none"\|null} \| null`, `stockUpdatedAt: ms \| null`, `stockStale: bool` |
| ambulances | `available`, `types` (existing) |

**New action:** `bloodbank_stock_set {id?, groups?, refresh?}` → `{ok, id, stock, stockUpdatedAt, stockStale}`.

**Migration:** `V690__bloodbank_stock.sql` (new table `blood_bank_stock`; additive, blue/green safe).

---

## Details

### openNow / hours
- Pharmacy and blood bank: `ListingRules.openNow` (as before) — `open24`, `openAt`–`closeAt` (across midnight too),
  `closedDays`, pharmacy pause (`closedUntil`).
- Lab: `hours` is free text. `ListingFacts.parseHours` understands the first two times of the common forms:
  "সকাল ৮টা – রাত ১০টা" (ভোর/সকাল/দুপুর/বিকাল/বিকেল/সন্ধ্যা/রাত, Bangla or ASCII digits), "08:00-22:00", "8am–10pm",
  and "২৪ ঘণ্টা" / "24 hours" / "24/7". Anything else → `openNow`, `opensAt`, `closesAt` null (never a guess).
- Doctor: `openNow` = a chamber the viewer may see (online chambers only while `online_chamber` is on for both) is in a
  session now (`CareRules.chamberNowOpen`, sessions past midnight included).

### distance
- From the request's point to the listing's point, haversine, rounded to 0.1 km.
- Pharmacies: own lat/lng (exact). Ambulances: the base (2 decimals) or the district centre (approx).
  Labs and blood banks: the upazila centre (approx; no upazila → null). Doctors: the nearest in-person chamber's upazila
  centre (approx).
- The old fields stay: pharmacy `km`, ambulance `km`/`kmApprox` (only with `gps: true`).

### ratingSummary
`{avg, count}` from the same sums as the old `rating`/`ratingCount` (pharmacy overall stars, ambulance stars, doctor
stars). Null when `count == 0`.

### Doctors: nextSlot, feeAmount, specialtyLabel
- `nextSlot.date` = UTC-midnight ms of the Dhaka day (like `appointments.date` and `book.date`).
- The first day from today to 13 days ahead on which a chamber that **takes appointments** (the doctor's main switch and
  the chamber's `appointments`) sits, with the same rules as `book`: today only while one of today's sessions is not
  over. Online chambers count only while `online_chamber` is on for the viewer and the doctor.
- **Capacity:** a chamber with minutes per patient (`patientMin`) takes `session minutes / patientMin` serials a day.
  When its pending + confirmed requests for that day reach that, the day is full and the next free day is used. Only
  if every day of the 14 is full: the first open day with `available: false`. Chambers without `patientMin` have no
  limit (`book` does not limit either — `available` is advice for the card).
- `label`: bn "আজ" / "কাল" / "২৫ অক্টোবর"; en (`lang: "en"`) "Today" / "Tomorrow" / "25 Oct".
- `chamberIndex`: the chamber (index of `chambers[]`, the `book` `chamberIndex`).
- `null` when no chamber can be booked in the 14 days.
- `feeAmount`: taka of the chamber `nextSlot` points to (else the first bookable chamber), `CareRules.feeAmount`
  ("৫০০ টাকা" → 500, "৳1,200" → 1200); `0` = no fee; `null` = not written. The old `fee` text stays.
- `specialtyLabel`: the first two specialties' catalog labels in the viewer's language, joined with ", ".
- Chamber-day closing ("আজ বন্ধ"): there is no separate closing mechanism today; a chamber the doctor turns off
  (`appointments: false`) is skipped. The provider-side quick switch is part 3b (second merge).

### Blood bank stock
- Table `blood_bank_stock (bank_id PK → blood_banks ON DELETE CASCADE, groups JSONB, updated_at, updated_by)`.
- `stock` is null until the owner sets it once; afterwards all 8 keys are present (`null` = unknown).
- `stockStale` = `stockUpdatedAt` older than **48 h** → the client shows "পুরনো তথ্য".
- `bloodbank_mine` items carry `stock`, `stockUpdatedAt`, `stockStale` too (for "আমার কাজ").

**`bloodbank_stock_set`** (logged in, owner only):
- `id`: the blood bank. May be left out when the owner has exactly one (two or three → 400 "ব্লাড ব্যাংক বেছে নিন").
  Not the owner / unknown → 404. Suspended → 403 (same text as `bloodbank_save`). Pending banks may set it (shown once approved).
- `groups`: `{"A+": "have"|"low"|"none"|null, …}` = the **whole** state; a group left out or null = unknown.
  Unknown group key or level → 400.
- `refresh: true` without `groups` = "সব ঠিক আছে — এখন হালনাগাদ": keeps the groups, renews `stockUpdatedAt`
  (400 when the stock was never set).
- Limit: 60 saves per owner per Dhaka day (quota `bbStock`, 429 like other quotas). `updated_by` records the caller.
- Not a caregiver action, not behind health consent.

### Sort
Stable: ties keep the list's own order.
- `near`: `distanceKm` ascending, unknown last.
- `rating`: `ratingSummary.avg` descending, then `count`; unrated last.
- `open`: open now (ambulance: `available`) first, unknown next, closed last; then the earliest free `nextSlot`
  (doctors); then `distanceKm`.
- Pharmacies with a prescription (`rxId`): the chosen sort is applied on top of the prescription-match order.
- Ambulances: `nearestId` and the busy list are not affected by `sort`.

### Search (`q`)
Pharmacies: name, address. Labs: name, address. Blood banks: name, address. Ambulances: name, area.
Doctors: name, degrees, specialty labels (both languages), chamber hospital names (en/bn) and addresses.

### Demo
- Blood banks: the demo owner's bank has a fresh full stock (2 h), the club a partial one (5 h), the government bank a
  3-day-old one (stale); the pending bank none.
- Doctors: the existing demo chambers already give "আজ" (every-day chambers) and later days; lab hours parse
  ("সকাল ৮টা – রাত ১০টা" …).

## Differences from the draft
- **`rating` kept as before** (a number on pharmacies, ambulances, doctors — the released apps read it). The object is
  `ratingSummary: {avg, count} | null`.
- **Doctors' `fee` kept** (old free text); the number is `feeAmount`.
- Added: `distanceApprox`, `nextSlot.chamberIndex`, `bloodbank_stock_set` `id`/`refresh`, list filters above.
