-- =====================================================================================
-- V3: professional badges, providers (doctor/nurse/… chambers + home care), appointments, queues
-- =====================================================================================

-- shared listing/verification shape is repeated per table (see SCHEMA.md "listing columns")

-- badges/{uid}
CREATE TABLE badges (
    uid                    TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    type                   TEXT NOT NULL CHECK (type IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'expert')),
    reg_no                 TEXT NOT NULL CHECK (length(reg_no) <= 60),
    workplace              TEXT NOT NULL CHECK (length(workplace) <= 120),
    name                   TEXT NOT NULL DEFAULT '',
    email                  TEXT NOT NULL DEFAULT '',
    status                 TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
    status_reason          TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at            TIMESTAMPTZ,
    reviewed_by            TEXT,
    -- verification {by, byEmail, at, checks[]}
    verified_by            TEXT,
    verified_by_email      TEXT,
    verified_at            TIMESTAMPTZ,
    verification_checks    TEXT[],
    -- docsHave {cert:true, idcard:true}
    docs_have              TEXT[] NOT NULL DEFAULT '{}',
    docs_sent_at           TIMESTAMPTZ,
    docs_requested_at      TIMESTAMPTZ,
    docs_request_note      TEXT,
    -- pendingChange {type, regNo, workplace, name, at} — correction of an approved badge
    change_type            TEXT,
    change_reg_no          TEXT,
    change_workplace       TEXT,
    change_name            TEXT,
    change_at              TIMESTAMPTZ
);
CREATE INDEX badges_status_idx ON badges (status);
CREATE INDEX badges_change_idx ON badges (change_at) WHERE change_at IS NOT NULL;

-- providers/{uid}
CREATE TABLE providers (
    uid                       TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    type                      TEXT NOT NULL CHECK (type IN ('doctor', 'nurse', 'nutritionist', 'trainer')),
    name                      TEXT NOT NULL DEFAULT '',
    degrees                   TEXT NOT NULL DEFAULT '',
    gender                    TEXT CHECK (gender IN ('male', 'female', 'other')),
    specialties               TEXT[] NOT NULL DEFAULT '{}',
    listed                    BOOLEAN NOT NULL DEFAULT FALSE,
    -- legacy app flag (pre-"listed" documents)
    active                    BOOLEAN NOT NULL DEFAULT FALSE,
    appointments              BOOLEAN NOT NULL DEFAULT FALSE,
    -- suspension/badge bookkeeping (apptcancel.js, community.js admin_badge_resolve)
    admin_suspended           BOOLEAN NOT NULL DEFAULT FALSE,
    suspend_reason            TEXT CHECK (suspend_reason IN ('admin', 'report', 'deletion')),
    listed_before_suspend     BOOLEAN,
    badge_rejected            BOOLEAN NOT NULL DEFAULT FALSE,
    listed_before             BOOLEAN,
    active_before             BOOLEAN,
    -- home care (homeVisit {...})
    home_visit_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    home_visit_blocked        BOOLEAN NOT NULL DEFAULT FALSE,
    home_radius_km            INTEGER,
    home_upazila_ids          INTEGER[] NOT NULL DEFAULT '{}',
    home_services             TEXT[] NOT NULL DEFAULT '{}',
    home_phone                TEXT,
    home_schedule             TEXT,
    home_no_fee               BOOLEAN NOT NULL DEFAULT FALSE,
    home_fee                  TEXT,
    home_visit_terms_at       TIMESTAMPTZ,
    -- reportReview {enabled, noFee, fee, payNumber, payMethods}
    review_enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    review_no_fee             BOOLEAN NOT NULL DEFAULT FALSE,
    review_fee                TEXT,
    review_pay_number         TEXT,
    review_pay_methods        TEXT[] NOT NULL DEFAULT '{}',
    rating_sum                INTEGER NOT NULL DEFAULT 0,
    rating_count              INTEGER NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ,
    is_demo                   BOOLEAN GENERATED ALWAYS AS (uid LIKE 'demo-%') STORED
);
CREATE INDEX providers_type_listed_idx ON providers (type) WHERE listed OR active;
CREATE INDEX providers_home_idx ON providers (type) WHERE home_visit_enabled;

-- providers.assistantEmails[]
CREATE TABLE provider_assistants (
    provider_uid  TEXT NOT NULL REFERENCES providers (uid) ON DELETE CASCADE,
    email         TEXT NOT NULL CHECK (email = lower(email)),
    PRIMARY KEY (provider_uid, email)
);
CREATE INDEX provider_assistants_email_idx ON provider_assistants (email);

-- providers.chambers[] (order matters: book() uses chamberIndex)
CREATE TABLE provider_chambers (
    provider_uid      TEXT NOT NULL REFERENCES providers (uid) ON DELETE CASCADE,
    idx               INTEGER NOT NULL CHECK (idx >= 0),
    hospital_id       TEXT,
    hospital_name     TEXT NOT NULL DEFAULT '',
    hospital_name_bn  TEXT NOT NULL DEFAULT '',
    district          TEXT,
    upazila_id        INTEGER,
    union_id          INTEGER,
    room              TEXT NOT NULL DEFAULT '',
    days              SMALLINT[] NOT NULL DEFAULT '{}',
    start_time        TEXT CHECK (start_time ~ '^[0-9]{2}:[0-9]{2}$'),
    end_time          TEXT CHECK (end_time ~ '^[0-9]{2}:[0-9]{2}$'),
    schedule          TEXT NOT NULL DEFAULT '',
    no_fee            BOOLEAN NOT NULL DEFAULT FALSE,
    fee               TEXT,
    appointments      BOOLEAN NOT NULL DEFAULT FALSE,
    serial_phone      TEXT,
    PRIMARY KEY (provider_uid, idx)
);
CREATE INDEX provider_chambers_hospital_idx ON provider_chambers (hospital_id);
CREATE INDEX provider_chambers_area_idx ON provider_chambers (district, upazila_id);

-- chambers[].times[] {days, start, end} (several sessions per chamber) and homeVisit.times[]
CREATE TABLE provider_chamber_times (
    provider_uid  TEXT NOT NULL,
    chamber_idx   INTEGER NOT NULL,
    idx           INTEGER NOT NULL,
    days          SMALLINT[] NOT NULL,
    start_time    TEXT NOT NULL CHECK (start_time ~ '^[0-9]{2}:[0-9]{2}$'),
    end_time      TEXT NOT NULL CHECK (end_time ~ '^[0-9]{2}:[0-9]{2}$'),
    PRIMARY KEY (provider_uid, chamber_idx, idx),
    FOREIGN KEY (provider_uid, chamber_idx) REFERENCES provider_chambers (provider_uid, idx) ON DELETE CASCADE
);
CREATE TABLE provider_home_times (
    provider_uid  TEXT NOT NULL REFERENCES providers (uid) ON DELETE CASCADE,
    idx           INTEGER NOT NULL,
    days          SMALLINT[] NOT NULL,
    start_time    TEXT NOT NULL CHECK (start_time ~ '^[0-9]{2}:[0-9]{2}$'),
    end_time      TEXT NOT NULL CHECK (end_time ~ '^[0-9]{2}:[0-9]{2}$'),
    PRIMARY KEY (provider_uid, idx)
);

-- providers.queueStats{chamberKeyHash: {avg, n, at}} (minutes per patient, for the queue ETA)
CREATE TABLE provider_queue_stats (
    provider_uid  TEXT NOT NULL REFERENCES providers (uid) ON DELETE CASCADE,
    queue_key     TEXT NOT NULL,
    avg_minutes   DOUBLE PRECISION NOT NULL,
    n             INTEGER NOT NULL,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_uid, queue_key)
);

-- appointments/{id}
CREATE TABLE appointments (
    id               TEXT PRIMARY KEY,
    patient_uid      TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    provider_uid     TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    patient_name     TEXT NOT NULL DEFAULT '',
    provider_name    TEXT NOT NULL DEFAULT '',
    -- family member booked for (careaccess family), when not the account holder
    member_id        TEXT,
    type             TEXT NOT NULL CHECK (type IN ('doctor', 'nurse', 'nutritionist', 'trainer')),
    chamber          TEXT NOT NULL DEFAULT '',
    chamber_bn       TEXT NOT NULL DEFAULT '',
    chamber_key      TEXT NOT NULL DEFAULT '',
    room             TEXT NOT NULL DEFAULT '',
    schedule         TEXT NOT NULL DEFAULT '',
    start_time       TEXT,
    -- Node "date": UTC-midnight millis of the Dhaka calendar day
    visit_date       DATE NOT NULL,
    note             TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 300),
    status           TEXT NOT NULL CHECK (status IN ('pending', 'confirmed', 'declined', 'cancelled', 'expired', 'completed')),
    provider_note    TEXT NOT NULL DEFAULT '',
    serial           INTEGER,
    cancelled_by     TEXT CHECK (cancelled_by IN ('patient', 'provider', 'system')),
    by_caretaker     TEXT,
    -- "I'll be late" (queue.js)
    late_status      TEXT CHECK (late_status IN ('asked', 'ok', 'later')),
    late_min         INTEGER,
    late_note        TEXT,
    late_at          TIMESTAMPTZ,
    late_count       INTEGER NOT NULL DEFAULT 0,
    late_reply_at    TIMESTAMPTZ,
    near_push_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    is_demo          BOOLEAN GENERATED ALWAYS AS (patient_uid LIKE 'demo-%') STORED
);
CREATE INDEX appointments_patient_idx ON appointments (patient_uid, status);
CREATE INDEX appointments_provider_idx ON appointments (provider_uid, status, visit_date);
CREATE INDEX appointments_date_status_idx ON appointments (visit_date, status);
CREATE INDEX appointments_pending_idx ON appointments (created_at) WHERE status = 'pending';

-- serials/{providerUid_date_key} — next serial number per chamber per day (assigned in a transaction)
CREATE TABLE serial_counters (
    provider_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    visit_date    DATE NOT NULL,
    queue_key     TEXT NOT NULL,
    n             INTEGER NOT NULL DEFAULT 0,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_uid, visit_date, queue_key)
);

-- queues/{providerUid_date_key} — live chamber queue (queue.js)
CREATE TABLE queues (
    provider_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    visit_date    DATE NOT NULL,
    queue_key     TEXT NOT NULL,
    chamber_key   TEXT NOT NULL DEFAULT '',
    current       INTEGER NOT NULL DEFAULT 0,
    pos           INTEGER NOT NULL DEFAULT 0,
    done          INTEGER[] NOT NULL DEFAULT '{}',
    -- epoch-ms of each "next" press (ETA average)
    advances      BIGINT[] NOT NULL DEFAULT '{}',
    started       BOOLEAN NOT NULL DEFAULT FALSE,
    started_at    TIMESTAMPTZ,
    paused        BOOLEAN NOT NULL DEFAULT FALSE,
    paused_at     TIMESTAMPTZ,
    finished      BOOLEAN NOT NULL DEFAULT FALSE,
    last_at       TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_uid, visit_date, queue_key)
);
