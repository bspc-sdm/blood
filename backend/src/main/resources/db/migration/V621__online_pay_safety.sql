-- Online chambers: the security review (docs/test-plan/CALLS-SECURITY-REVIEW.md R6–R8), spring-p15, 23 Oct 2026.

-- R6: a changed bKash number reaches new bookings only after 24 h (the old one meanwhile); every change is logged
ALTER TABLE providers ADD COLUMN online_bkash_prev TEXT;
ALTER TABLE providers ADD COLUMN online_bkash_changed_at TIMESTAMPTZ;
CREATE TABLE bkash_number_log (
    id            BIGSERIAL PRIMARY KEY,
    provider_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    -- last 3 digits only (the full numbers are on the chamber / the appointments)
    old_last3     TEXT,
    new_last3     TEXT,
    by_uid        TEXT,
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bkash_number_log_provider_idx ON bkash_number_log (provider_uid, at DESC);

-- R7: a TrxID once per doctor; at most 5 claims per appointment per hour
CREATE UNIQUE INDEX appointments_pay_ref_uniq ON appointments (provider_uid, pay_ref) WHERE pay_ref IS NOT NULL;
ALTER TABLE appointments ADD COLUMN pay_claim_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE appointments ADD COLUMN pay_claim_window_at TIMESTAMPTZ;

-- R8: append-only record of every pay transition
CREATE TABLE appt_pay_log (
    id              BIGSERIAL PRIMARY KEY,
    appointment_id  TEXT NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
    by_uid          TEXT,
    from_state      TEXT,
    to_state        TEXT,
    pay_ref         TEXT,
    note            TEXT,
    at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX appt_pay_log_appt_idx ON appt_pay_log (appointment_id, id);

-- R8: the doctor (or an assistant) joined the video call — set by appt_join (spring-p14); a paid appointment whose
-- window passed without it (and without a prescription) is reported to the patient and the admins once
ALTER TABLE appointments ADD COLUMN doctor_joined_at TIMESTAMPTZ;
ALTER TABLE appointments ADD COLUMN unserved_notified_at TIMESTAMPTZ;

-- R8: appointment reports for the admins (appt_report; reporter NULL = the server)
CREATE TABLE appt_reports (
    id              TEXT PRIMARY KEY,
    appointment_id  TEXT NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
    reporter_uid    TEXT REFERENCES accounts (uid) ON DELETE CASCADE,
    reporter_role   TEXT NOT NULL CHECK (reporter_role IN ('patient', 'doctor', 'assistant', 'system')),
    reason          TEXT NOT NULL CHECK (length(reason) <= 300),
    status          TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     TEXT
);
CREATE INDEX appt_reports_status_idx ON appt_reports (status, created_at DESC);
CREATE INDEX appt_reports_appt_idx ON appt_reports (appointment_id);
