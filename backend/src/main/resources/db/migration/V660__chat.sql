-- In-app messages (Oct 2026, backend/docs/CHAT.md). Who may write to whom is the calls' ContactPolicy (V622 call_blocks).
-- Messages are stored here (photos on R2 via stored_objects), not end-to-end encrypted. A thread becomes read-only when
-- its relation ends (closed_at) and is deleted 90 days later with everything in it (daily step chatDeleted); it is
-- deleted at once with either person's account (FK cascade + ChatDeletionStep).

-- ---------- chat_threads: one per pair + ctx + refId (uid_a < uid_b) ----------
CREATE TABLE chat_threads (
    id            TEXT PRIMARY KEY,
    ctx           TEXT NOT NULL CHECK (ctx IN ('blood', 'appointment', 'ambulance', 'rx_order', 'lab_order')),
    ref_id        TEXT NOT NULL,
    uid_a         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    uid_b         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- the newest message (ordering of chat_threads); NULL = no message yet (not listed)
    last_at       TIMESTAMPTZ,
    -- the relation ended: read-only; deleted 90 days after this
    closed_at     TIMESTAMPTZ,
    -- "at most 20 messages before the other side replies": who wrote the current run and how many
    run_uid       TEXT,
    run_count     INTEGER NOT NULL DEFAULT 0,
    is_demo       BOOLEAN GENERATED ALWAYS AS (uid_a LIKE 'demo-%') STORED,
    CHECK (uid_a < uid_b),
    UNIQUE (uid_a, uid_b, ctx, ref_id)
);
CREATE INDEX chat_threads_a_idx ON chat_threads (uid_a, last_at DESC);
CREATE INDEX chat_threads_b_idx ON chat_threads (uid_b, last_at DESC);
CREATE INDEX chat_threads_ref_idx ON chat_threads (ctx, ref_id);
CREATE INDEX chat_threads_closed_idx ON chat_threads (closed_at) WHERE closed_at IS NOT NULL;
CREATE INDEX chat_threads_open_idx ON chat_threads (id) WHERE closed_at IS NULL;

-- ---------- chat_messages ----------
-- kind text | photo | location. Unsend (10 min) erases text/photo/location at once and sets unsent_at.
-- photo_key: the re-encoded JPEG on R2 (no EXIF); photo_data: the same as base64 when R2 is not configured.
-- thumb: a small base64 JPEG (≤ 160 px) sent inline.
CREATE TABLE chat_messages (
    id          TEXT PRIMARY KEY,
    thread_id   TEXT NOT NULL REFERENCES chat_threads (id) ON DELETE CASCADE,
    from_uid    TEXT NOT NULL,
    client_id   TEXT NOT NULL CHECK (length(client_id) BETWEEN 1 AND 64),
    kind        TEXT NOT NULL CHECK (kind IN ('text', 'photo', 'location')),
    text        TEXT CHECK (length(text) <= 1000),
    photo_key   TEXT,
    photo_data  TEXT,
    thumb       TEXT,
    lat         DOUBLE PRECISION CHECK (lat BETWEEN -90 AND 90),
    lng         DOUBLE PRECISION CHECK (lng BETWEEN -180 AND 180),
    at          TIMESTAMPTZ NOT NULL,
    read_at     TIMESTAMPTZ,
    unsent_at   TIMESTAMPTZ,
    UNIQUE (thread_id, from_uid, client_id)
);
CREATE INDEX chat_messages_page_idx ON chat_messages (thread_id, at DESC, id DESC);
CREATE INDEX chat_messages_unread_idx ON chat_messages (thread_id, from_uid) WHERE read_at IS NULL AND unsent_at IS NULL;
CREATE INDEX chat_messages_photos_idx ON chat_messages (from_uid, at) WHERE kind = 'photo';

-- a photo leaving a row (message deleted — also by cascade —, unsent) → its R2 object is deleted (V602)
CREATE TRIGGER chat_messages_release_del AFTER DELETE ON chat_messages FOR EACH ROW
    WHEN (OLD.photo_key IS NOT NULL)
    EXECUTE FUNCTION stored_object_release('photo_key');
CREATE TRIGGER chat_messages_release_upd AFTER UPDATE OF photo_key ON chat_messages FOR EACH ROW
    WHEN (OLD.photo_key IS DISTINCT FROM NEW.photo_key)
    EXECUTE FUNCTION stored_object_release('photo_key');

-- ---------- chat_reports: "রিপোর্ট" on a message, its content copied for the Lifolo team ----------
CREATE TABLE chat_reports (
    id            TEXT PRIMARY KEY,
    message_id    TEXT NOT NULL,
    thread_id     TEXT NOT NULL,
    reporter_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    reported_uid  TEXT NOT NULL,
    reason        TEXT NOT NULL CHECK (reason IN ('harassment', 'spam', 'fake', 'other')),
    note          TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 500),
    -- {ctx, refId, kind, text?, location?, at, thumb?}
    message_meta  JSONB NOT NULL DEFAULT '{}',
    -- a copy of the reported photo (the message may be unsent or deleted later): R2 key, or base64 without R2
    photo_key     TEXT,
    photo_data    TEXT,
    status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    UNIQUE (message_id, reporter_uid)
);
CREATE INDEX chat_reports_open_idx ON chat_reports (created_at) WHERE status = 'open';
CREATE INDEX chat_reports_reported_idx ON chat_reports (reported_uid);
CREATE TRIGGER chat_reports_release_del AFTER DELETE ON chat_reports FOR EACH ROW
    WHEN (OLD.photo_key IS NOT NULL)
    EXECUTE FUNCTION stored_object_release('photo_key');

-- ---------- notification category "messages" (মেসেজ), default on ----------
ALTER TABLE notify_prefs ADD COLUMN messages BOOLEAN NOT NULL DEFAULT TRUE;
