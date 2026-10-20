-- =====================================================================================
-- V7: health Q&A, tips, moderation reports, support tickets
-- =====================================================================================

-- questions/{id}
CREATE TABLE questions (
    id              TEXT PRIMARY KEY,
    uid             TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    author_name     TEXT NOT NULL DEFAULT '' CHECK (length(author_name) <= 80),
    anonymous       BOOLEAN NOT NULL DEFAULT FALSE,
    text            TEXT NOT NULL CHECK (length(text) <= 1000),
    answer_count    INTEGER NOT NULL DEFAULT 0,
    last_answer_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at       TIMESTAMPTZ
);
CREATE INDEX questions_created_idx ON questions (created_at DESC);
CREATE INDEX questions_uid_idx ON questions (uid);

-- questions/{id}/answers/{id}
CREATE TABLE answers (
    id           TEXT PRIMARY KEY,
    question_id  TEXT NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    uid          TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    author_name  TEXT NOT NULL DEFAULT '',
    badge        TEXT NOT NULL CHECK (badge IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'expert')),
    text         TEXT NOT NULL CHECK (length(text) <= 2000),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at    TIMESTAMPTZ
);
CREATE INDEX answers_question_idx ON answers (question_id, created_at);
CREATE INDEX answers_uid_idx ON answers (uid);

-- tips/{id}
CREATE TABLE tips (
    id           TEXT PRIMARY KEY,
    uid          TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    author_name  TEXT NOT NULL DEFAULT '',
    badge        TEXT NOT NULL CHECK (badge IN ('doctor', 'nurse', 'nutritionist', 'trainer', 'expert')),
    title        TEXT NOT NULL CHECK (length(title) <= 120),
    body         TEXT NOT NULL CHECK (length(body) <= 3000),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at    TIMESTAMPTZ
);
CREATE INDEX tips_created_idx ON tips (created_at DESC);
CREATE INDEX tips_uid_idx ON tips (uid);

-- modReports/{collection_id__reporterUid} (one report per reporter per target)
CREATE TABLE mod_reports (
    id            TEXT PRIMARY KEY,
    target_path   TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('question', 'answer', 'tip', 'homecare', 'provider', 'ambulance', 'pharmacy', 'bloodbank')),
    reporter_uid  TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    reported_uid  TEXT,
    reason        TEXT NOT NULL DEFAULT '',
    -- copy of the reported text/listing name at report time
    snapshot      TEXT NOT NULL DEFAULT '',
    status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    UNIQUE (target_path, reporter_uid)
);
CREATE INDEX mod_reports_open_idx ON mod_reports (target_path) WHERE status = 'open';

-- tickets/{id} (support & appeals)
CREATE TABLE tickets (
    id          TEXT PRIMARY KEY,
    uid         TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    email       TEXT NOT NULL DEFAULT '',
    name        TEXT NOT NULL DEFAULT '',
    category    TEXT NOT NULL CHECK (category IN ('account', 'badge', 'pharmacy', 'ambulance', 'bloodbank', 'homevisit', 'homecare', 'medorder', 'appointment', 'blood', 'other')),
    status      TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'answered', 'closed')),
    last_from   TEXT NOT NULL DEFAULT 'user' CHECK (last_from IN ('user', 'admin')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tickets_uid_idx ON tickets (uid, updated_at DESC);
CREATE INDEX tickets_status_idx ON tickets (status, updated_at DESC);
-- tickets.messages[] {from, text, at}
CREATE TABLE ticket_messages (
    ticket_id  TEXT NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    idx        INTEGER NOT NULL,
    sender     TEXT NOT NULL CHECK (sender IN ('user', 'admin')),
    text       TEXT NOT NULL,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ticket_id, idx)
);
