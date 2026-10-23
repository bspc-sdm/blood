# Prescription state ("আমার প্রেসক্রিপশন")

The patient's list shows where each prescription is now. The server computes it (`RxState.kt`); the web and the app
only translate the keys, so both show the same thing. No new table or column: everything comes from rows that
already exist (`prescription_dispensings`, `rx_orders`, `lab_orders`, `rx_reviews`, `prescriptions.status /
cancelled_at / follow_up_date / refill_at`).

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
  medicine: { state, bought, total, pharmacyName, mode } | null,   // null: no medicines, or not bought through pharmacies
  lab: { state, orders, reported, labName, reviewed } | null,      // null: no tests and no lab order
  followUpDate: ms | null,             // only while it is today or ahead
  lastActivityAt: ms,
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

1. `cancelled` — বাতিল করেছেন ডাক্তার (greyed card; the doctor gives no reason today — `rx_cancel` has none)
2. the medicine state, in the order of the table above (open order → running out → all bought → declined → partial → not bought)
3. no medicine state: `lab_<state>` (the tests line becomes the main one)
4. nothing else: `follow_up` (ফলো-আপ: date) while the follow-up is ahead
5. `written` (notes only)

The cards show the tests as a second small line when `lab` is not null, and "ফলো-আপ: date" when `followUpDate` is set.

## Active or past (`phase`) — the tabs চলমান / পূর্বের

In order, the first rule that applies:

1. cancelled by the doctor → **past**
2. a follow-up date today or ahead → **active**
3. something still waiting (an open pharmacy order, or a lab order sent / sample taken) → **active**
4. nothing to track (notes, free-text medicines; no tests) → **past** once the course has ended (the longest medicine's
   days from the writing day) and at least 7 days have passed since writing
5. finished — every medicine bought (or none to buy) and every test reported (or none) → **past**, at once
6. otherwise **past** when nothing happened for 30 days: 30 days after the last of — writing, the last purchase, the
   course end, the follow-up date, the refill day + 2, the last order / lab / review change

So a fully bought prescription with no tests moves to পূর্বের the day the last medicine is bought (or the day after
its follow-up date); one that is never finished moves 30 days after the last purchase, the end of the course or the
follow-up date, whichever is last.

## Timeline (`rx_get` → `state.timeline`)

`written`, `pharmacy_sent` (name, mode), `pharmacy_accepted|ready|out|delivered|cancelled|withdrawn` (the order's last
change), `bought` (pharmacy, `count` medicines), `lab_sent`, `lab_received`, `lab_reported`, `lab_cancelled`,
`reviewed` (the doctor checked the report), `cancelled`, `follow_up` (`ahead` while to come). Oldest first. The
orders keep only their last status time, so an order shows "sent" + its latest step.

Tests: `RxStateTest` (unit), `RxStateIT` (integration).
