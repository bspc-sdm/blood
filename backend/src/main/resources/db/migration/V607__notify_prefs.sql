-- Per-category notification settings (notify_prefs_get / notify_prefs_set, Oct 2026). One row per person who changed
-- something; no row = everything on. `all_on` is the master switch. "blood_nearby" is NOT a column here: it is the
-- existing user_profiles.notify (one source of truth, nothing to keep in sync). "account" is always on (no column).
-- Turning a category off stops the phone push only; the in-app bell list (inbox_items) still gets every item.
CREATE TABLE notify_prefs (
    uid              TEXT PRIMARY KEY REFERENCES accounts (uid) ON DELETE CASCADE,
    all_on           BOOLEAN NOT NULL DEFAULT TRUE,
    blood_emergency  BOOLEAN NOT NULL DEFAULT TRUE,
    my_request       BOOLEAN NOT NULL DEFAULT TRUE,
    appointments     BOOLEAN NOT NULL DEFAULT TRUE,
    rx               BOOLEAN NOT NULL DEFAULT TRUE,
    ambulance        BOOLEAN NOT NULL DEFAULT TRUE,
    community        BOOLEAN NOT NULL DEFAULT TRUE,
    business         BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Until now emergency appeals reached only donors with user_profiles.notify on. They are their own category now; people
-- who had turned the old switch off keep not getting them (they can turn "emergency" on separately).
INSERT INTO notify_prefs (uid, blood_emergency)
SELECT p.uid, FALSE FROM user_profiles p JOIN accounts a ON a.uid = p.uid WHERE NOT p.notify
ON CONFLICT (uid) DO NOTHING;
