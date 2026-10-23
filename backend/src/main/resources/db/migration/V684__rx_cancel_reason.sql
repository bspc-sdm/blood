-- Why the doctor cancelled a prescription (rx_cancel {id, reason, note?} — backend/docs/RX-STATE.md "Cancellation reason").
-- The patient (and a caregiver acting for the patient) sees it; the public /rx/{code} page, pharmacies, labs and pushes never do.
--
-- Backward compatible (blue/green, deploy/ZERO-DOWNTIME.md): two new nullable columns, no default, no backfill. The
-- previous release never reads them; a cancellation it makes during the switch leaves them NULL, which the new code
-- shows as cancelled without a reason (state.cancelReason null). New cancellations always carry a reason.
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS cancel_reason TEXT;
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS cancel_note TEXT;

ALTER TABLE prescriptions DROP CONSTRAINT IF EXISTS prescriptions_cancel_reason_check;
ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_cancel_reason_check CHECK (
    cancel_reason IS NULL
    OR cancel_reason IN ('wrong_medicine', 'wrong_patient', 'replaced', 'patient_reported', 'other')
);
ALTER TABLE prescriptions DROP CONSTRAINT IF EXISTS prescriptions_cancel_note_check;
ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_cancel_note_check CHECK (cancel_note IS NULL OR char_length(cancel_note) <= 200);
