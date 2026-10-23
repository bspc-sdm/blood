-- =====================================================================================
-- V693: "আমার কাজ" — a short break of today's chamber (backend/docs/MYWORK.md, chamber_day_set {closed: true, until}).
-- One row per chamber; it ends by itself at until_at (every read compares with now — no job needed). While it lasts
-- the chamber is not "open now" on the card; bookings and nextSlot are not affected (unlike chamber_day_closed).
-- Additive only (a new table): safe for blue/green — the old server never reads it.
-- =====================================================================================
CREATE TABLE chamber_pause (
    provider_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    chamber_key   TEXT NOT NULL,
    until_at      TIMESTAMPTZ NOT NULL,
    paused_by     TEXT REFERENCES accounts (uid) ON DELETE SET NULL,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_uid, chamber_key)
);
