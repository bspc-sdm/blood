-- In-app audio/video calls (Oct 2026, backend/docs/CALLS.md + docs/test-plan/CALLS-SECURITY-REVIEW.md).
-- Online chambers / appointments.online: spring-p15 (V620+).

-- ---------- call_sessions: one in-app call (LiveKit room = `room`). Metadata only — calls are never recorded. ----------
-- kind 'ring' = call_start (ringing → accepted/declined/cancelled/missed → ended);
-- kind 'room' = appt_join (at most one live room per appointment; a join after the end opens a new row and room).
-- caller_uid/callee_uid: for a room the patient / the doctor. Rows are deleted 180 days after created_at (daily job) and
-- with either account (FK cascade + CallsDeletionStep).
CREATE TABLE call_sessions (
    id                TEXT PRIMARY KEY CHECK (length(id) >= 32),
    room              TEXT NOT NULL,
    kind              TEXT NOT NULL DEFAULT 'ring' CHECK (kind IN ('ring', 'room')),
    ctx               TEXT NOT NULL CHECK (ctx IN ('blood', 'appointment', 'ambulance', 'rx_order', 'lab_order')),
    ref_id            TEXT NOT NULL,
    caller_uid        TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    callee_uid        TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    video             BOOLEAN NOT NULL DEFAULT FALSE,
    state             TEXT NOT NULL CHECK (state IN ('ringing', 'accepted', 'declined', 'missed', 'ended', 'cancelled')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at       TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ,
    -- why it ended when the server ended it: 'timeout' | 'relation' | 'heartbeat' | 'blocked' | 'account' | 'busy_limit'
    end_reason        TEXT,
    -- heartbeats (call_status every 30 s while in a call); a room: caller = patient side, callee = doctor side
    caller_seen_at    TIMESTAMPTZ,
    callee_seen_at    TIMESTAMPTZ,
    -- audio → video upgrade asked by this uid (call_upgrade), until accepted/declined
    upgrade_by        TEXT,
    upgrade_at        TIMESTAMPTZ,
    -- appt_call_ready push to the patient (at most once per 2 minutes)
    ready_pushed_at   TIMESTAMPTZ,
    -- a room: the doctor-side person in it now (doctor or one assistant — the call server allows 2 per room)
    host_uid          TEXT,
    is_demo           BOOLEAN GENERATED ALWAYS AS (caller_uid LIKE 'demo-%') STORED,
    CHECK (caller_uid <> callee_uid)
);
CREATE INDEX call_sessions_live_callee_idx ON call_sessions (callee_uid) WHERE state IN ('ringing', 'accepted');
CREATE INDEX call_sessions_live_caller_idx ON call_sessions (caller_uid) WHERE state IN ('ringing', 'accepted');
CREATE INDEX call_sessions_live_ref_idx ON call_sessions (ctx, ref_id) WHERE state IN ('ringing', 'accepted');
CREATE INDEX call_sessions_caller_idx ON call_sessions (caller_uid, created_at DESC);
CREATE INDEX call_sessions_callee_idx ON call_sessions (callee_uid, created_at DESC);
CREATE INDEX call_sessions_pair_idx ON call_sessions (caller_uid, callee_uid, created_at DESC);
CREATE INDEX call_sessions_created_idx ON call_sessions (created_at);
-- LiveKit webhooks name the room
CREATE INDEX call_sessions_room_name_idx ON call_sessions (room);
-- the 15-second sweep: ringing past 45 s → missed; live calls re-checked (relation, heartbeat)
CREATE INDEX call_sessions_ringing_idx ON call_sessions (created_at) WHERE state = 'ringing';
CREATE UNIQUE INDEX call_sessions_room_idx ON call_sessions (ref_id) WHERE kind = 'room' AND state = 'accepted';

-- ---------- call_blocks: "এই মানুষের অ্যাপে কল বন্ধ করুন" — stops every call between the pair, in every context ----------
CREATE TABLE call_blocks (
    blocker_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    blocked_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_uid, blocked_uid),
    CHECK (blocker_uid <> blocked_uid)
);
CREATE INDEX call_blocks_blocked_idx ON call_blocks (blocked_uid);

-- ---------- call_reports: "রিপোর্ট" on a call, with the call's metadata copied (the call row may be deleted later) ----------
CREATE TABLE call_reports (
    id            TEXT PRIMARY KEY,
    call_id       TEXT NOT NULL,
    reporter_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    reported_uid  TEXT NOT NULL,
    reason        TEXT NOT NULL CHECK (reason IN ('harassment', 'spam', 'fake', 'other')),
    note          TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 500),
    -- {ctx, refId, video, state, startedAt, answeredAt, durationSec, dir}
    call_meta     JSONB NOT NULL DEFAULT '{}',
    status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (call_id, reporter_uid)
);
CREATE INDEX call_reports_open_idx ON call_reports (created_at) WHERE status = 'open';
CREATE INDEX call_reports_reported_idx ON call_reports (reported_uid);

-- ---------- notification category "calls" (অ্যাপে কল), default on ----------
ALTER TABLE notify_prefs ADD COLUMN calls BOOLEAN NOT NULL DEFAULT TRUE;
