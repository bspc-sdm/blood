-- =====================================================================================
-- V4: public listings — pharmacies, labs, ambulances, blood banks; calls, stats, ratings,
--     verification documents, medicine finds, pharmacy stock/missed, "call an ambulance" requests
--
-- Every listing table repeats the same review columns (SCHEMA.md "listing columns"):
--   status, status_reason, approved_name, last_approved (snapshot JSONB), verified_* + verification_checks,
--   docs_have, docs_sent_at, docs_requested_at, docs_request_note, reviewed_at, reviewed_by,
--   hidden_for_deletion (status before account-deletion hide), edit_rejected_at/_reason
-- =====================================================================================

-- pharmacies/{ownerUid}
CREATE TABLE pharmacies (
    uid                   TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    name                  TEXT NOT NULL CHECK (length(name) <= 100),
    license_no            TEXT NOT NULL CHECK (length(license_no) <= 40),
    phone                 TEXT NOT NULL,
    district              TEXT NOT NULL,
    upazila_id            INTEGER,
    address               TEXT NOT NULL DEFAULT '',
    lat                   DOUBLE PRECISION,
    lng                   DOUBLE PRECISION,
    open24                BOOLEAN NOT NULL DEFAULT FALSE,
    open_at               TEXT,
    close_at              TEXT,
    closed_days           SMALLINT[] NOT NULL DEFAULT '{}',
    closed_until          TIMESTAMPTZ,
    discount              INTEGER,
    discount_min          INTEGER,
    free_delivery_over    INTEGER,
    discount_note         TEXT,
    delivery              BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_note         TEXT,
    terms_accepted        BOOLEAN NOT NULL DEFAULT FALSE,
    shop_code             TEXT UNIQUE,
    -- online {enabled, radiusKm, fee, note, updatedAt}
    online_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    online_radius_km      INTEGER,
    online_fee            TEXT,
    online_note           TEXT,
    online_updated_at     TIMESTAMPTZ,
    -- medicine find (medfind.js)
    find_off              BOOLEAN NOT NULL DEFAULT FALSE,
    find_day              DATE,
    find_count            INTEGER NOT NULL DEFAULT 0,
    -- ratings (behaviour = overall stars; aspects price/delivery tracked separately)
    rating_sum            INTEGER NOT NULL DEFAULT 0,
    rating_count          INTEGER NOT NULL DEFAULT 0,
    aspect_price_sum      INTEGER NOT NULL DEFAULT 0,
    aspect_price_count    INTEGER NOT NULL DEFAULT 0,
    aspect_delivery_sum   INTEGER NOT NULL DEFAULT 0,
    aspect_delivery_count INTEGER NOT NULL DEFAULT 0,
    -- listing columns
    status                TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'suspended', 'rejected')),
    status_reason         TEXT,
    approved_name         TEXT,
    last_approved         JSONB,
    verified_by           TEXT,
    verified_by_email     TEXT,
    verified_at           TIMESTAMPTZ,
    verification_checks   TEXT[],
    docs_have             TEXT[] NOT NULL DEFAULT '{}',
    docs_sent_at          TIMESTAMPTZ,
    docs_requested_at     TIMESTAMPTZ,
    docs_request_note     TEXT,
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           TEXT,
    hidden_for_deletion   TEXT,
    edit_rejected_at      TIMESTAMPTZ,
    edit_rejected_reason  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    is_demo               BOOLEAN GENERATED ALWAYS AS (uid LIKE 'demo-%') STORED
);
CREATE INDEX pharmacies_area_idx ON pharmacies (district, upazila_id) WHERE status = 'approved';
CREATE INDEX pharmacies_status_idx ON pharmacies (status);
CREATE INDEX pharmacies_phone_idx ON pharmacies (phone);

-- labs/{ownerUid}
CREATE TABLE labs (
    uid                   TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    facility_id           TEXT,
    name                  TEXT NOT NULL,
    district              TEXT NOT NULL,
    upazila_id            INTEGER,
    address               TEXT NOT NULL DEFAULT '',
    license_no            TEXT NOT NULL DEFAULT '',
    phone                 TEXT NOT NULL DEFAULT '',
    hours                 TEXT NOT NULL DEFAULT '',
    home_collection       BOOLEAN NOT NULL DEFAULT FALSE,
    terms_accepted        BOOLEAN NOT NULL DEFAULT FALSE,
    status                TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'suspended', 'rejected')),
    status_reason         TEXT,
    approved_name         TEXT,
    last_approved         JSONB,
    verified_by           TEXT,
    verified_by_email     TEXT,
    verified_at           TIMESTAMPTZ,
    verification_checks   TEXT[],
    docs_have             TEXT[] NOT NULL DEFAULT '{}',
    docs_sent_at          TIMESTAMPTZ,
    docs_requested_at     TIMESTAMPTZ,
    docs_request_note     TEXT,
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           TEXT,
    hidden_for_deletion   TEXT,
    edit_rejected_at      TIMESTAMPTZ,
    edit_rejected_reason  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    is_demo               BOOLEAN GENERATED ALWAYS AS (uid LIKE 'demo-%') STORED
);
CREATE INDEX labs_area_idx ON labs (district, upazila_id) WHERE status = 'approved';
CREATE INDEX labs_status_idx ON labs (status);
CREATE INDEX labs_facility_idx ON labs (facility_id) WHERE facility_id IS NOT NULL;

-- labs.ranges{"code.key": {low, high}} (lab's own normal ranges)
CREATE TABLE lab_ranges (
    lab_uid   TEXT NOT NULL REFERENCES labs (uid) ON DELETE CASCADE,
    test_key  TEXT NOT NULL,
    low       DOUBLE PRECISION,
    high      DOUBLE PRECISION,
    PRIMARY KEY (lab_uid, test_key)
);

-- labPrices/{labUid}
CREATE TABLE lab_prices (
    lab_uid       TEXT PRIMARY KEY REFERENCES labs (uid) ON DELETE CASCADE,
    discount_pct  INTEGER CHECK (discount_pct BETWEEN 0 AND 100),
    home_fee      INTEGER,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE lab_price_items (
    lab_uid  TEXT NOT NULL REFERENCES lab_prices (lab_uid) ON DELETE CASCADE,
    idx      INTEGER NOT NULL,
    code     TEXT,
    name     TEXT NOT NULL,
    price    INTEGER NOT NULL CHECK (price >= 0),
    tat      TEXT,
    enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (lab_uid, idx)
);
CREATE INDEX lab_price_items_code_idx ON lab_price_items (code) WHERE code IS NOT NULL;

-- ambulances/{id} (one owner may have several)
CREATE TABLE ambulances (
    id                    TEXT PRIMARY KEY,
    owner_uid             TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    name                  TEXT NOT NULL CHECK (length(name) <= 80),
    phone                 TEXT NOT NULL,
    vehicle_no            TEXT NOT NULL CHECK (length(vehicle_no) <= 40),
    types                 TEXT[] NOT NULL DEFAULT '{}',
    district              TEXT NOT NULL,
    upazila_id            INTEGER,
    area                  TEXT NOT NULL DEFAULT '',
    fare                  TEXT NOT NULL DEFAULT '',
    -- base location (rounded) for distance sort
    lat                   DOUBLE PRECISION,
    lng                   DOUBLE PRECISION,
    available             BOOLEAN NOT NULL DEFAULT FALSE,
    available_at          TIMESTAMPTZ,
    busy_at               TIMESTAMPTZ,
    remind_at             TIMESTAMPTZ,
    reminder_sent_at      TIMESTAMPTZ,
    -- id of the "call an ambulance" trip keeping it busy
    req_busy              TEXT,
    -- fareChart {local, rows[], oxygen, icu, night}
    fare_local            INTEGER,
    fare_oxygen           INTEGER,
    fare_icu              INTEGER,
    fare_night            INTEGER,
    rating_sum            INTEGER NOT NULL DEFAULT 0,
    rating_count          INTEGER NOT NULL DEFAULT 0,
    overcharge_count      INTEGER NOT NULL DEFAULT 0,
    terms_accepted        BOOLEAN NOT NULL DEFAULT FALSE,
    status                TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'suspended', 'rejected')),
    status_reason         TEXT,
    approved_name         TEXT,
    last_approved         JSONB,
    verified_by           TEXT,
    verified_by_email     TEXT,
    verified_at           TIMESTAMPTZ,
    verification_checks   TEXT[],
    docs_have             TEXT[] NOT NULL DEFAULT '{}',
    docs_sent_at          TIMESTAMPTZ,
    docs_requested_at     TIMESTAMPTZ,
    docs_request_note     TEXT,
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           TEXT,
    hidden_for_deletion   TEXT,
    edit_rejected_at      TIMESTAMPTZ,
    edit_rejected_reason  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    is_demo               BOOLEAN GENERATED ALWAYS AS (owner_uid LIKE 'demo-%') STORED
);
CREATE INDEX ambulances_area_idx ON ambulances (district, status);
CREATE INDEX ambulances_owner_idx ON ambulances (owner_uid);
CREATE INDEX ambulances_remind_idx ON ambulances (remind_at) WHERE remind_at IS NOT NULL;
CREATE INDEX ambulances_vehicle_idx ON ambulances (vehicle_no);

-- ambulances.fareChart.rows[] {kind: upazila|district|hospital, upazilaId|district|name, amount}
CREATE TABLE ambulance_fare_rows (
    ambulance_id  TEXT NOT NULL REFERENCES ambulances (id) ON DELETE CASCADE,
    idx           INTEGER NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('upazila', 'district', 'hospital')),
    upazila_id    INTEGER,
    district      TEXT,
    name          TEXT,
    amount        INTEGER NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (ambulance_id, idx)
);

-- bloodBanks/{id}
CREATE TABLE blood_banks (
    id                    TEXT PRIMARY KEY,
    owner_uid             TEXT REFERENCES accounts (uid) ON DELETE CASCADE,
    added_by_admin        BOOLEAN NOT NULL DEFAULT FALSE,
    name                  TEXT NOT NULL CHECK (length(name) <= 100),
    kind                  TEXT NOT NULL CHECK (kind IN ('govt', 'private', 'org')),
    phone                 TEXT NOT NULL,
    district              TEXT NOT NULL,
    upazila_id            INTEGER,
    address               TEXT NOT NULL DEFAULT '',
    license_no            TEXT NOT NULL DEFAULT '',
    open24                BOOLEAN NOT NULL DEFAULT FALSE,
    open_at               TEXT,
    close_at              TEXT,
    closed_days           SMALLINT[] NOT NULL DEFAULT '{}',
    services              TEXT[] NOT NULL DEFAULT '{}',
    terms_accepted        BOOLEAN NOT NULL DEFAULT FALSE,
    status                TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'suspended', 'rejected')),
    status_reason         TEXT,
    approved_name         TEXT,
    last_approved         JSONB,
    verified_by           TEXT,
    verified_by_email     TEXT,
    verified_at           TIMESTAMPTZ,
    verification_checks   TEXT[],
    docs_have             TEXT[] NOT NULL DEFAULT '{}',
    docs_sent_at          TIMESTAMPTZ,
    docs_requested_at     TIMESTAMPTZ,
    docs_request_note     TEXT,
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           TEXT,
    hidden_for_deletion   TEXT,
    edit_rejected_at      TIMESTAMPTZ,
    edit_rejected_reason  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    is_demo               BOOLEAN GENERATED ALWAYS AS (coalesce(owner_uid LIKE 'demo-%', FALSE)) STORED
);
CREATE INDEX blood_banks_area_idx ON blood_banks (district, status);
CREATE INDEX blood_banks_owner_idx ON blood_banks (owner_uid) WHERE owner_uid IS NOT NULL;

-- ---------- calls & ratings (one row per caller per listing; Node ids "{uid}_{listingId}") ----------
CREATE TABLE ambulance_calls (
    uid            TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    ambulance_id   TEXT NOT NULL REFERENCES ambulances (id) ON DELETE CASCADE,
    at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    rating         SMALLINT CHECK (rating BETWEEN 1 AND 5),
    comment        TEXT NOT NULL DEFAULT '',
    rated_at       TIMESTAMPTZ,
    overcharged    BOOLEAN,
    -- trip agreed in-app (ambreq) → overcharge rating allowed
    agreed_fare    INTEGER,
    trip_at        TIMESTAMPTZ,
    by_phone_req   TEXT,
    PRIMARY KEY (uid, ambulance_id)
);
CREATE INDEX ambulance_calls_ambulance_idx ON ambulance_calls (ambulance_id);

CREATE TABLE pharmacy_calls (
    uid           TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    pharmacy_uid  TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (uid, pharmacy_uid)
);

CREATE TABLE pharmacy_ratings (
    uid             TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    pharmacy_uid    TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    behaviour       SMALLINT NOT NULL CHECK (behaviour BETWEEN 1 AND 5),
    price           SMALLINT CHECK (price BETWEEN 1 AND 5),
    delivery        SMALLINT CHECK (delivery BETWEEN 1 AND 5),
    comment         TEXT NOT NULL DEFAULT '',
    first_rated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    rated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (uid, pharmacy_uid)
);
CREATE INDEX pharmacy_ratings_shop_idx ON pharmacy_ratings (pharmacy_uid);

CREATE TABLE blood_bank_calls (
    uid      TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    bank_id  TEXT NOT NULL REFERENCES blood_banks (id) ON DELETE CASCADE,
    at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (uid, bank_id)
);

CREATE TABLE home_calls (
    uid           TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    provider_uid  TEXT NOT NULL REFERENCES providers (uid) ON DELETE CASCADE,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    rating        SMALLINT CHECK (rating BETWEEN 1 AND 5),
    comment       TEXT NOT NULL DEFAULT '',
    rated_at      TIMESTAMPTZ,
    PRIMARY KEY (uid, provider_uid)
);
CREATE INDEX home_calls_provider_idx ON home_calls (provider_uid);

-- ambulanceStats / pharmacyStats / bloodBankStats / homeStats {total, callers, d{YYYYMMDD: n}, recent[]}
CREATE TABLE listing_stats (
    kind        TEXT NOT NULL CHECK (kind IN ('ambulance', 'pharmacy', 'bloodbank', 'home')),
    listing_id  TEXT NOT NULL,
    total       INTEGER NOT NULL DEFAULT 0,
    callers     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, listing_id)
);
CREATE TABLE listing_stats_daily (
    kind        TEXT NOT NULL,
    listing_id  TEXT NOT NULL,
    day         DATE NOT NULL,
    calls       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, listing_id, day),
    FOREIGN KEY (kind, listing_id) REFERENCES listing_stats (kind, listing_id) ON DELETE CASCADE
);
-- recent[] {rating, comment, at, who(hashed uid)} — newest few comments for the owner
CREATE TABLE listing_recent_ratings (
    kind        TEXT NOT NULL,
    listing_id  TEXT NOT NULL,
    who         TEXT NOT NULL,
    rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT NOT NULL DEFAULT '',
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, listing_id, who),
    FOREIGN KEY (kind, listing_id) REFERENCES listing_stats (kind, listing_id) ON DELETE CASCADE
);

-- verifyDocs/{kind_listingId_type} — licence/certificate photos (base64 today; R2 key in phase 2)
CREATE TABLE verify_docs (
    kind         TEXT NOT NULL CHECK (kind IN ('badge', 'pharmacy', 'ambulance', 'bloodbank', 'lab')),
    listing_id   TEXT NOT NULL,
    type         TEXT NOT NULL,
    owner_uid    TEXT REFERENCES accounts (uid) ON DELETE CASCADE,
    data         TEXT,
    storage_key  TEXT,
    size         INTEGER NOT NULL DEFAULT 0,
    at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, listing_id, type),
    CHECK (data IS NOT NULL OR storage_key IS NOT NULL)
);
CREATE INDEX verify_docs_owner_idx ON verify_docs (owner_uid);

-- ---------- medicine find: medFinds/{id} ----------
CREATE TABLE med_finds (
    id            TEXT PRIMARY KEY,
    uid           TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    district      TEXT NOT NULL,
    upazila_id    INTEGER,
    lat           DOUBLE PRECISION,
    lng           DOUBLE PRECISION,
    drug_name     TEXT NOT NULL,
    drug_generic  TEXT,
    drug_form     TEXT,
    drug_company  TEXT,
    status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    closed_at     TIMESTAMPTZ,
    is_demo       BOOLEAN GENERATED ALWAYS AS (uid LIKE 'demo-%') STORED
);
CREATE INDEX med_finds_uid_idx ON med_finds (uid, created_at DESC);
CREATE INDEX med_finds_cleanup_idx ON med_finds (closed_at);
-- medFinds.notified[] and answers{pharmacyUid: {have, at}}
CREATE TABLE med_find_targets (
    find_id       TEXT NOT NULL REFERENCES med_finds (id) ON DELETE CASCADE,
    pharmacy_uid  TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    have          BOOLEAN,
    answered_at   TIMESTAMPTZ,
    PRIMARY KEY (find_id, pharmacy_uid)
);
CREATE INDEX med_find_targets_shop_idx ON med_find_targets (pharmacy_uid);

-- ---------- pharmacyStock/{uid} {names[], keys[], prices{key}, mrp{key}, pricesAll, mrpAll} ----------
CREATE TABLE pharmacy_stock (
    pharmacy_uid  TEXT PRIMARY KEY REFERENCES pharmacies (uid) ON DELETE CASCADE,
    prices_all    BOOLEAN NOT NULL DEFAULT FALSE,
    mrp_all       BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE pharmacy_stock_items (
    pharmacy_uid  TEXT NOT NULL REFERENCES pharmacy_stock (pharmacy_uid) ON DELETE CASCADE,
    key           TEXT NOT NULL,
    name          TEXT NOT NULL,
    in_stock      BOOLEAN NOT NULL DEFAULT TRUE,
    price         NUMERIC(12, 2),
    mrp           NUMERIC(12, 2),
    PRIMARY KEY (pharmacy_uid, key)
);
CREATE INDEX pharmacy_stock_items_key_idx ON pharmacy_stock_items (key) WHERE in_stock;

-- pharmacyMissed/{uid}.items{k: {name, count, lastAt}} — medicines the shop could not give
CREATE TABLE pharmacy_missed (
    pharmacy_uid  TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    item_key      TEXT NOT NULL,
    name          TEXT NOT NULL,
    count         INTEGER NOT NULL DEFAULT 1,
    last_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pharmacy_uid, item_key)
);

-- ---------- "call an ambulance": ambulanceRequests/{id} ----------
CREATE TABLE ambulance_requests (
    id                TEXT PRIMARY KEY,
    uid               TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    patient_name      TEXT NOT NULL DEFAULT '',
    phone             TEXT,
    district          TEXT NOT NULL,
    upazila_id        INTEGER,
    upazila_auto      BOOLEAN NOT NULL DEFAULT FALSE,
    union_id          INTEGER,
    lat               DOUBLE PRECISION,
    lng               DOUBLE PRECISION,
    gps               BOOLEAN NOT NULL DEFAULT FALSE,
    pickup            TEXT,
    note              TEXT,
    type              TEXT,
    status            TEXT NOT NULL CHECK (status IN ('open', 'accepted', 'arrived', 'done', 'cancelled', 'expired', 'byPhone')),
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    wave              INTEGER NOT NULL DEFAULT 1,
    -- fare/quote mode (2.30.0)
    quote             BOOLEAN NOT NULL DEFAULT FALSE,
    max_fare          INTEGER,
    raise_to          INTEGER,
    raised_at         TIMESTAMPTZ,
    include_unknown   BOOLEAN NOT NULL DEFAULT FALSE,
    unknown_free      INTEGER,
    oxygen            BOOLEAN NOT NULL DEFAULT FALSE,
    night             BOOLEAN NOT NULL DEFAULT FALSE,
    -- dest {kind, upazilaId, district, facilityId, name}
    dest_kind         TEXT CHECK (dest_kind IN ('upazila', 'district', 'hospital')),
    dest_upazila_id   INTEGER,
    dest_district     TEXT,
    dest_facility_id  TEXT,
    dest_name         TEXT,
    -- outcome
    accepted_by       TEXT,
    ambulance_id      TEXT,
    ambulance_name    TEXT,
    driver_phone      TEXT,
    booked_owner      TEXT,
    chosen_id         TEXT,
    by_phone_id       TEXT,
    agreed_fare       INTEGER,
    cancelled_by      TEXT CHECK (cancelled_by IN ('patient', 'driver', 'system')),
    cancel_reason     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    opened_at         TIMESTAMPTZ,
    widened_at        TIMESTAMPTZ,
    accepted_at       TIMESTAMPTZ,
    arrived_at        TIMESTAMPTZ,
    done_at           TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    -- privacy purge 24 h after close (dailyCleanup)
    purge_at          TIMESTAMPTZ,
    purged_at         TIMESTAMPTZ,
    is_demo           BOOLEAN GENERATED ALWAYS AS (uid LIKE 'demo-%') STORED
);
CREATE INDEX ambulance_requests_uid_idx ON ambulance_requests (uid, created_at DESC);
CREATE INDEX ambulance_requests_active_idx ON ambulance_requests (status) WHERE active;
CREATE INDEX ambulance_requests_purge_idx ON ambulance_requests (purge_at) WHERE purge_at IS NOT NULL;

-- sentTo{ownerUid: ambulanceId}, activeOwners[], declined[], lapsed[], est{owner: fare}, km{owner: km}
CREATE TABLE ambreq_targets (
    request_id    TEXT NOT NULL REFERENCES ambulance_requests (id) ON DELETE CASCADE,
    owner_uid     TEXT NOT NULL,
    ambulance_id  TEXT NOT NULL,
    wave          INTEGER NOT NULL DEFAULT 1,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    declined      BOOLEAN NOT NULL DEFAULT FALSE,
    lapsed        BOOLEAN NOT NULL DEFAULT FALSE,
    est_fare      INTEGER,
    km            DOUBLE PRECISION,
    sent_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, owner_uid)
);
CREATE INDEX ambreq_targets_owner_idx ON ambreq_targets (owner_uid) WHERE active;
-- quotes{ownerUid: {ambulanceId, name, types, fare, rating, ratingCount, at}}
CREATE TABLE ambreq_quotes (
    request_id    TEXT NOT NULL REFERENCES ambulance_requests (id) ON DELETE CASCADE,
    owner_uid     TEXT NOT NULL,
    ambulance_id  TEXT NOT NULL,
    name          TEXT NOT NULL DEFAULT '',
    types         TEXT[] NOT NULL DEFAULT '{}',
    fare          INTEGER NOT NULL,
    rating        DOUBLE PRECISION NOT NULL DEFAULT 0,
    rating_count  INTEGER NOT NULL DEFAULT 0,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, owner_uid)
);
-- calledIds[] + calledInfo{ambulanceId: {name, phone}} (cars the patient phoned while waiting)
CREATE TABLE ambreq_calls (
    request_id    TEXT NOT NULL REFERENCES ambulance_requests (id) ON DELETE CASCADE,
    ambulance_id  TEXT NOT NULL,
    name          TEXT NOT NULL DEFAULT '',
    phone         TEXT NOT NULL DEFAULT '',
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, ambulance_id)
);
-- fallback[] {id, name, phone, km} (phone list shown after the first wave)
CREATE TABLE ambreq_fallback (
    request_id    TEXT NOT NULL REFERENCES ambulance_requests (id) ON DELETE CASCADE,
    idx           INTEGER NOT NULL,
    ambulance_id  TEXT NOT NULL,
    name          TEXT NOT NULL DEFAULT '',
    phone         TEXT NOT NULL DEFAULT '',
    km            DOUBLE PRECISION,
    PRIMARY KEY (request_id, idx)
);
-- replaced[] {uid, ambulanceId, at, reason}
CREATE TABLE ambreq_replaced (
    request_id    TEXT NOT NULL REFERENCES ambulance_requests (id) ON DELETE CASCADE,
    idx           INTEGER NOT NULL,
    owner_uid     TEXT NOT NULL,
    ambulance_id  TEXT NOT NULL,
    reason        TEXT,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, idx)
);
-- ambReqLimits/{uid} {activeId, at, times[]} — one active request + PER_HOUR creation limit
CREATE TABLE ambreq_patient_state (
    uid                TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    active_request_id  TEXT,
    at                 TIMESTAMPTZ
);
CREATE TABLE ambreq_create_log (
    uid         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (uid, created_at)
);
