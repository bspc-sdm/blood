-- =====================================================================================
-- V2: blood donation — requests, responses, emergency calls, date corrections, hospitals
-- =====================================================================================

-- requests/{id}
CREATE TABLE blood_requests (
    id                     TEXT PRIMARY KEY,
    requester_uid          TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    requester_name         TEXT NOT NULL DEFAULT '',
    patient_group          TEXT NOT NULL CHECK (patient_group IN ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),
    bags                   INTEGER NOT NULL CHECK (bags BETWEEN 1 AND 10),
    hospital               TEXT NOT NULL CHECK (length(hospital) <= 120),
    place_name             TEXT NOT NULL DEFAULT '',
    lat                    DOUBLE PRECISION NOT NULL,
    lng                    DOUBLE PRECISION NOT NULL,
    geohash                TEXT NOT NULL,
    needed_by_ms           BIGINT NOT NULL,
    needed_at_ms           BIGINT,
    urgent                 BOOLEAN NOT NULL DEFAULT FALSE,
    travel_fare            BOOLEAN NOT NULL DEFAULT FALSE,
    note                   TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 300),
    status                 TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    -- notify-request.js
    notified_at            TIMESTAMPTZ,
    notified_count         INTEGER NOT NULL DEFAULT 0,
    notify_rounds          INTEGER,
    last_notified_at       TIMESTAMPTZ,
    notifying_until        TIMESTAMPTZ,
    -- counters
    accepted_count         INTEGER NOT NULL DEFAULT 0,
    confirmed_count        INTEGER NOT NULL DEFAULT 0,
    fulfilled              BOOLEAN NOT NULL DEFAULT FALSE,
    bags_received          INTEGER NOT NULL DEFAULT 0,
    bags_received_before   INTEGER,
    -- edit-request.js
    edit_count             INTEGER NOT NULL DEFAULT 0,
    edited_at              TIMESTAMPTZ,
    -- close
    closed_at              TIMESTAMPTZ,
    closed_by              TEXT CHECK (closed_by IN ('requester', 'expired', 'not_needed', 'auto', 'removed')),
    is_demo                BOOLEAN GENERATED ALWAYS AS (requester_uid LIKE 'demo-%') STORED
);
-- geoOpen equivalent: geohash range scans over open requests only
CREATE INDEX blood_requests_open_geo_idx ON blood_requests (geohash text_pattern_ops) WHERE status = 'open';
CREATE INDEX blood_requests_requester_idx ON blood_requests (requester_uid, created_at DESC);
CREATE INDEX blood_requests_open_place_idx ON blood_requests (place_name) WHERE status = 'open';

-- requests/{id}/private/contact (only requester + accepted/donated donors may read)
CREATE TABLE blood_request_contacts (
    request_id  TEXT PRIMARY KEY REFERENCES blood_requests (id) ON DELETE CASCADE,
    name        TEXT NOT NULL DEFAULT '',
    phone       TEXT NOT NULL DEFAULT ''
);

-- requests/{id}/meta/notified.uids[] (donors already pushed for this request)
CREATE TABLE blood_request_notified (
    request_id  TEXT NOT NULL REFERENCES blood_requests (id) ON DELETE CASCADE,
    uid         TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, uid)
);

-- requests/{id}/meta/close (undo information for request_reopen within 1 h)
CREATE TABLE blood_request_close_marks (
    request_id        TEXT PRIMARY KEY REFERENCES blood_requests (id) ON DELETE CASCADE,
    day               DATE NOT NULL,
    confirmed_before  INTEGER NOT NULL DEFAULT 0,
    fulfilled_before  BOOLEAN NOT NULL DEFAULT FALSE,
    at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE blood_request_close_mark_donors (
    request_id     TEXT NOT NULL REFERENCES blood_request_close_marks (request_id) ON DELETE CASCADE,
    donor_uid      TEXT NOT NULL,
    -- previous lastDonationMillis; keep_date = true means the date was not touched ('keep')
    prev_last_ms   BIGINT,
    keep_date      BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (request_id, donor_uid)
);

-- requests/{id}/responses/{donorUid}
CREATE TABLE blood_responses (
    request_id        TEXT NOT NULL REFERENCES blood_requests (id) ON DELETE CASCADE,
    donor_uid         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    donor_name        TEXT NOT NULL DEFAULT '',
    donor_phone       TEXT NOT NULL DEFAULT '',
    donor_group       TEXT NOT NULL DEFAULT '',
    status            TEXT NOT NULL DEFAULT 'accepted' CHECK (status IN ('accepted', 'donated', 'cancelled', 'declined')),
    confirmed         BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_at      TIMESTAMPTZ,
    distance_km       INTEGER,
    fare_after_km     INTEGER,
    cancelled_reason  TEXT,
    removed_by        TEXT,
    removed_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    PRIMARY KEY (request_id, donor_uid)
);
CREATE INDEX blood_responses_donor_idx ON blood_responses (donor_uid, status);

-- reports/{requestId}_{uid} (one report per user per request; nobody reads them via the API)
CREATE TABLE blood_request_reports (
    request_id    TEXT NOT NULL,
    reporter_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, reporter_uid)
);

-- correctionRequests/{id} (last-donation date correction, resolved by admin)
CREATE TABLE correction_requests (
    id               TEXT PRIMARY KEY,
    uid              TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    name             TEXT NOT NULL DEFAULT '',
    reason           TEXT NOT NULL DEFAULT '',
    current_ms       BIGINT,
    requested_ms     BIGINT,
    status           TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      TEXT
);
CREATE INDEX correction_requests_pending_idx ON correction_requests (created_at) WHERE status = 'pending';

-- emergencies/{id} (admin-run emergency blood calls)
CREATE TABLE emergencies (
    id              TEXT PRIMARY KEY,
    district        TEXT NOT NULL,
    districts       TEXT[] NOT NULL DEFAULT '{}',
    hospital_id     TEXT,
    hospital_name   TEXT NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    exact_location  BOOLEAN NOT NULL DEFAULT FALSE,
    radius_km       INTEGER NOT NULL,
    contact_phone   TEXT NOT NULL DEFAULT '',
    note            TEXT NOT NULL DEFAULT '',
    status          TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed', 'expired')),
    waves           INTEGER NOT NULL DEFAULT 0,
    last_wave_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      TEXT NOT NULL DEFAULT '',
    ends_at         TIMESTAMPTZ NOT NULL,
    closed_at       TIMESTAMPTZ,
    -- seeded demo calls are hidden from guests (emergencies_active)
    is_demo         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX emergencies_active_idx ON emergencies (created_at DESC) WHERE status = 'active';
CREATE INDEX emergencies_created_idx ON emergencies (created_at DESC);

-- emergencies/{id}.groups{group: {need, coming, notified, closed}}
CREATE TABLE emergency_groups (
    emergency_id  TEXT NOT NULL REFERENCES emergencies (id) ON DELETE CASCADE,
    blood_group   TEXT NOT NULL CHECK (blood_group IN ('A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),
    need          INTEGER NOT NULL CHECK (need >= 0),
    coming        INTEGER NOT NULL DEFAULT 0,
    notified      INTEGER NOT NULL DEFAULT 0,
    closed        BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (emergency_id, blood_group)
);

-- emergencies/{id}/donors/{uid}
CREATE TABLE emergency_donors (
    emergency_id  TEXT NOT NULL REFERENCES emergencies (id) ON DELETE CASCADE,
    uid           TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    blood_group   TEXT,
    km            DOUBLE PRECISION,
    status        TEXT NOT NULL CHECK (status IN ('notified', 'coming', 'cancelled', 'donated', 'not_donated')),
    notified_at   TIMESTAMPTZ,
    coming_at     TIMESTAMPTZ,
    answered_at   TIMESTAMPTZ,
    PRIMARY KEY (emergency_id, uid)
);
CREATE INDEX emergency_donors_uid_idx ON emergency_donors (uid);

-- hospitals/{id} — user-requested hospitals approved by admins (the DGHS directory stays a bundled JSON)
CREATE TABLE hospitals (
    id          TEXT PRIMARY KEY,
    name_en     TEXT NOT NULL,
    name_bn     TEXT NOT NULL DEFAULT '',
    type        TEXT NOT NULL,
    district    TEXT NOT NULL,
    upazila_id  INTEGER,
    union_id    INTEGER,
    address     TEXT NOT NULL DEFAULT '',
    phone       TEXT NOT NULL DEFAULT '',
    norm_key    TEXT NOT NULL DEFAULT '',
    source      TEXT NOT NULL DEFAULT 'user',
    status      TEXT NOT NULL DEFAULT 'approved' CHECK (status IN ('approved', 'hidden')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX hospitals_district_idx ON hospitals (district) WHERE status = 'approved';

-- hospitalRequests/{id}
CREATE TABLE hospital_requests (
    id           TEXT PRIMARY KEY,
    name_en      TEXT NOT NULL,
    name_bn      TEXT NOT NULL DEFAULT '',
    type         TEXT NOT NULL,
    district     TEXT NOT NULL,
    upazila_id   INTEGER,
    union_id     INTEGER,
    address      TEXT NOT NULL DEFAULT '',
    phone        TEXT NOT NULL DEFAULT '',
    norm_key     TEXT NOT NULL DEFAULT '',
    count        INTEGER NOT NULL DEFAULT 1,
    status       TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'merged')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at  TIMESTAMPTZ,
    hospital_id  TEXT
);
CREATE INDEX hospital_requests_pending_idx ON hospital_requests (district) WHERE status = 'pending';
-- hospitalRequests.requesterUids[]
CREATE TABLE hospital_request_requesters (
    request_id  TEXT NOT NULL REFERENCES hospital_requests (id) ON DELETE CASCADE,
    uid         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    PRIMARY KEY (request_id, uid)
);
CREATE INDEX hospital_request_requesters_uid_idx ON hospital_request_requesters (uid);
