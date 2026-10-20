-- =====================================================================================
-- V5: prescriptions, doctor's patient register, pharmacy orders, lab orders/reports, report reviews
-- =====================================================================================

-- rxPatients/{id} — patients a doctor/assistant entered by hand (paper prescriptions)
CREATE TABLE rx_patients (
    id           TEXT PRIMARY KEY,
    doctor_uid   TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    age          INTEGER,
    gender       TEXT,
    phone        TEXT NOT NULL DEFAULT '',
    -- HMAC of the normalized phone (prescriptions.js phoneKey) for claim matching
    phone_key    TEXT NOT NULL DEFAULT '',
    linked_uid   TEXT REFERENCES accounts (uid) ON DELETE SET NULL,
    created_by   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_rx_at   TIMESTAMPTZ,
    is_demo      BOOLEAN GENERATED ALWAYS AS (doctor_uid LIKE 'demo-%') STORED
);
CREATE INDEX rx_patients_doctor_idx ON rx_patients (doctor_uid);
CREATE INDEX rx_patients_phone_key_idx ON rx_patients (doctor_uid, phone_key);
CREATE INDEX rx_patients_linked_idx ON rx_patients (linked_uid) WHERE linked_uid IS NOT NULL;

-- prescriptions/{id}
CREATE TABLE prescriptions (
    id                   TEXT PRIMARY KEY,
    code                 TEXT NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{10}$'),
    doctor_uid           TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    -- patient account (null for paper patients, or after the patient deleted their account)
    patient_uid          TEXT REFERENCES accounts (uid) ON DELETE SET NULL,
    rx_patient_id        TEXT REFERENCES rx_patients (id) ON DELETE SET NULL,
    appointment_id       TEXT,
    chamber_index        INTEGER,
    -- doctor snapshot {name, degrees, specialties, regNo, chamber}
    doctor_name          TEXT NOT NULL DEFAULT '',
    doctor_degrees       TEXT NOT NULL DEFAULT '',
    doctor_specialties   TEXT[] NOT NULL DEFAULT '{}',
    doctor_reg_no        TEXT NOT NULL DEFAULT '',
    doctor_chamber       TEXT,
    -- patient snapshot {name, age, gender}
    patient_name         TEXT NOT NULL DEFAULT '',
    patient_age          INTEGER,
    patient_gender       TEXT,
    complaints           TEXT NOT NULL DEFAULT '',
    -- findings {bp, pulse, temp, weight, spo2, other}
    finding_bp           TEXT,
    finding_pulse        TEXT,
    finding_temp         TEXT,
    finding_weight       TEXT,
    finding_spo2         TEXT,
    finding_other        TEXT,
    diagnosis            TEXT NOT NULL DEFAULT '',
    advice               TEXT NOT NULL DEFAULT '',
    tests                TEXT[] NOT NULL DEFAULT '{}',
    test_place           TEXT NOT NULL DEFAULT '',
    test_place_id        TEXT,
    follow_up_date       DATE,
    follow_up_book_date  DATE,
    follow_up_note       TEXT NOT NULL DEFAULT '',
    -- when bought medicines run out (refill reminder)
    refill_at            DATE,
    status               TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'cancelled')),
    cancelled_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_demo              BOOLEAN GENERATED ALWAYS AS (doctor_uid LIKE 'demo-%') STORED
);
CREATE INDEX prescriptions_doctor_idx ON prescriptions (doctor_uid, created_at DESC);
CREATE INDEX prescriptions_patient_idx ON prescriptions (patient_uid, created_at DESC) WHERE patient_uid IS NOT NULL;
CREATE INDEX prescriptions_rx_patient_idx ON prescriptions (rx_patient_id) WHERE rx_patient_id IS NOT NULL;
CREATE INDEX prescriptions_follow_up_idx ON prescriptions (follow_up_date) WHERE follow_up_date IS NOT NULL;
CREATE INDEX prescriptions_refill_idx ON prescriptions (refill_at) WHERE refill_at IS NOT NULL;

-- prescriptions.medicines[] (index = "i" used by dispensing and sales)
CREATE TABLE prescription_medicines (
    rx_id     TEXT NOT NULL REFERENCES prescriptions (id) ON DELETE CASCADE,
    idx       INTEGER NOT NULL,
    name      TEXT NOT NULL,
    form      TEXT,
    strength  TEXT,
    dose      TEXT,
    timing    TEXT,
    days      INTEGER,
    note      TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (rx_id, idx)
);

-- prescriptions.dispensed[] {by, name, at, items[{i, qty}]}
CREATE TABLE prescription_dispensings (
    id            BIGSERIAL PRIMARY KEY,
    rx_id         TEXT NOT NULL REFERENCES prescriptions (id) ON DELETE CASCADE,
    pharmacy_uid  TEXT NOT NULL,
    pharmacy_name TEXT NOT NULL DEFAULT '',
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX prescription_dispensings_rx_idx ON prescription_dispensings (rx_id, at);
CREATE INDEX prescription_dispensings_shop_idx ON prescription_dispensings (pharmacy_uid, at);
CREATE TABLE prescription_dispensed_items (
    dispensing_id  BIGINT NOT NULL REFERENCES prescription_dispensings (id) ON DELETE CASCADE,
    med_idx        INTEGER NOT NULL,
    qty            INTEGER NOT NULL CHECK (qty > 0),
    PRIMARY KEY (dispensing_id, med_idx)
);

-- rxOrders/{id} — prescription sent to a pharmacy (shop visit or online delivery)
CREATE TABLE rx_orders (
    id                  TEXT PRIMARY KEY,
    rx_id               TEXT NOT NULL REFERENCES prescriptions (id) ON DELETE CASCADE,
    code                TEXT NOT NULL,
    patient_uid         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    pharmacy_uid        TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    pharmacy_name       TEXT NOT NULL DEFAULT '',
    patient_name        TEXT NOT NULL DEFAULT '',
    mode                TEXT NOT NULL CHECK (mode IN ('shop', 'online')),
    status              TEXT NOT NULL CHECK (status IN ('sent', 'ready', 'accepted', 'out', 'done', 'cancelled', 'withdrawn')),
    -- delivery contact (purged 7 days after close)
    address             TEXT,
    phone               TEXT,
    note                TEXT,
    price               TEXT,
    eta                 TEXT,
    delivery_fee        TEXT,
    reason              TEXT,
    withdraw_reason     TEXT,
    missed              TEXT[] NOT NULL DEFAULT '{}',
    by_caretaker        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    done_at             TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    purge_contact_at    TIMESTAMPTZ,
    contact_removed_at  TIMESTAMPTZ,
    is_demo             BOOLEAN GENERATED ALWAYS AS (patient_uid LIKE 'demo-%') STORED
);
CREATE INDEX rx_orders_rx_idx ON rx_orders (rx_id);
CREATE INDEX rx_orders_pharmacy_idx ON rx_orders (pharmacy_uid, created_at DESC);
CREATE INDEX rx_orders_patient_idx ON rx_orders (patient_uid);
CREATE INDEX rx_orders_purge_idx ON rx_orders (purge_contact_at) WHERE purge_contact_at IS NOT NULL;

-- labOrders/{id}
CREATE TABLE lab_orders (
    id                   TEXT PRIMARY KEY,
    rx_id                TEXT REFERENCES prescriptions (id) ON DELETE SET NULL,
    code                 TEXT,
    lab_uid              TEXT NOT NULL REFERENCES labs (uid) ON DELETE CASCADE,
    lab_name             TEXT NOT NULL DEFAULT '',
    patient_uid          TEXT REFERENCES accounts (uid) ON DELETE CASCADE,
    patient_name         TEXT NOT NULL DEFAULT '',
    patient_age          INTEGER,
    patient_gender       TEXT,
    doctor_uid           TEXT,
    doctor_name          TEXT NOT NULL DEFAULT '',
    tests                TEXT[] NOT NULL DEFAULT '{}',
    status               TEXT NOT NULL CHECK (status IN ('sent', 'received', 'reported', 'cancelled')),
    walk_in              BOOLEAN NOT NULL DEFAULT FALSE,
    by_caretaker         TEXT,
    -- home sample collection {date, slot, note, fee, status, requestedAt, visitDate, time, at, confirmNote,
    --                         confirmedAt, declineNote, declinedAt, address, phone}
    home_status          TEXT CHECK (home_status IN ('requested', 'confirmed', 'declined')),
    home_date            DATE,
    home_slot            TEXT CHECK (home_slot IN ('early', 'morning', 'afternoon')),
    home_note            TEXT,
    home_fee             INTEGER,
    home_address         TEXT,
    home_phone           TEXT,
    home_requested_at    TIMESTAMPTZ,
    home_visit_date      DATE,
    home_time            TEXT,
    home_at              TIMESTAMPTZ,
    home_confirm_note    TEXT,
    home_confirmed_at    TIMESTAMPTZ,
    home_decline_note    TEXT,
    home_declined_at     TIMESTAMPTZ,
    home_cancelled_at    TIMESTAMPTZ,
    -- prices snapshot {items[], missing[], subtotal, discountPct, estimate, at}
    price_subtotal       INTEGER,
    price_discount_pct   INTEGER,
    price_estimate       INTEGER,
    price_missing        TEXT[] NOT NULL DEFAULT '{}',
    price_at             TIMESTAMPTZ,
    -- report {items[], note, photos[], reportedAt}
    report_note          TEXT,
    reported_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ,
    is_demo              BOOLEAN GENERATED ALWAYS AS (lab_uid LIKE 'demo-%') STORED
);
CREATE INDEX lab_orders_lab_idx ON lab_orders (lab_uid, created_at DESC);
CREATE INDEX lab_orders_rx_idx ON lab_orders (rx_id);
CREATE INDEX lab_orders_patient_idx ON lab_orders (patient_uid) WHERE patient_uid IS NOT NULL;
CREATE INDEX lab_orders_home_open_idx ON lab_orders (lab_uid) WHERE home_address IS NOT NULL;

CREATE TABLE lab_order_price_items (
    order_id  TEXT NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    idx       INTEGER NOT NULL,
    test      TEXT NOT NULL,
    name      TEXT,
    price     INTEGER,
    tat       TEXT,
    PRIMARY KEY (order_id, idx)
);
CREATE TABLE lab_report_items (
    order_id     TEXT NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    idx          INTEGER NOT NULL,
    test         TEXT NOT NULL,
    result       TEXT NOT NULL DEFAULT '',
    unit         TEXT,
    range_text   TEXT,
    flag         TEXT CHECK (flag IN ('low', 'high', 'normal', '')),
    group_name   TEXT,
    code         TEXT,
    param_key    TEXT,
    low          DOUBLE PRECISION,
    high         DOUBLE PRECISION,
    PRIMARY KEY (order_id, idx)
);
-- report photos (≤2 small JPEGs; base64 today, R2 keys in phase 2)
CREATE TABLE lab_report_photos (
    order_id     TEXT NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    idx          INTEGER NOT NULL,
    data         TEXT,
    storage_key  TEXT,
    PRIMARY KEY (order_id, idx)
);

-- rxReviews/{labOrderId} — doctor's paid/free online look at a lab report
CREATE TABLE rx_reviews (
    lab_order_id  TEXT PRIMARY KEY REFERENCES lab_orders (id) ON DELETE CASCADE,
    rx_id         TEXT,
    doctor_uid    TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    patient_uid   TEXT REFERENCES accounts (uid) ON DELETE CASCADE,
    doctor_name   TEXT NOT NULL DEFAULT '',
    lab_name      TEXT NOT NULL DEFAULT '',
    patient_name  TEXT NOT NULL DEFAULT '',
    tests         TEXT[] NOT NULL DEFAULT '{}',
    status        TEXT NOT NULL CHECK (status IN ('off', 'unpaid', 'claimed', 'paid', 'done')),
    fee           TEXT,
    no_fee        BOOLEAN NOT NULL DEFAULT FALSE,
    pay_number    TEXT,
    pay_methods   TEXT[] NOT NULL DEFAULT '{}',
    method        TEXT,
    trx_id        TEXT,
    reject_note   TEXT,
    paid_at       TIMESTAMPTZ,
    comment       TEXT,
    new_rx_id     TEXT,
    done_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX rx_reviews_doctor_idx ON rx_reviews (doctor_uid, updated_at DESC);
CREATE INDEX rx_reviews_patient_idx ON rx_reviews (patient_uid);
