-- Temporary account deactivation (account_deactivate / account_reactivate, Oct 2026).
--
-- accounts.deactivated_at: set while the person has switched the account off. Nothing expires it — the daily job never
-- deletes a deactivated account (only `delete_at` does that). Deletion supersedes deactivation: delete_account on a
-- deactivated account first clears this (and restores the listings), then hides them for the deletion as usual.
ALTER TABLE accounts ADD COLUMN deactivated_at TIMESTAMPTZ;
CREATE INDEX accounts_deactivated_idx ON accounts (uid) WHERE deactivated_at IS NOT NULL;

-- the listing status before the deactivation hid it (status = 'suspended' meanwhile), like hidden_for_deletion
ALTER TABLE pharmacies ADD COLUMN hidden_for_deactivation TEXT;
ALTER TABLE labs ADD COLUMN hidden_for_deactivation TEXT;
ALTER TABLE ambulances ADD COLUMN hidden_for_deactivation TEXT;
ALTER TABLE blood_banks ADD COLUMN hidden_for_deactivation TEXT;

-- doctor / nurse / home care: listed, active and home_visit_enabled before the deactivation (NULL = not hidden)
ALTER TABLE providers ADD COLUMN deact_listed BOOLEAN;
ALTER TABLE providers ADD COLUMN deact_active BOOLEAN;
ALTER TABLE providers ADD COLUMN deact_home BOOLEAN;
