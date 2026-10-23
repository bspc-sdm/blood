# Lifolo — Medical systems + new professionals — shared contract

The owner approved this on 2026-10-23. The backend agent owns `backend/docs/PRACTICE.md`, which is final once it is on main.

## 1. Doctor medical system
- A new field on doctor providers: `system: "allopathy" | "homeopathy" | "unani" | "ayurveda"`. Every existing doctor gets `allopathy` (migration). Dentists (BDS) are `allopathy` with the dental specialty.
- Registering bodies:

| system | registering body | degree hints |
|---|---|---|
| allopathy | BMDC | MBBS / BDS |
| homeopathy | বাংলাদেশ হোমিওপ্যাথিক বোর্ড | DHMS / BHMS |
| unani | বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড | DUMS / BUMS |
| ayurveda | বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড | DAMS / BAMS |

  The registration number and certificate photo go through the existing badge verification flow. Admin review shows the system and the body.
- Changing `system` after approval sends the badge back to re-verification.
- Display: the system label always appears next to the name in every list, profile, appointment and prescription header: "এলোপ্যাথি", "হোমিওপ্যাথি", "ইউনানি", "আয়ুর্বেদ".
  - Never auto-prefix "ডা." for non-allopathic systems. Show the name plus the degree text the provider entered.
  - (The owner has a legal check pending on title use.)
- Specialties: the catalog is per system. Homeopathy, unani and ayurveda get a short generic list plus "সাধারণ".
- Search: `providers {system?}` filter. With no filter, all systems are shown, each clearly labelled. Patients get a filter chip row: সব / এলোপ্যাথি / হোমিওপ্যাথি / ইউনানি / আয়ুর্বেদ.
- Prescriptions:
  - Non-allopathic prescriptions use free-text medicine lines. They get no allopathic drug-catalog autocomplete, no interaction checks, and no pharmacy stock matching.
  - "Send to pharmacy" is hidden for them.
  - Lab tests are allowed for all systems.
  - The prescription PDF and verify page show the system.

## 2. New professional types
- Provider `type` adds `physio` (ফিজিওথেরাপিস্ট) and `psychologist` (ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর).

| type | verification | services | prescriptions |
|---|---|---|---|
| physio | degree certificate (BPT/MPT) + professional body membership if any | chamber, online (video), home visit | "পরামর্শ ও ব্যায়াম" notes only; NO medicines section |
| psychologist | degree (MS/MPhil clinical/counselling psychology) + Bangladesh Clinical Psychology Society membership if any | chamber, online (video) — no home visit | session notes / advice only; NO medicines (they cannot prescribe drugs); show "ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান" |

- Specialty catalogs:
  - physio: back/neck, sports injury, stroke rehab, pediatric, post-surgery
  - psychologist: depression/anxiety, child & adolescent, couple/family, addiction counselling, trauma
- Psychologist privacy: their appointments and notes are never visible to caregivers unless the patient explicitly shares them (default off). Push text is generic: "অ্যাপয়েন্টমেন্ট আপডেট".
- Calls and chat: the same appointment relations apply automatically.
- Everything uses the existing chamber, appointment, online chamber, fee and receipt flows. Receipts use kind `chamber`.

## 3. Feature keys (register them in Features with Bangla names)
- `practice_systems` (চিকিৎসা পদ্ধতি): the system field, labels, filter and prescription rules.
  - When it is off, everyone is treated as allopathy and the UI shows no system choice.
  - Non-allopathic doctors cannot register while it is off.
- `pro_physio` (ফিজিওথেরাপিস্ট)
- `pro_psychologist` (সাইকোলজিস্ট / কাউন্সেলর)

## 4. Demo
DemoRefresh seeds, in the demo world only:
- one demo doctor for each non-allopathic system
- one physio and one psychologist, each with an in-person and an online chamber
- a couple of appointments

## 5. Tests
- Integration tests for: the migration default, the system filter, the label in responses, the re-verification on change, the prescription rules (free text, no pharmacy send, no medicines for physio/psychologist), psychologist caregiver privacy, the feature-off behaviour and demo separation.
- Manual tests in docs/test-plan/manual/20-practice.md, in the same format as 17-calls.md.

---

## মালিকের জন্য একটা আইনি নোট (অনিশ্চিত — উকিলকে দিয়ে যাচাই করান)

> **নিশ্চিত নই:** বাংলাদেশে নামের আগে "ডা." (ডাক্তার) লেখা সম্ভবত শুধু BMDC-নিবন্ধিত চিকিৎসকদের (MBBS/BDS) জন্য সীমিত —
> বাংলাদেশ মেডিকেল অ্যান্ড ডেন্টাল কাউন্সিল আইন (BMDC Act, ২০১০) অনুযায়ী অন্যরা "ডা." পদবি ব্যবহার করলে তা আইনত নিষিদ্ধ বা
> শাস্তিযোগ্য হতে পারে। হোমিওপ্যাথি, ইউনানি, আয়ুর্বেদ বোর্ডের নিবন্ধিত চিকিৎসক, ফিজিওথেরাপিস্ট বা সাইকোলজিস্টরা "ডা." লিখতে পারবেন
> কি না, এ নিয়ে আদালতের রায় ও বোর্ডগুলোর নিজস্ব নিয়ম আলাদা হতে পারে। আমি আইনজীবী নই — এটা আইনি পরামর্শ নয়।
> **চালু করার আগে একজন উকিলকে দিয়ে যাচাই করান।** ততদিন সার্ভার নিজে কখনো "ডা." বসায় না (নাম + প্রোভাইডারের লেখা ডিগ্রিই দেখায়),
> আর ডেমোর অ-এলোপ্যাথিক চিকিৎসক/ফিজিও/সাইকোলজিস্টের নামে "ডা." নেই। প্রোভাইডার নিজে নামে "ডা." লিখলে সেটা আটকানো হবে কি না,
> উকিলের মতামতের পর ঠিক করবেন।

---

## Backend implementation (spring-p21) — the shapes the apps use

Everything below is on the server; the apps only show it. Migration **V680** (`practice_systems`).

### Data
- `providers.system`, `badges.system`: `allopathy | homeopathy | unani | ayurveda`, doctors only (NULL for every other
  profession). V680 set every existing doctor to `allopathy`; a NULL on a doctor is still read as `allopathy`.
- `badges.reverify_from`: the system before a change that is being verified again.
- `prescriptions.doctor_type` (`doctor | physio | psychologist`), `doctor_system`, `free_text`.
- `care_links.share_psych` (default false).
- Professions everywhere (`badge_apply`, `providers`, appointments, answers/tips): `physio`, `psychologist` added.
- Catalog lists (`catalog_get` → `lists.specialty[...]`): `doctor` (allopathy, as before), `doctor_homeopathy`,
  `doctor_unani`, `doctor_ayurveda` (`alt_general` "সাধারণ", `alt_skin_hair`, `alt_women`, `alt_child`,
  `alt_joint_pain`, `alt_digestive`, `alt_allergy`), `physio` (`physio_back_neck`, `physio_sports`, `physio_stroke`,
  `physio_pediatric`, `physio_post_surgery`), `psychologist` (`psy_depression_anxiety`, `psy_child`,
  `psy_couple_family`, `psy_addiction`, `psy_trauma`); `lists.homeService.physio` (`physio_home_exercise`,
  `physio_home_stroke`, `physio_home_post_op`, `physio_home_elderly`, `physio_home_pain`). Admin-editable like the
  others.

### Labels — on every provider, appointment and prescription
Three fields, added next to `type`:
```
system:      "allopathy" | "homeopathy" | "unani" | "ayurveda" | null   (null = not a doctor, or practice_systems off)
systemLabel: "এলোপ্যাথি" | "হোমিওপ্যাথি" | "ইউনানি" | "আয়ুর্বেদ" | null
typeLabel:   "ডাক্তার" | "নার্স" | "পুষ্টিবিদ" | "ফিটনেস ট্রেইনার" | "ফিজিওথেরাপিস্ট" | "ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর"
```
Where: `providers` items, `provider_me.provider`, `home_providers` items, `my_appointments` items (the provider's —
also `private: true` for a psychologist's), `rx_me`, `rx_assisting` items, every prescription's `doctor` object
(`rx_get`, `rx_mine`, `rx_doctor_list`, `rx_patient_history`), the public `/rx/{code}` page (the label next to the name,
the registering body instead of "BMDC"), `me.badge`, `admin_badges` items. The server never adds "ডা.".

### Lists
- `providers {type: "doctor", system?, …}`: `system` filters; no filter = all systems. `facets.systems` =
  `[{code, n}]` for the chip row (সব / এলোপ্যাথি / হোমিওপ্যাথি / ইউনানি / আয়ুর্বেদ). `specialty` accepts any system's code.
- `providers {type: "physio" | "psychologist"}`; `home_providers {type: "physio"}` (psychologists: no home visits).

### Badge / verification
- `badge_apply {type, system?, regNo, workplace, docs}`:
  - `type` adds `physio`, `psychologist`; `system` only matters for `doctor` (default `allopathy`, unknown → 400 `bad_system`).
  - Documents: non-allopathic doctor → the registering body's certificate (`cert`, **required**); physio → degree
    certificate BPT/MPT (`cert`, required) + professional body membership (`member`, optional); psychologist → MS/MPhil
    certificate (`cert`, required) + Bangladesh Clinical Psychology Society membership (`member`, optional).
  - An **approved** doctor sending another `system` → `{ok, reverify: true}`: the badge goes back to `pending` with the new
    system, number, workplace and certificate; verification cleared; out of the lists while pending (open appointments
    stay). On approval the listing takes the new system, its specialties are cleared and it is unlisted (the doctor saves
    the chambers again with the new system's specialties), and future appointments booked under the old system are
    cancelled ("ডাক্তার চিকিৎসা পদ্ধতি বদলেছেন"). Rejected → like any rejected badge.
  - Without `system` an approved badge sends a number/workplace correction as before (the system stays).
- Admin review (`admin_badges`): `system`, `systemLabel`, `body` (registering body), `degreeHint`, `reverifyFrom`;
  the panel's checks follow the system (BMDC checks for allopathy; `cert_no`, `cert_body`, `cert_name`, `workplace` for
  the boards; `cert_name`, `cert_degree`, `workplace` for physio/psychologist).
- `provider_save` never takes a system: the listing's system is the verified badge's. Specialties are checked against
  that system's/profession's catalog.

### Prescriptions (enforced on the server)
- `rx_me` → `{…, type, system, systemLabel, typeLabel, rxRules}`; every prescription view has `doctorType`, `freeText`,
  `pharmacy` and `rxRules`:
  ```
  rxRules: {medicines, freeText, drugCatalog, interactions, pharmacy, tests, section, notice}
  allopathy:            {true,  false, true,  true,  true,  true,  null, null}
  homeopathy/unani/ayu: {true,  true,  false, false, false, true,  null, null}
  physio:               {false, false, false, false, false, false, "পরামর্শ ও ব্যায়াম", null}
  psychologist:         {false, false, false, false, false, false, "সেশন নোট ও পরামর্শ", "ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান"}
  ```
- `rx_create`: physio/psychologist write their own notes (their assistants enter patients, as for doctors). Medicines → 400 `no_medicines`, tests →
  400 `no_tests`; notes go in `advice` (up to 3000 characters), `complaints`, `diagnosis`, follow-up.
  A non-allopathic doctor's `medicines` are free-text lines: each `{name (≤200), dose (free text ≤60), days, note}` or a
  plain string; no form/strength/timing; stored with `free_text = true`. Lab tests are allowed for all doctors.
- Pharmacy: `rx_send_pharmacy`, `rx_pharmacy_options`, `rx_pharmacy_open`, `rx_dispense` → 409 `rx_no_pharmacy`
  ("এই প্রেসক্রিপশন ফার্মেসিতে পাঠানো যায় না") for anything but an allopathic doctor's prescription.

### New professions
- Chambers, online (video) chamber, appointments, serial queue, assistants, fees and receipts (kind `chamber`) work as
  for doctors. Physio: home visits (`homeVisit`, physio home services). Psychologist: `homeVisit.enabled` → 400
  `no_home_visit`.

### Psychologist privacy
- A caregiver acting for the patient (`asUid`) does not see the patient's psychologist appointments (`my_appointments`),
  session notes (`rx_mine`, `rx_get`, `rx_activity` → 404) or receipts (`my_invoices`, `my_expenses`, `invoice_get`), and
  cannot act on them (`appointment_update`, `appointment_queue`, `appointment_late`, `appt_pay_*`, `appt_report` → 404;
  `book` with a psychologist → 403 `private_type`).
- `care_share_psych {caretakerUid, share}` (the patient only, never as a caregiver) → `{ok, sharePsych}`; `care_list`
  caretaker items have `sharePsych`. Default off.
- Every push and bell entry about a psychologist's appointment, notes or receipt (both sides) reads **"অ্যাপয়েন্টমেন্ট
  আপডেট"** ("Appointment update") with no body; `data.private = "true"`. Caregivers get them only when shared.
  `PushService` does this for items tagged `Practice.PRIVATE_KEY` and for any item whose `type: appointment`+`id`,
  `rxId` or `invoiceId` points to a psychologist's.

### Feature switches (backend/docs/FEATURES.md)
- `practice_systems` off for the viewer: `system`/`systemLabel` null, no `facets.systems`, the filter ignored,
  non-allopathic doctors not listed and not bookable; `badge_apply` with a non-allopathic system → 404 `feature_off`.
  Stored prescriptions keep their rules.
- `pro_physio` / `pro_psychologist` off: `badge_apply`, `providers`, `home_providers`, `provider_save`, `book`, `rx_*`
  writing for that profession → 404 `feature_off`.

### Demo (`DemoPractice`, step 4 after the doctors)
- Homeopath "ডেমো হোমিও চিকিৎসক আনোয়ার হোসেন", unani "ডেমো ইউনানি চিকিৎসক রফিকুল ইসলাম", ayurveda "ডেমো আয়ুর্বেদিক চিকিৎসক
  সুমিতা রায়" (uids `demo-x-pr-hom`, `-pr-unani`, `-pr-ayur`); physio "ডেমো ফিজিওথেরাপিস্ট সাবরিনা ইসলাম" (`demo-x-pr-physio`,
  home visits too); psychologist "ডেমো কাউন্সেলর নাদিয়া রহমান" (`demo-x-pr-psy`). In-person chambers at the demo hospital;
  the online chambers only where demo online chambers are on (`DEMO_ONLINE_CHAMBERS`, as for the demo doctor).
- Appointments: the physio's patient today (#1), the homeopath's patient on the next open day, and the demo patient's own
  pending request with the psychologist (the demo caregiver does not see it).

### Tests
- `PracticeIT` (migration default, filter + labels, re-verification, prescription rules, physio/psychologist, privacy +
  generic pushes, switches off/on), `DemoPracticeIT` (demo seeds, demo/real separation, wipe).
- Manual: `docs/test-plan/manual/20-practice.md`.
