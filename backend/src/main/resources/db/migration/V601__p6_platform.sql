-- =====================================================================================
-- V601 (P6-rest): platform, trust & admin — fields the Node documents have that V1/V7 did not map
-- (support.js, moderation.js, adminweb.js).
-- =====================================================================================

-- tickets.messages[].by: the admin's email on a reply (support.js admin_ticket_reply; not shown to the user)
ALTER TABLE ticket_messages ADD COLUMN by_email TEXT;

-- admin_user: "reports against this person"; admin_user_section "reports made"
CREATE INDEX mod_reports_reported_idx ON mod_reports (reported_uid) WHERE reported_uid IS NOT NULL;
CREATE INDEX mod_reports_reporter_idx ON mod_reports (reporter_uid);

-- admin_user_section "admin": the history of one person's things
CREATE INDEX admin_log_target_idx ON admin_log (target, at DESC);

-- phone-code limits cleanup (daily step 6)
CREATE INDEX rate_counters_scope_day_idx ON rate_counters (scope, day);
