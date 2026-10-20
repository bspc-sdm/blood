-- =====================================================================================
-- V600 (P6 kernel): fixes the shared trust tables for the Node moderation/listing-review rules
-- (moderation.js, listingreview.js). Package migrations use V{100·k + i} — see PHASE2-PLAN.md.
-- =====================================================================================

-- modReports: Node statuses are open | dismissed (admin "keep") | removed (report upheld).
-- reportCount counts open + removed; reopenOnEdit sets dismissed → open with reopenedAt/reopenReason.
ALTER TABLE mod_reports DROP CONSTRAINT IF EXISTS mod_reports_status_check;
ALTER TABLE mod_reports ADD CONSTRAINT mod_reports_status_check CHECK (status IN ('open', 'dismissed', 'removed'));
ALTER TABLE mod_reports ADD COLUMN reopened_at   TIMESTAMPTZ;
ALTER TABLE mod_reports ADD COLUMN reopen_reason TEXT;
ALTER TABLE mod_reports ADD COLUMN reported_role TEXT;
ALTER TABLE mod_reports ADD COLUMN reported_name TEXT;
-- The Node document id is "{path with _}__{reporterUid}[__{reportedUid}][__provider]": one reporter may report the
-- same provider twice (home care and chamber). The id column keeps that id; the old UNIQUE was too strict.
ALTER TABLE mod_reports DROP CONSTRAINT IF EXISTS mod_reports_target_path_reporter_uid_key;
-- reportCount / assertNoOpenReports / reopenOnEdit / admin_report_resolve: by target and status
CREATE INDEX mod_reports_target_status_idx ON mod_reports (target_path, status);

-- listings: suspendedAt (admin list shows it as "reviewed at" for suspended listings)
ALTER TABLE ambulances  ADD COLUMN suspended_at TIMESTAMPTZ;
ALTER TABLE pharmacies  ADD COLUMN suspended_at TIMESTAMPTZ;
ALTER TABLE blood_banks ADD COLUMN suspended_at TIMESTAMPTZ;
ALTER TABLE labs        ADD COLUMN suspended_at TIMESTAMPTZ;

-- sellerstats "recent": newest first per listing
CREATE INDEX listing_recent_ratings_at_idx ON listing_recent_ratings (kind, listing_id, at DESC);

-- contracts (com.lifolo.contracts): providers.assistantEmails[] keeps its order; adminweb.js also suspends with
-- reason 'account' (account disabled by an admin)
ALTER TABLE provider_assistants ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
ALTER TABLE providers DROP CONSTRAINT IF EXISTS providers_suspend_reason_check;
ALTER TABLE providers ADD CONSTRAINT providers_suspend_reason_check CHECK (suspend_reason IN ('admin', 'report', 'deletion', 'account'));
