-- =====================================================================================
-- V200 (P2): doctors, appointments, home care, community content — the fields the Node documents have that V2/V3
-- did not map yet (appointments.js, care.js, careaccess.js, hospitals.js).
-- =====================================================================================

-- hospitals/{id}.status: hospitals.js findHospital/userAdded skip status 'removed' (set by hand in the console);
-- the port lists only 'approved' ones, so 'hidden' and 'removed' both stay out of every list.
ALTER TABLE hospitals DROP CONSTRAINT IF EXISTS hospitals_status_check;
ALTER TABLE hospitals ADD CONSTRAINT hospitals_status_check CHECK (status IN ('approved', 'hidden', 'removed'));

-- providers/{uid}: which shape the chambers have (care.js chambersOf):
--   NULL  = no `chambers` field (a document created by another path, e.g. the queue's average or a badge change)
--   FALSE = `chambers[]` (provider_save, the rows in provider_chambers)
--   TRUE  = legacy single chamber of app ≤ 1.3 (legacySave: chamber/district/schedule/fee as provider_chambers idx 0)
ALTER TABLE providers ADD COLUMN legacy_chamber BOOLEAN;
-- legacy free-text specialty (legacySave; provider() still shows it)
ALTER TABLE providers ADD COLUMN specialty TEXT;
-- the `homeVisit` object exists (provider_save with homeVisit, suspendProvider / blockHomeCare set homeVisit.enabled)
ALTER TABLE providers ADD COLUMN home_visit_set BOOLEAN NOT NULL DEFAULT FALSE;

-- chambers[]: a place the professional typed in (nutritionist/trainer: gym, office) and minutes per patient (queue ETA)
ALTER TABLE provider_chambers ADD COLUMN custom BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE provider_chambers ADD COLUMN address TEXT;
ALTER TABLE provider_chambers ADD COLUMN patient_min INTEGER CHECK (patient_min BETWEEN 1 AND 120);

-- appointments booked for a family member: patientAge / patientGender at booking time
ALTER TABLE appointments ADD COLUMN patient_age INTEGER;
ALTER TABLE appointments ADD COLUMN patient_gender TEXT NOT NULL DEFAULT '';

-- queue.js: today's appointments of a doctor (queue_today, load) and the daily reminders
CREATE INDEX appointments_provider_date_idx ON appointments (provider_uid, visit_date);
