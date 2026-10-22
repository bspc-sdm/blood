-- Online (video) chambers + the bKash fee flow (spring-p15, 23 Oct 2026 — docs/CHAMBERS-ONLINE.md).
--
-- A chamber is either at a place (in_person, every chamber so far) or online: no hospital, no address; days/times,
-- serials, fee as before, plus the doctor's own bKash number (shown to a patient after booking) and a short note.
ALTER TABLE provider_chambers ADD COLUMN mode TEXT NOT NULL DEFAULT 'in_person' CHECK (mode IN ('in_person', 'online'));
ALTER TABLE provider_chambers ADD COLUMN bkash_number TEXT CHECK (bkash_number ~ '^01[0-9]{9}$');
ALTER TABLE provider_chambers ADD COLUMN note TEXT CHECK (length(note) <= 200);

-- An appointment in an online chamber (derived from the chamber's mode at booking; appt_join reads it).
-- pay_state: NULL = no fee to pay (in person, or an online chamber without a fee); unpaid → claimed (patient: "টাকা
-- পাঠিয়েছি", optional bKash TrxID in pay_ref) → paid (doctor/assistant: "ফি পেয়েছি"); the doctor may reset claimed →
-- unpaid ("পাইনি", pay_note = the reason). bkash_number / fee / no_fee / online_note are copied at booking (the number
-- and note follow later chamber edits while the appointment is open).
ALTER TABLE appointments ADD COLUMN online BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE appointments ADD COLUMN pay_state TEXT CHECK (pay_state IN ('unpaid', 'claimed', 'paid'));
ALTER TABLE appointments ADD COLUMN pay_ref TEXT CHECK (length(pay_ref) <= 30);
ALTER TABLE appointments ADD COLUMN pay_note TEXT CHECK (length(pay_note) <= 200);
ALTER TABLE appointments ADD COLUMN pay_claimed_at TIMESTAMPTZ;
ALTER TABLE appointments ADD COLUMN pay_paid_at TIMESTAMPTZ;
ALTER TABLE appointments ADD COLUMN pay_paid_by TEXT;
ALTER TABLE appointments ADD COLUMN bkash_number TEXT;
ALTER TABLE appointments ADD COLUMN fee TEXT;
ALTER TABLE appointments ADD COLUMN no_fee BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE appointments ADD COLUMN online_note TEXT;
ALTER TABLE appointments ADD CONSTRAINT appointments_pay_online CHECK (pay_state IS NULL OR online);
CREATE INDEX appointments_provider_online_idx ON appointments (provider_uid, pay_state) WHERE online;
