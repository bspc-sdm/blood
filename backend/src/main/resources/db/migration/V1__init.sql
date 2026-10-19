-- =====================================================================================
-- Lifolo backend — V1: core (accounts, profiles, per-user server state, quotas, auth codes,
-- config, admins, admin log, inbox, devices, caregivers, sanctions, demo manifest, job markers)
--
-- Conventions (see backend/docs/SCHEMA.md):
--   * ids are TEXT. Firebase uids as-is; document ids keep the Firestore-like 20-char format.
--   * server-generated instants are TIMESTAMPTZ; the API layer converts them to epoch-ms numbers.
--   * values the clients send as epoch-ms and expect back unchanged are BIGINT "*_ms" columns.
--   * Dhaka calendar days (Node "YYYYMMDD" day keys / UTC-midnight millis of a Dhaka date) are DATE.
--   * status-like enums are TEXT + CHECK. JSONB only for genuinely free-form blobs.
--   * is_demo = "demo world" (Node demo.sameWorld: uid starts with 'demo-'); generated where possible.
-- =====================================================================================

-- ---------- accounts: every Firebase Auth uid that ever acted (FK target for user-owned rows) ----------
-- Firestore: (implicit) Auth user + serverState/{uid}.deleteAt|deleteRequestedAt|disabledReason
CREATE TABLE accounts (
    uid                  TEXT PRIMARY KEY CHECK (uid ~ '^[A-Za-z0-9_-]{1,128}$'),
    email                TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at         TIMESTAMPTZ,
    is_demo              BOOLEAN GENERATED ALWAYS AS (uid LIKE 'demo-%') STORED,
    -- account deletion with a 30-day grace period (lib/account.js schedule/cancel/runDue)
    delete_requested_at  TIMESTAMPTZ,
    delete_at            TIMESTAMPTZ,
    -- shown in the "account suspended" error (firebase.js accountDisabledError)
    disabled_reason      TEXT
);
CREATE INDEX accounts_delete_at_idx ON accounts (delete_at) WHERE delete_at IS NOT NULL;
CREATE INDEX accounts_email_idx ON accounts (lower(email));

-- ---------- user_profiles: users/{uid} (only what the app writes + server-maintained geo keys) ----------
CREATE TABLE user_profiles (
    uid                        TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    name                       TEXT CHECK (length(name) <= 100),
    phone                      TEXT CHECK (length(phone) <= 30),
    -- '' = not set; 'unknown' exists in legacy data (respond.js rejects it)
    blood_group                TEXT CHECK (blood_group IN ('', 'unknown', 'A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-')),
    gender                     TEXT,
    birth_date_ms              BIGINT,
    weight_kg                  DOUBLE PRECISION CHECK (weight_kg BETWEEN 0 AND 500),
    last_donation_ms           BIGINT,
    notify                     BOOLEAN NOT NULL DEFAULT TRUE,
    max_push_per_day           INTEGER CHECK (max_push_per_day BETWEEN 1 AND 10),
    notify_groups              TEXT[],
    view_groups                TEXT[],
    max_km                     INTEGER CHECK (max_km IN (5, 10, 25, 50)),
    fare_after_km              INTEGER CHECK (fare_after_km BETWEEN 0 AND 1000),
    lang                       TEXT CHECK (lang IN ('en', 'bn')),
    -- base64 JPEG today (≤600 KB / ≤100 KB); phase 2 may move them to R2 and keep keys here
    photo                      TEXT,
    photo_thumb                TEXT,
    lat                        DOUBLE PRECISION CHECK (lat BETWEEN -90 AND 90),
    lng                        DOUBLE PRECISION CHECK (lng BETWEEN -180 AND 180),
    geohash                    TEXT,
    -- "group|geohash" donor search key (geo.js donorKey) — one btree range scan per group
    donor_geo                  TEXT,
    place_name                 TEXT CHECK (length(place_name) <= 120),
    location_source            TEXT CHECK (length(location_source) <= 20),
    location_updated_at        TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ,
    CHECK ((lat IS NULL) = (lng IS NULL))
);
CREATE INDEX user_profiles_donor_geo_idx ON user_profiles (donor_geo text_pattern_ops) WHERE donor_geo IS NOT NULL;
CREATE INDEX user_profiles_geohash_idx ON user_profiles (geohash text_pattern_ops) WHERE geohash IS NOT NULL;
CREATE INDEX user_profiles_last_donation_idx ON user_profiles (last_donation_ms) WHERE last_donation_ms IS NOT NULL;
CREATE INDEX user_profiles_phone_idx ON user_profiles (phone) WHERE phone IS NOT NULL AND phone <> '';

-- users/{uid}.family[] (members without their own account; careaccess.js family_get/family_set)
CREATE TABLE user_family_members (
    uid        TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    member_id  TEXT NOT NULL,
    position   INTEGER NOT NULL,
    name       TEXT NOT NULL,
    relation   TEXT,
    age        INTEGER,
    gender     TEXT,
    phone      TEXT,
    PRIMARY KEY (uid, member_id)
);

-- users/{uid}.commitments{requestId: ms} (the donor's "processing" list; profile_update commitAdd/commitRemove)
CREATE TABLE user_commitments (
    uid           TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    request_id    TEXT NOT NULL,
    committed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (uid, request_id)
);

-- users/{uid}.fcmTokens[] + legacy fcmToken (devices.js; sendPush removes dead tokens)
CREATE TABLE device_tokens (
    token       TEXT PRIMARY KEY,
    uid         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX device_tokens_uid_idx ON device_tokens (uid, updated_at);

-- ---------- user_state: serverState/{uid} (everything the app may not write) ----------
CREATE TABLE user_state (
    uid                      TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    inbox_seen_at            TIMESTAMPTZ,
    verified_phone           TEXT,
    phone_verified_at        TIMESTAMPTZ,
    phone_code_hash          TEXT,
    phone_code_phone         TEXT,
    phone_code_expires_at    TIMESTAMPTZ,
    phone_code_tries         INTEGER NOT NULL DEFAULT 0,
    -- last home-collection address/phone (labprices.js rememberHome)
    lab_home_address         TEXT,
    lab_home_phone           TEXT,
    lab_home_at              TIMESTAMPTZ,
    -- request push counter (notify-request.js pushDay/pushCount, per donor per Dhaka day)
    push_day                 DATE,
    push_count               INTEGER NOT NULL DEFAULT 0
);

-- serverState/{uid}.{kind}Day/{kind}Count — daily quotas (community-db.useQuota + request/respond/create counters)
CREATE TABLE user_quotas (
    uid    TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    kind   TEXT NOT NULL CHECK (kind ~ '^[A-Za-z]{1,32}$'),
    day    DATE NOT NULL,
    count  INTEGER NOT NULL DEFAULT 0 CHECK (count >= 0),
    PRIMARY KEY (uid, kind)
);

-- serverState/{uid}.donationDateLog[] (donationdate.js)
CREATE TABLE donation_date_log (
    id       BIGSERIAL PRIMARY KEY,
    uid      TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    from_ms  BIGINT,
    to_ms    BIGINT,
    risk     TEXT,
    at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX donation_date_log_uid_idx ON donation_date_log (uid, at DESC);

-- serverState/ip_*, serverState/_emailGlobalExisting|_emailGlobalNew (send-code.js), phoneLimits/h_* (phone.js)
CREATE TABLE rate_counters (
    key      TEXT PRIMARY KEY,
    scope    TEXT NOT NULL CHECK (scope IN ('email_ip', 'email_global_existing', 'email_global_new', 'phone')),
    day      DATE NOT NULL,
    count    INTEGER NOT NULL DEFAULT 0,
    last_at  TIMESTAMPTZ
);
CREATE INDEX rate_counters_day_idx ON rate_counters (day);

-- ---------- login codes: emailCodes/{sha256('email:'+email)} ----------
CREATE TABLE email_codes (
    email_hash    TEXT PRIMARY KEY,
    last_sent_at  TIMESTAMPTZ,
    send_day      DATE,
    send_count    INTEGER NOT NULL DEFAULT 0,
    fail_day      DATE,
    fail_count    INTEGER NOT NULL DEFAULT 0
);
-- emailCodes.codes[] — up to 3 live codes {h, exp, rid, fails}
CREATE TABLE email_code_entries (
    id          BIGSERIAL PRIMARY KEY,
    email_hash  TEXT NOT NULL REFERENCES email_codes (email_hash) ON DELETE CASCADE,
    code_hash   TEXT NOT NULL,
    rid         TEXT,
    expires_at  TIMESTAMPTZ NOT NULL,
    fails       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX email_code_entries_hash_idx ON email_code_entries (email_hash, expires_at);

-- ---------- config ----------
-- config/admins.emails (extra admins; owners come from ADMIN_EMAILS)
CREATE TABLE admin_emails (
    email     TEXT PRIMARY KEY CHECK (email = lower(email) AND length(email) <= 200),
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by  TEXT
);

-- config/catalog.lists[kind][type][] (catalog.js; merged with catalog-defaults.json at read time)
CREATE TABLE catalog_items (
    kind      TEXT NOT NULL CHECK (kind IN ('specialty', 'homeService', 'ambulanceType', 'bloodBankService')),
    type      TEXT NOT NULL,
    code      TEXT NOT NULL,
    en        TEXT NOT NULL,
    bn        TEXT NOT NULL,
    hidden    BOOLEAN NOT NULL DEFAULT FALSE,
    position  INTEGER NOT NULL,
    PRIMARY KEY (kind, type, code)
);
CREATE TABLE catalog_meta (
    id          BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- config/labTests.tests[] (labtests.js; admin overrides merged with labtests-defaults.json)
CREATE TABLE lab_test_templates (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    bn          TEXT,
    aliases     TEXT[] NOT NULL DEFAULT '{}',
    -- report form rows {key,name,unit,low,high,long,…}: an admin-edited template, kept as one blob
    params      JSONB NOT NULL DEFAULT '[]',
    hidden      BOOLEAN NOT NULL DEFAULT FALSE,
    position    INTEGER NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- admin log: adminLog/{autoId} ----------
CREATE TABLE admin_log (
    id        TEXT PRIMARY KEY,
    at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    action    TEXT NOT NULL,
    by_email  TEXT NOT NULL DEFAULT '',
    by_uid    TEXT,
    via       TEXT NOT NULL CHECK (via IN ('app', 'web')),
    target    TEXT NOT NULL DEFAULT '' CHECK (length(target) <= 300),
    detail    TEXT NOT NULL DEFAULT '' CHECK (length(detail) <= 300)
);
CREATE INDEX admin_log_at_idx ON admin_log (at DESC);

-- ---------- inbox: inbox/{uid_reverseTime_rand} (bell list; 21-day retention cron) ----------
CREATE TABLE inbox_items (
    id          TEXT PRIMARY KEY,
    uid         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    title       TEXT NOT NULL CHECK (length(title) <= 200),
    body        TEXT NOT NULL DEFAULT '' CHECK (length(body) <= 300),
    -- the push "data" map (type, requestId, rxId, asUid, …): free-form by design
    data        JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX inbox_items_uid_idx ON inbox_items (uid, created_at DESC);
CREATE INDEX inbox_items_created_idx ON inbox_items (created_at);

-- ---------- caregivers: careLinks/{patientUid}_{caretakerUid} ----------
CREATE TABLE care_links (
    patient_uid      TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    caretaker_uid    TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    status           TEXT NOT NULL CHECK (status IN ('invited', 'active', 'rejected')),
    patient_name     TEXT NOT NULL DEFAULT '',
    caretaker_name   TEXT NOT NULL DEFAULT '',
    caretaker_email  TEXT NOT NULL DEFAULT '',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at      TIMESTAMPTZ,
    PRIMARY KEY (patient_uid, caretaker_uid),
    CHECK (patient_uid <> caretaker_uid)
);
CREATE INDEX care_links_caretaker_idx ON care_links (caretaker_uid);

-- ---------- sanctions/{sha256(kind:value)[0:40]} — survive listing/account deletion ----------
CREATE TABLE sanctions (
    key         TEXT PRIMARY KEY,
    listing     BOOLEAN NOT NULL DEFAULT FALSE,
    home_care   BOOLEAN NOT NULL DEFAULT FALSE,
    kind        TEXT,
    reason      TEXT,
    -- extra audit info the moderator attached (by, path, …): free-form
    info        JSONB NOT NULL DEFAULT '{}',
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- demo manifest: serverState/_demo {paths, uids, emails, partial, at} ----------
CREATE TABLE demo_state (
    id       BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    partial  BOOLEAN NOT NULL DEFAULT TRUE,
    at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE demo_accounts (
    uid       TEXT PRIMARY KEY CHECK (uid LIKE 'demo-%'),
    email     TEXT,
    role_key  TEXT
);
-- rows created by the seeder that are not owned by a demo account (deleted on wipe)
CREATE TABLE demo_manifest_rows (
    id          BIGSERIAL PRIMARY KEY,
    table_name  TEXT NOT NULL,
    row_key     JSONB NOT NULL
);

-- ---------- maintenance/{name}: one-off/backfill job progress ----------
CREATE TABLE job_markers (
    key      TEXT PRIMARY KEY,
    done     BOOLEAN NOT NULL DEFAULT FALSE,
    done_at  TIMESTAMPTZ,
    cursor   TEXT
);
