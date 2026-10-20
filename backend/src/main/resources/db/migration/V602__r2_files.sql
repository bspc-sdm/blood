-- =====================================================================================
-- V602: photo/document bytes on Cloudflare R2 (S3 API) instead of base64 text in PostgreSQL.
--
-- Only when R2 is configured (R2_* env vars, see deploy/R2.md): a new photo is uploaded to the private bucket and
-- the row keeps a reference (key → stored_objects: size, content type, sha256). Without R2 nothing changes: the
-- base64 text stays in the old column. Reads accept both (old column first, else the key), so rows can be moved
-- one by one (POST /api/cron/storage/migrate) while the app runs.
--
--   user_profiles.photo / photo_thumb  → photo_key / photo_thumb_key   (new columns)
--   verify_docs.data                   → verify_docs.storage_key        (column existed since V4)
--   lab_report_photos.data             → lab_report_photos.storage_key  (column existed since V5)
--
-- Deleting: whenever a key leaves a row (row deleted — also by ON DELETE CASCADE from accounts/lab_orders —,
-- or the key replaced/cleared) a trigger removes its stored_objects row and queues the key in
-- stored_objects_trash in the same transaction; a job deletes the R2 object after commit. So account deletion,
-- listing removal, a new report and the demo wipe delete R2 objects without each path knowing about R2, and a
-- rolled-back transaction deletes nothing.
-- =====================================================================================

CREATE TABLE stored_objects (
    key           TEXT PRIMARY KEY,
    size          INTEGER NOT NULL CHECK (size >= 0),
    content_type  TEXT NOT NULL,
    -- hex SHA-256 of the object bytes
    sha256        TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- bytes: decoded image, the API gets standard base64 of it back;
    -- b64text: the exact base64 text as sent (not canonical base64: prefix, whitespace, no padding …)
    encoding      TEXT NOT NULL CHECK (encoding IN ('bytes', 'b64text')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stored_objects_trash (
    key         TEXT PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts    INTEGER NOT NULL DEFAULT 0,
    last_error  TEXT
);
CREATE INDEX stored_objects_trash_at_idx ON stored_objects_trash (at);

ALTER TABLE user_profiles ADD COLUMN photo_key TEXT, ADD COLUMN photo_thumb_key TEXT;

-- rows still holding base64 (the migration job and its status count read these)
CREATE INDEX user_profiles_photo_b64_idx ON user_profiles (uid) WHERE photo IS NOT NULL OR photo_thumb IS NOT NULL;
CREATE INDEX verify_docs_b64_idx ON verify_docs (kind, listing_id, type) WHERE data IS NOT NULL;
CREATE INDEX lab_report_photos_b64_idx ON lab_report_photos (order_id, idx) WHERE data IS NOT NULL;

-- TG_ARGV = the key columns of the table. A key that is no longer in the row → trash.
CREATE FUNCTION stored_object_release() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    col TEXT;
    old_key TEXT;
    new_key TEXT;
BEGIN
    FOREACH col IN ARRAY TG_ARGV LOOP
        old_key := to_jsonb(OLD) ->> col;
        new_key := CASE WHEN TG_OP = 'UPDATE' THEN to_jsonb(NEW) ->> col END;
        IF old_key IS NOT NULL AND old_key IS DISTINCT FROM new_key THEN
            DELETE FROM stored_objects WHERE key = old_key;
            INSERT INTO stored_objects_trash (key) VALUES (old_key) ON CONFLICT (key) DO NOTHING;
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE TRIGGER user_profiles_release_del AFTER DELETE ON user_profiles FOR EACH ROW
    WHEN (OLD.photo_key IS NOT NULL OR OLD.photo_thumb_key IS NOT NULL)
    EXECUTE FUNCTION stored_object_release('photo_key', 'photo_thumb_key');
CREATE TRIGGER user_profiles_release_upd AFTER UPDATE OF photo_key, photo_thumb_key ON user_profiles FOR EACH ROW
    WHEN (OLD.photo_key IS DISTINCT FROM NEW.photo_key OR OLD.photo_thumb_key IS DISTINCT FROM NEW.photo_thumb_key)
    EXECUTE FUNCTION stored_object_release('photo_key', 'photo_thumb_key');

CREATE TRIGGER verify_docs_release_del AFTER DELETE ON verify_docs FOR EACH ROW
    WHEN (OLD.storage_key IS NOT NULL)
    EXECUTE FUNCTION stored_object_release('storage_key');
CREATE TRIGGER verify_docs_release_upd AFTER UPDATE OF storage_key ON verify_docs FOR EACH ROW
    WHEN (OLD.storage_key IS DISTINCT FROM NEW.storage_key)
    EXECUTE FUNCTION stored_object_release('storage_key');

CREATE TRIGGER lab_report_photos_release_del AFTER DELETE ON lab_report_photos FOR EACH ROW
    WHEN (OLD.storage_key IS NOT NULL)
    EXECUTE FUNCTION stored_object_release('storage_key');
CREATE TRIGGER lab_report_photos_release_upd AFTER UPDATE OF storage_key ON lab_report_photos FOR EACH ROW
    WHEN (OLD.storage_key IS DISTINCT FROM NEW.storage_key)
    EXECUTE FUNCTION stored_object_release('storage_key');
