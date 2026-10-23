-- "আমার প্রেসক্রিপশন" (backend/docs/RX-STATE.md): the patient — or a caregiver acting for the patient — marks a
-- prescription finished ("শেষ হয়েছে") or ongoing again ("আবার চলমান করুন"). Both NULL = the automatic rules decide.
-- Backward compatible: new nullable columns, nothing else reads them.
ALTER TABLE prescriptions ADD COLUMN patient_done_at TIMESTAMPTZ;
ALTER TABLE prescriptions ADD COLUMN patient_reopened_at TIMESTAMPTZ;
