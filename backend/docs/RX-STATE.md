# Prescription state ("আমার প্রেসক্রিপশন")

The patient's list shows where each prescription is now. The server computes it (`RxState.kt`); the web and the app
only translate the keys, so both show the same thing. Almost everything comes from rows that already exist
(`prescription_dispensings`, `rx_orders`, `lab_orders`, `rx_reviews`, `prescriptions.status / cancelled_at /
follow_up_date / refill_at`, the patient's other prescriptions). The only new data is the patient's own mark
(V683: `prescriptions.patient_done_at`, `patient_reopened_at`, both nullable).

## Where

- `rx_mine`: every item gets `state` (new field; older apps ignore it, every old field is unchanged).
- `rx_get`: `item.state` with `timeline` too — only for the patient (or a caregiver acting for the patient). The
  writing doctor's view has no `state` (the patient's pharmacy/lab orders are private, as in `rx_activity`).
- Caregivers (`asUid`) get the same state for the patient. A psychologist's notes stay hidden from a caregiver unless
  the patient shares them (PRACTICE.md) — the state is only added to what is already shown.

```
state: {
  phase: "active" | "past",
  primary: <key below>,               // the one status line
  tone: "primary" | "good" | "info" | "error" | "neutral" | "muted",
  cancelled: bool, cancelledAt: ms | null,
  cancelReason: <reason key> | null,  // why (below); null when not cancelled, or cancelled before V684 / by the previous release
  cancelNote: string | null,          // the doctor's note, if any
  medicine: { state, bought, total, pharmacyName, mode } | null,   // null: no medicines, or not bought through pharmacies
  lab: { state, orders, reported, labName, reviewed } | null,      // null: no tests and no lab order
  followUpDate: ms | null,             // only while it is today or ahead and the prescription is active
  lastActivityAt: ms,
  pastReason: "cancelled" | "done" | "superseded" | "finished" | "course_end" | "stale" | null,
  doneAt: ms | null, reopenedAt: ms | null,                         // the patient's mark (rx_mark_done)
  supersededBy: { id, at } | null,      // the newer prescription, when pastReason is "superseded"
  courseEnd: ms | null,                 // UTC midnight of the first day after the longest medicine; null = no course end
  timeline?: [{ kind, at, name?, mode?, count?, ahead? }]          // rx_get only
}
```

## Medicine states (`medicine.state`)

Only for an allopathic doctor's medicine list (`pharmacy: true`); free-text (homeopathy …) lines and notes have no
medicine state. Bought counts come from the dispensings (`medStatus`): a medicine is bought when the pharmacy gave
the whole course (tablets/capsules with a known dose), or anything for other forms.

| key | bn | when |
|---|---|---|
| `out` | ডেলিভারির পথে | an open online order is on the way |
| `ready` | ওষুধ তৈরি — দোকান থেকে নিন | an open shop order is ready |
| `accepted` | ফার্মেসি গ্রহণ করেছে | an open online order was accepted |
| `sent` | ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা | sent, no answer yet |
| `running_out` | ওষুধ শেষ হয়ে আসছে | not all bought, something bought, and today is from the refill reminder day (`refill_at`, 2 days before they end) for 7 days |
| `all_bought` | সব ওষুধ কেনা হয়েছে | every medicine bought, or an online order delivered with nothing counted |
| `declined` | ফার্মেসি দিতে পারেনি | the last closed order was cancelled by the pharmacy and nothing was bought after it |
| `partial` | আংশিক কেনা (৩/৫) | some bought (`bought`/`total` medicines) |
| `not_bought` | ওষুধ কেনা হয়নি | nothing yet (a withdrawn order counts as nothing) |

Several orders open at once: the most advanced one speaks (`out` > `ready` > `accepted` > `sent`).

## Tests (`lab.state`)

| key | bn |
|---|---|
| `pending` | পরীক্ষা বাকি (no live lab order; a lab that cancelled counts as none) |
| `sent` | ল্যাবে পাঠানো |
| `received` | নমুনা নেওয়া হয়েছে |
| `reported` | রিপোর্ট এসেছে (`reviewed`: the doctor checked it) |

With several live lab orders the least advanced one decides; `reported`/`orders` give the count.

## The one line (`primary`) — priority

1. `cancelled` — বাতিল করেছেন ডাক্তার — <reason> (greyed card; the reason below, nothing after the dash when null)
2. the medicine state, in the order of the table above (open order → running out → all bought → declined → partial → not bought)
3. no medicine state: `lab_<state>` (the tests line becomes the main one)
4. nothing else: `follow_up` (ফলো-আপ: date) while the follow-up is ahead
5. `written` (notes only)

The cards show the tests as a second small line when `lab` is not null, and "ফলো-আপ: date" when `followUpDate` is set.
In পূর্বের the reason shows as a small line too: "আপনি শেষ করেছেন" (`done`) or "নতুন প্রেসক্রিপশনে বদলেছে · date"
(`superseded`).

## Active or past (`phase`) — the tabs চলমান / পূর্বের

Many patients buy their medicines outside the app, so "everything bought" alone would leave চলমান full of old
prescriptions. In order, the first rule that applies (`pastReason` says which):

1. cancelled by the doctor → **past** (`cancelled`)
2. the patient (or a caregiver) marked it **"শেষ হয়েছে"** → **past** (`done`; the card says "আপনি শেষ করেছেন")
3. something still waiting — an open pharmacy order (sent / accepted / ready / on the way) or a lab order sent / sample
   taken → **active**
4. **superseded**: the same doctor wrote a newer prescription (not cancelled) for the same person → **past**
   (`superseded`; "নতুন প্রেসক্রিপশনে বদলেছে · date", with a link to the newer one in the detail). The same person:
   the same paper patient (`rx_patient_id`) when both have one, otherwise the same patient name on the same account
   (a family member is a different name, so a different person). Different doctors never supersede each other.
5. a follow-up date today or ahead → **active**
6. **finished**: every medicine bought (or none to buy) and every test reported (or none), at least one of the two
   tracked → **past** at once (`finished`)
7. **course end**: the course ends on the writing day + the longest medicine's days; **3 days after** that → **past**
   (`course_end`). Purchases do not stretch it.
8. **no course end** — no medicines, or any medicine marked "চলবে" (continuing, stored as `days = 0`): **30 days**
   after the writing day or the last activity, whichever is later → **past** (`stale`). Activity = the last
   purchase, the follow-up date, the refill day + 2, the last pharmacy order / lab order / report review change, or
   "আবার চলমান করুন".

**"আবার চলমান করুন"** (`rx_mark_done` with `done: false`) clears the mark and makes it active again: after that the
"finished" and course-end rules no longer apply, only a prescription from the same doctor written *after* that
supersedes it, and it goes back to পূর্বের 30 days after the reopening with nothing new (rule 8), or when the patient
marks it done again, or when the doctor cancels it. It is offered on every পূর্বের prescription that is not cancelled;
"শেষ হয়েছে" on every চলমান one.

## rx_mark_done

`{id, done: true | false}` (missing `done` = true). Only the prescription's patient — or a caregiver acting for the
patient with `asUid` (in CARE_ACTIONS) — never the writing doctor or anybody else (404). A psychologist's notes stay
hidden from a caregiver unless the patient shares them (404, PRACTICE.md). Answers `{ok, item}`: the patient's view
with the new state and timeline. Not behind health-data consent (it enters no health data). Account deletion clears
the marks with the patient link.

## Timeline (`rx_get` → `state.timeline`)

`written`, `pharmacy_sent` (name, mode), `pharmacy_accepted|ready|out|delivered|cancelled|withdrawn` (the order's last
change), `bought` (pharmacy, `count` medicines), `lab_sent`, `lab_received`, `lab_reported`, `lab_cancelled`,
`reviewed` (the doctor checked the report), `superseded` (`id` of the newer one), `marked_done`, `reopened`,
`cancelled` (`reason?`, `note?`), `follow_up` (`ahead` while to come). Oldest first. The
orders keep only their last status time, so an order shows "sent" + its latest step.

## Cancellation reason (`rx_cancel {id, reason, note?}`)

The writing doctor (never an assistant or a caregiver) cancels with a required reason (`RxCancel.kt`; an app that sends
none gets 400 `cancel_reason` — owner 2026-10: no old app versions in use):

| key | bn | en |
|---|---|---|
| `wrong_medicine` | ভুল ওষুধ/মাত্রা | Wrong medicine/dose |
| `wrong_patient` | ভুল রোগী | Wrong patient |
| `replaced` | নতুন প্রেসক্রিপশন দিয়েছি | Gave a new prescription |
| `patient_reported` | রোগী জানিয়েছেন সমস্যা | Patient reported a problem |
| `other` | অন্য কারণ | Other reason |

`note`: optional, up to 200 characters; with `other` required, 3–200 (400 `cancel_note`). The first cancellation wins
(a second call answers ok and keeps the first reason). Stored in `prescriptions.cancel_reason` / `cancel_note`
(migration V684, nullable — a cancellation made by the previous release during a blue/green switch has NULL, shown as
cancelled without a reason).

Who sees it: the patient and a caregiver acting for the patient (`state.cancelReason/cancelNote`, the timeline's
`cancelled` step). Psychologist notes stay hidden from caregivers as before; physio/psychologist notes are cancelled the
same way. **Never** the public `/rx/{code}` page (only "বাতিল"/"Cancelled" — the note may hold medical details), the
pharmacy's code view (`status: cancelled`, "এই প্রেসক্রিপশন বাতিল" — no reason), the lab's code view (409 "এই
প্রেসক্রিপশন বাতিল") or any push (a cancellation sends none). The writing doctor's own view is unchanged.

Tests: `RxStateTest` (unit), `RxStateIT`, `RxCancelIT` (integration).
