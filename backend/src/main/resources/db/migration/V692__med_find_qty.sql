-- "ওষুধ খুঁজুন": how much the buyer needs, and how much a pharmacy has (backend/docs/MEDFIND.md).
--
-- med_finds.qty/unit: 1–999 in a unit that fits the drug's form; NULL = not said (RC3 and older apps send nothing,
-- shown as "পরিমাণ বলা নেই").
-- med_find_targets.have_qty/have_unit: the shop's "আছে" with how many (1–9999) — in the find's unit, or for a find
-- without an amount in the unit the shop picked; NULL with have = TRUE = "আছে" without an amount (RC3 pharmacy app).
-- med_find_targets.dismissed_at: the shop's "বাদ দিন" — hides the request from its own list only.
-- Backward compatible (blue/green): new nullable columns only — the old code neither writes nor reads them, and its
-- `have` TRUE/FALSE keeps meaning "has it" / "not shown to the buyer".
ALTER TABLE med_finds
    ADD COLUMN qty  INTEGER CHECK (qty BETWEEN 1 AND 999),
    ADD COLUMN unit TEXT CHECK (unit IN ('strip', 'pcs', 'bottle', 'vial', 'unit'));
ALTER TABLE med_find_targets
    ADD COLUMN have_qty     INTEGER CHECK (have_qty BETWEEN 1 AND 9999),
    ADD COLUMN have_unit    TEXT CHECK (have_unit IN ('strip', 'pcs', 'bottle', 'vial', 'unit')),
    ADD COLUMN dismissed_at TIMESTAMPTZ;
