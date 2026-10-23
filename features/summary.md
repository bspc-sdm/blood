## ফিচার সুইচ — দেখা (`show`)

- এখন: চালু (সবার জন্য) 24
- নির্ভরতা: সব মেটানো (FEATURES.md → Features.NEEDS)
- `pay_bkash_pgw` চালু, কিন্তু সার্ভারে BKASH_* চাবি নেই: Lifolo-র নিজের সাবস্ক্রিপশনের অনলাইন পেমেন্ট 503 `pgw_unavailable` ("অনলাইন পেমেন্ট এখন চালু নেই") দেবে — জেনেশুনে রাখা; অন্য কিছু ভাঙে না।

| key | mode | পাইলট | ডেমো | লাগে |
|---|---|---|---|---|
| `calls_audio` | on | 0 | হ্যাঁ | — |
| `calls_video` | on | 0 | হ্যাঁ | calls_audio |
| `appt_video` | on | 0 | হ্যাঁ | online_chamber |
| `chat` | on | 0 | হ্যাঁ | — |
| `chat_media` | on | 0 | হ্যাঁ | chat |
| `online_chamber` | on | 0 | হ্যাঁ | — |
| `inv_pharmacy` | on | 0 | হ্যাঁ | — |
| `inv_lab` | on | 0 | হ্যাঁ | — |
| `inv_chamber` | on | 0 | হ্যাঁ | — |
| `inv_ambulance` | on | 0 | হ্যাঁ | — |
| `my_expenses` | on | 0 | হ্যাঁ | — |
| `desktop` | on | 0 | হ্যাঁ | — |
| `pay_manual` | on | 0 | হ্যাঁ | — |
| `pay_chamber_advance` | on | 0 | হ্যাঁ | pay_manual |
| `pay_delivery` | on | 0 | হ্যাঁ | pay_manual, inv_pharmacy |
| `pay_lab` | on | 0 | হ্যাঁ | pay_manual, inv_lab |
| `pay_ambulance` | on | 0 | হ্যাঁ | pay_manual, inv_ambulance |
| `pay_due` | on | 0 | হ্যাঁ | pay_manual |
| `pay_bkash_pgw` | on | 0 | হ্যাঁ | — |
| `practice_systems` | on | 0 | হ্যাঁ | — |
| `pro_physio` | on | 0 | হ্যাঁ | — |
| `pro_psychologist` | on | 0 | হ্যাঁ | — |
| `health_consent` | on | 0 | হ্যাঁ | — |
| `title_guard` | on | 0 | — | — |

```
calls_audio=on
calls_video=on
appt_video=on
chat=on
chat_media=on
online_chamber=on
inv_pharmacy=on
inv_lab=on
inv_chamber=on
inv_ambulance=on
my_expenses=on
desktop=on
pay_manual=on
pay_chamber_advance=on
pay_delivery=on
pay_lab=on
pay_ambulance=on
pay_due=on
pay_bkash_pgw=on
practice_systems=on
pro_physio=on
pro_psychologist=on
health_consent=on
title_guard=on
```

### বাইরে থেকে পরীক্ষা (api.lifolo.com)
- ফল: `ok (health ok, অতিথি v=72: 24/24 চালু, ডেমো রোগী লগইন, 24/24 চালু, chat_threads, my_expenses, consent_status ঠিক, ডেমো ডাক্তার লগইন, 24/24 চালু)`
- ok health
- ok chat_threads (chat on): 200 ok, items, cursor
- ok my_expenses (my_expenses on): 200 month, total, byKind, count
- ok consent_status: on=True ask=False version=2026-10-23.1
- ok doctor features + provider_me

**কীভাবে ফেরাবেন:** Actions → "Ops: feature switches" → action `restore` (সর্বশেষ all_on/all_off-এর আগের অবস্থা)। দেখতে: `show`।

_run 39987813710 · গাইড: backend/docs/FEATURES.md → Ops_
