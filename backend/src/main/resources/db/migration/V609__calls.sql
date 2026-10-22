-- In-app audio/video calls (Oct 2026, backend/docs/CALLS.md). Online chambers / appointments.online: spring-p15 (V620+).

-- ---------- call_sessions: one in-app call (LiveKit room c_<id>). Metadata only — calls are never recorded. ----------
-- kind 'ring' = call_start (ringing → accepted/declined/cancelled/missed → ended);
-- kind 'room' = appt_join (one per appointment; opened when someone joins, re-opened on a later join).
-- caller_uid/callee_uid: for a room the patient / the doctor. Rows are deleted 180 days after created_at (daily job) and
-- with either account (FK cascade + CallsDeletionStep).
CREATE TABLE call_sessions (
    id                TEXT PRIMARY KEY,
    kind              TEXT NOT NULL DEFAULT 'ring' CHECK (kind IN ('ring', 'room')),
    ctx               TEXT NOT NULL CHECK (ctx IN ('blood', 'appointment', 'ambulance', 'order')),
    ref_id            TEXT NOT NULL,
    caller_uid        TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    callee_uid        TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    video             BOOLEAN NOT NULL DEFAULT FALSE,
    state             TEXT NOT NULL CHECK (state IN ('ringing', 'accepted', 'declined', 'missed', 'ended', 'cancelled')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at       TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ,
    -- appt_call_ready push to the patient (at most once per 2 minutes)
    ready_pushed_at   TIMESTAMPTZ,
    is_demo           BOOLEAN GENERATED ALWAYS AS (caller_uid LIKE 'demo-%') STORED,
    CHECK (caller_uid <> callee_uid)
);
CREATE INDEX call_sessions_live_callee_idx ON call_sessions (callee_uid) WHERE state IN ('ringing', 'accepted');
CREATE INDEX call_sessions_live_caller_idx ON call_sessions (caller_uid) WHERE state IN ('ringing', 'accepted');
CREATE INDEX call_sessions_caller_idx ON call_sessions (caller_uid, created_at DESC);
CREATE INDEX call_sessions_callee_idx ON call_sessions (callee_uid, created_at DESC);
CREATE INDEX call_sessions_created_idx ON call_sessions (created_at);
-- the 15-second sweep: ringing past 45 s → missed, accepted past 2 h → ended
CREATE INDEX call_sessions_ringing_idx ON call_sessions (created_at) WHERE state = 'ringing';
CREATE INDEX call_sessions_accepted_idx ON call_sessions (accepted_at) WHERE state = 'accepted';
CREATE UNIQUE INDEX call_sessions_room_idx ON call_sessions (ref_id) WHERE kind = 'room';

-- ---------- notification category "calls" (অ্যাপে কল), default on ----------
ALTER TABLE notify_prefs ADD COLUMN calls BOOLEAN NOT NULL DEFAULT TRUE;
