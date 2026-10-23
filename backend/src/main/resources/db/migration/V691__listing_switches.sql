-- =====================================================================================
-- V691: provider quick switches (backend/docs/LISTINGS.md "Provider side"):
--   * pharmacy / lab "খোলা/বন্ধ এখন": a manual open-now override that ends at the next scheduled opening/closing
--   * doctor "আজ বন্ধ" per chamber and day (chamber_day_closed) — nextSlot, openNow and book skip it
--   * listing_switch_log: who flipped which switch when (owner or assistant)
-- Additive only (nullable columns, new tables): safe for blue/green — the old server never reads them.
-- =====================================================================================
ALTER TABLE pharmacies ADD COLUMN open_override BOOLEAN;
ALTER TABLE pharmacies ADD COLUMN open_override_until TIMESTAMPTZ;
ALTER TABLE labs ADD COLUMN open_override BOOLEAN;
ALTER TABLE labs ADD COLUMN open_override_until TIMESTAMPTZ;

CREATE TABLE chamber_day_closed (
    provider_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    chamber_key   TEXT NOT NULL,
    visit_date    DATE NOT NULL,
    closed_by     TEXT REFERENCES accounts (uid) ON DELETE SET NULL,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_uid, chamber_key, visit_date)
);

CREATE TABLE listing_switch_log (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT NOT NULL CHECK (kind IN ('pharmacy', 'lab', 'ambulance', 'bloodbank', 'chamber')),
    listing_id  TEXT NOT NULL,
    -- the listing's owner (pharmacy/lab uid, ambulance/blood bank owner, the doctor); the log goes with the account
    owner_uid   TEXT REFERENCES accounts (uid) ON DELETE CASCADE,
    by_uid      TEXT REFERENCES accounts (uid) ON DELETE CASCADE,
    role        TEXT NOT NULL CHECK (role IN ('owner', 'assistant')),
    field       TEXT NOT NULL,
    value       TEXT,
    until       TIMESTAMPTZ,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX listing_switch_log_listing_idx ON listing_switch_log (kind, listing_id, at DESC);
CREATE INDEX listing_switch_log_owner_idx ON listing_switch_log (owner_uid);
CREATE INDEX listing_switch_log_by_idx ON listing_switch_log (by_uid);
