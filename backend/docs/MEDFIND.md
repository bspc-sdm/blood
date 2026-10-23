# "ওষুধ খুঁজুন" — which nearby pharmacy has it, and how much

Package `com.lifolo.pharmacy` (`MedFindService`, `MedQty`), migration **V692** (`med_finds.qty/unit`,
`med_find_targets.have_qty/have_unit/dismissed_at`). Apps: Android `MedFind.kt` (`MedQty`), web
`web-vue/src/sections/services/medfind.ts`.

## Flow
1. The buyer picks a medicine from the DGDA list (`drug_search`), then says **how much** ("কতটা লাগবে"): a stepper
   1–999 and a unit that fits the drug's form. Default: 1 of the first unit (tablets: ১ পাতা).
2. `med_find {i, district, upazilaId, lat, lng, qty, unit}` pushes up to 15 nearby open approved pharmacies
   (unchanged: find setting on, ≤30 a day per shop, quota `medFind` 5 a day for the buyer). `qty` is required:
   outside 1–999 or missing → 400 "পরিমাণ ১ থেকে ৯৯৯-এর মধ্যে দিন" (before the quota is used).
3. The shop's "ওষুধ খোঁজা" list (`med_find_requests`) shows "<ওষুধ> — ৩ পাতা লাগবে". A shop that has it writes **how
   many it has** in the buyer's unit and sends it (`med_find_answer {id, haveQty}`, 1–9999); sending again changes
   the amount. There is **no "নেই"**: a shop that does not have it just leaves it, or taps the quiet **"বাদ দিন"**
   (`med_find_answer {id, dismiss: true}`), which only hides it from that shop's own list — the buyer is never told
   and sees nothing from that shop.
4. The buyer (`med_finds_mine`) sees only shops that have it, with name, distance and the existing call/report
   actions: "আছে — ২০ পিস · পুরোটা আছে" when the amount is at least what was asked, "আছে — ১০ পিস (আপনার দরকার ২০)"
   when it is less. Order: shops with all of it first, then by distance.

## Units (`MedQty.unitsFor`, the same rules in the apps)
| form (DGDA, lower-case contains) | units (first = default) | Bangla | English |
|---|---|---|---|
| inject / infusion / vaccine | `vial` | ২টি/ভায়াল | 2 vials |
| tablet / capsule / cozycap | `strip`, `pcs` | ৩ পাতা · ১০ পিস | 3 strips · 10 pcs |
| nebuliser / respirator | `unit` | ২টি | 2 pcs |
| syrup / suspension / elixir / linctus / oral solution | `bottle` | ১ বোতল | 1 bottle |
| anything else | `unit` | ২টি | 2 pcs |

A unit that does not fit the form becomes the form's default. A shop always answers in the find's unit (`unit` it
sends is ignored); a find stored before V692 (no amount) takes the form's default unit.

## API
- `med_find_requests` items: `{id, drug, area, km, answer: "yes"|null, createdAt, qty, unit, haveQty, haveUnit}`;
  dismissed requests are left out.
- `med_finds_mine` items: `{…, qty, unit, have: [{id, name, phone, address, openNow, open24, km, at, haveQty,
  haveUnit, full}]}` — `have` holds only shops that said they have it.
- `med_find_answer`: `haveQty` missing or outside 1–9999 (also an old app's `have: true/false`) → 400
  "কতটা আছে লিখুন (১ থেকে ৯৯৯৯)"; 403 not an approved pharmacy; 404 not sent to this shop; 409 closed (not for dismiss).

## Pushes (privacy and wording unchanged)
- To the shops: title "কাছে একজন ওষুধ খুঁজছেন" (English title for English users), body
  "<ওষুধ> — ৩ পাতা লাগবে। আপনার কাছে আছে?" (Bangla digits). Never the buyer's name, phone or exact place.
- To the buyer, on a shop's first answer: "<দোকান>-এ আছে" / "<ওষুধ> — ফোন করে নিশ্চিত হয়ে নিন" as before —
  never a stock number on the lock screen. A changed amount does not push again.

## Deploys
There are no users on older apps (RC4 replaces RC3), so old requests just get a clean 400. Blue/green: V692 adds
nullable columns only; the old code never reads or writes them, and a row it writes without `qty` still lists.
