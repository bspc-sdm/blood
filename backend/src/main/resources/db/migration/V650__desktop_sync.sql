-- P17: offline (desktop) sync — registered desktop devices, the op log (idempotency by opId), stock review flags,
-- chamber walk-ins. backend/docs/INVOICES.md "Offline sync".

-- one desktop (Windows) install of a business; series D<n> numbers its offline invoices (never reused, also after revoke)
CREATE TABLE desktop_devices (
    id             TEXT PRIMARY KEY,
    kind           TEXT NOT NULL CHECK (kind IN ('pharmacy', 'lab', 'chamber')),
    -- pharmacy / lab: the owner's uid; chamber: the doctor's uid
    biz_id         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    n              INTEGER NOT NULL CHECK (n >= 1),
    name           TEXT NOT NULL CHECK (length(name) <= 40),
    registered_by  TEXT NOT NULL,
    -- the device secret (HMAC key of its offline_sync / offline_bootstrap requests, header X-Lifolo-Device-Sig) is never
    -- stored: it is derived from the server key + this random salt (DeviceSig.secretOf), shown once at desktop_register;
    -- the salt is removed on revoke
    secret_salt    TEXT CHECK (secret_salt ~ '^[0-9a-f]{64}$'),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sync_at   TIMESTAMPTZ,
    last_sync_by   TEXT,
    ops_count      INTEGER NOT NULL DEFAULT 0,
    revoked_at     TIMESTAMPTZ,
    revoked_by     TEXT,
    is_demo        BOOLEAN GENERATED ALWAYS AS (biz_id LIKE 'demo-%') STORED,
    UNIQUE (kind, biz_id, n)
);
CREATE INDEX desktop_devices_biz_idx ON desktop_devices (biz_id, kind);

-- every op a device sent: the stored result is returned again for the same opId (idempotent sync)
CREATE TABLE offline_ops (
    device_id    TEXT NOT NULL REFERENCES desktop_devices (id) ON DELETE CASCADE,
    op_id        TEXT NOT NULL,
    type         TEXT NOT NULL,
    at           TIMESTAMPTZ,
    ok           BOOLEAN NOT NULL,
    result       JSONB NOT NULL,
    -- the desk's own id of the record (data.localId): pay/void ops refer to an offline receipt by it
    local_id     TEXT CHECK (length(local_id) <= 64),
    -- what the op made (invoice / purchase / adjustment / walk-in id)
    ref_id       TEXT,
    -- the receipt number the desk printed, when the server gave another one (I5 remap)
    device_no    TEXT,
    -- `at` outside the accepted window (older than 8 days / more than 10 minutes ahead): rejected and shown to the owner
    suspicious   BOOLEAN NOT NULL DEFAULT FALSE,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, op_id)
);
CREATE INDEX offline_ops_received_idx ON offline_ops (received_at);
CREATE INDEX offline_ops_local_idx ON offline_ops (device_id, local_id) WHERE local_id IS NOT NULL;
CREATE INDEX offline_ops_device_no_idx ON offline_ops (device_no) WHERE device_no IS NOT NULL;
CREATE INDEX offline_ops_suspicious_idx ON offline_ops (device_id) WHERE suspicious;

-- an offline sale took more than the server's stock: accepted with warn "stock_short", the item is flagged for review
-- until the next stock count (books_adjust kind "count") or purchase of that item
CREATE TABLE shop_stock_flags (
    id           TEXT PRIMARY KEY,
    shop_uid     TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    item_id      TEXT NOT NULL,
    item_name    TEXT NOT NULL,
    wanted       NUMERIC(12, 2) NOT NULL,
    had          NUMERIC(12, 2) NOT NULL,
    short        NUMERIC(12, 2) NOT NULL,
    device_id    TEXT,
    op_id        TEXT,
    at           TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);
CREATE INDEX shop_stock_flags_open_idx ON shop_stock_flags (shop_uid, item_id) WHERE resolved_at IS NULL;

-- a patient who walked into a chamber without an app appointment (desktop "walkin" op): name, optional phone, a serial
-- from the same counter as the app's confirmed appointments (serial_counters)
CREATE TABLE chamber_walkins (
    id           TEXT PRIMARY KEY,
    doctor_uid   TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    chamber_key  TEXT NOT NULL CHECK (length(chamber_key) <= 120),
    visit_date   DATE NOT NULL,
    name         TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    phone        TEXT,
    serial       INTEGER NOT NULL,
    device_id    TEXT,
    created_by   TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_demo      BOOLEAN GENERATED ALWAYS AS (doctor_uid LIKE 'demo-%') STORED
);
CREATE INDEX chamber_walkins_day_idx ON chamber_walkins (doctor_uid, visit_date);
