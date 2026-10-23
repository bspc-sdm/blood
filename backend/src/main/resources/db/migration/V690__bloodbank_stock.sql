-- =====================================================================================
-- V690: blood bank stock per blood group (backend/docs/LISTINGS.md) — the owner sets it from "আমার কাজ"
--       (bloodbank_stock_set); the lists show it with stockUpdatedAt and stockStale (older than 48 h).
-- Additive only (a new table), safe for blue/green: the old server never reads it.
-- =====================================================================================
CREATE TABLE blood_bank_stock (
    bank_id     TEXT PRIMARY KEY REFERENCES blood_banks (id) ON DELETE CASCADE,
    -- {"A+": "have"|"low"|"none", …}; a group that is not in the map is unknown
    groups      JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  TEXT
);
