-- Feature switches (backend/docs/FEATURES.md). A key without a row is OFF.
CREATE TABLE feature_flags (
    key         TEXT PRIMARY KEY,
    mode        TEXT NOT NULL DEFAULT 'off' CHECK (mode IN ('off', 'pilot', 'on')),
    pilot_uids  TEXT[] NOT NULL DEFAULT '{}',
    demo        BOOLEAN NOT NULL DEFAULT false,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  TEXT
);

-- every admin change (history on the admin page); max(id) is the version `v` the clients compare
CREATE TABLE feature_changes (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    key         TEXT NOT NULL,
    mode        TEXT NOT NULL,
    pilot_uids  TEXT[] NOT NULL DEFAULT '{}',
    demo        BOOLEAN NOT NULL DEFAULT false,
    prev_mode   TEXT,
    prev_demo   BOOLEAN,
    prev_pilot  TEXT[],
    by_uid      TEXT,
    by_email    TEXT
);
CREATE INDEX feature_changes_key_idx ON feature_changes (key, id DESC);

-- accounts whose app has asked for `features` (only those get the silent features_changed push: an older app would
-- show it as an empty notification)
CREATE TABLE feature_clients (
    uid      TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    seen_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
