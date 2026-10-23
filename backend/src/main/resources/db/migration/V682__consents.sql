-- Explicit consent for health data (backend/docs/CONSENT.md), behind the `health_consent` feature switch.
-- Bangladesh's personal data protection law treats health data as sensitive: explicit, specific consent, recorded.
--
-- An append-only audit log: one row per grant or withdrawal of one scope by the person themselves (never a
-- caregiver). A scope's state is its latest row; a row whose `version` is not the current text version counts as
-- "outdated" (asked again). Rows are never updated (trigger below); they go with the account (ON DELETE CASCADE).
-- Backward compatible: a new table only — the old code never reads it.
CREATE TABLE consents (
    id       BIGSERIAL PRIMARY KEY,
    uid      TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    scope    TEXT NOT NULL CHECK (scope IN ('health_data', 'data_abroad')),
    version  TEXT NOT NULL CHECK (length(version) BETWEEN 1 AND 40),
    action   TEXT NOT NULL CHECK (action IN ('granted', 'withdrawn')),
    source   TEXT NOT NULL CHECK (source IN ('app', 'web', 'desktop')),
    at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX consents_uid_scope_idx ON consents (uid, scope, id DESC);

-- history is never rewritten
CREATE FUNCTION consents_no_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'consents rows are never updated (append a new row)';
END;
$$;
CREATE TRIGGER consents_no_update BEFORE UPDATE ON consents FOR EACH ROW EXECUTE FUNCTION consents_no_update();
