-- =====================================================================================
-- V300 (P3): prescriptions & labs — schema gaps against the Node documents (prescriptions.js, rxnet.js, labprices.js)
-- =====================================================================================

-- prescriptions.doctor.chamber is {name, address, phone} in Node (rx_create); V5 kept only the name
ALTER TABLE prescriptions ADD COLUMN doctor_chamber_address TEXT;
ALTER TABLE prescriptions ADD COLUMN doctor_chamber_phone   TEXT;

-- rxnet.js refillNotifiedAt: the refill reminder was sent for this refill date (cleared by a new dispensing)
ALTER TABLE prescriptions ADD COLUMN refill_notified_at DATE;

-- account.js deleteNow keeps what a doctor wrote (the patient's record) and the lab orders of a removed lab
-- (they stay with the patient; only the home address/phone is stripped) — so no cascades from those owners.
-- The report-review requests of a deleted doctor stay too (only the patient's own are deleted).
ALTER TABLE prescriptions DROP CONSTRAINT IF EXISTS prescriptions_doctor_uid_fkey;
ALTER TABLE lab_orders    DROP CONSTRAINT IF EXISTS lab_orders_lab_uid_fkey;
ALTER TABLE rx_reviews    DROP CONSTRAINT IF EXISTS rx_reviews_doctor_uid_fkey;

-- lab_report items: the lab may mark a text result 'abnormal' (rxnet.js lab_report); long text results
-- (`long: true`) are not compared in the report history
ALTER TABLE lab_report_items DROP CONSTRAINT IF EXISTS lab_report_items_flag_check;
ALTER TABLE lab_report_items ADD CONSTRAINT lab_report_items_flag_check CHECK (flag IN ('low', 'high', 'abnormal', ''));
ALTER TABLE lab_report_items ADD COLUMN long_text BOOLEAN NOT NULL DEFAULT FALSE;

-- reportHistory: a patient's reported orders, newest first
CREATE INDEX lab_orders_patient_reported_idx ON lab_orders (patient_uid, reported_at DESC) WHERE status = 'reported';
-- lab_order_get / rx_activity: report reviews by prescription
CREATE INDEX rx_reviews_rx_idx ON rx_reviews (rx_id);
