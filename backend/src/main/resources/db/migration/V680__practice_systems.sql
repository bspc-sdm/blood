-- Medical systems + new professionals (backend/docs/PRACTICE.md).
-- Written to be re-runnable: PracticeIT runs this file again on a "before" row to check the defaults.

-- 1. the professions physio and psychologist wherever a profession is checked (badges, providers, appointments,
--    answers/tips by badge holders). The old CHECKs (all of them name 'nutritionist') are dropped whatever their name.
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.conrelid::regclass::text AS tbl, c.conname
        FROM pg_constraint c
        WHERE c.contype = 'c'
          AND c.conrelid::regclass::text IN ('badges', 'providers', 'appointments', 'answers', 'tips')
          AND pg_get_constraintdef(c.oid) LIKE '%nutritionist%'
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;
END $$;

ALTER TABLE badges ADD CONSTRAINT badges_type_check
    CHECK (type IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'expert', 'physio', 'psychologist'));
ALTER TABLE providers ADD CONSTRAINT providers_type_check
    CHECK (type IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'physio', 'psychologist'));
ALTER TABLE appointments ADD CONSTRAINT appointments_type_check
    CHECK (type IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'physio', 'psychologist'));
ALTER TABLE answers ADD CONSTRAINT answers_badge_check
    CHECK (badge IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'expert', 'physio', 'psychologist'));
ALTER TABLE tips ADD CONSTRAINT tips_badge_check
    CHECK (badge IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'expert', 'physio', 'psychologist'));

-- 2. a doctor's medical system. NULL on non-doctors; every existing doctor is allopathy.
--    badges.system = the system being verified (a change on an approved badge sends it back to 'pending');
--    badges.reverify_from = the system before such a change (the admin review shows "was …").
ALTER TABLE providers ADD COLUMN IF NOT EXISTS system TEXT;
ALTER TABLE badges ADD COLUMN IF NOT EXISTS system TEXT;
ALTER TABLE badges ADD COLUMN IF NOT EXISTS reverify_from TEXT;

ALTER TABLE providers DROP CONSTRAINT IF EXISTS providers_system_check;
ALTER TABLE providers ADD CONSTRAINT providers_system_check
    CHECK (system IN ('allopathy', 'homeopathy', 'unani', 'ayurveda'));
ALTER TABLE badges DROP CONSTRAINT IF EXISTS badges_system_check;
ALTER TABLE badges ADD CONSTRAINT badges_system_check
    CHECK (system IN ('allopathy', 'homeopathy', 'unani', 'ayurveda') AND reverify_from IN ('allopathy', 'homeopathy', 'unani', 'ayurveda'));

UPDATE providers SET system = 'allopathy' WHERE type = 'doctor' AND system IS NULL;
UPDATE badges SET system = 'allopathy' WHERE type = 'doctor' AND system IS NULL;

CREATE INDEX IF NOT EXISTS providers_type_system_idx ON providers (type, system);

-- 3. prescriptions: who wrote it (doctor / physio / psychologist), the doctor's system, free-text medicine lines
--    (non-allopathic — never matched with a pharmacy's stock or sent to a pharmacy)
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS doctor_type TEXT NOT NULL DEFAULT 'doctor';
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS doctor_system TEXT;
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS free_text BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE prescriptions DROP CONSTRAINT IF EXISTS prescriptions_doctor_type_check;
ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_doctor_type_check CHECK (doctor_type IN ('doctor', 'physio', 'psychologist'));
UPDATE prescriptions SET doctor_system = 'allopathy' WHERE doctor_type = 'doctor' AND doctor_system IS NULL;

-- 4. psychologist privacy: the patient shares their psychologist appointments/notes with this caregiver (default off)
ALTER TABLE care_links ADD COLUMN IF NOT EXISTS share_psych BOOLEAN NOT NULL DEFAULT FALSE;
