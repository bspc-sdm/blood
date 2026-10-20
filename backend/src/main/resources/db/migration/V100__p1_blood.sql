-- =====================================================================================
-- V100 (P1 blood): fields the Node blood code writes that V2/V4 did not keep yet.
-- =====================================================================================

-- request_reopen: reopenedAt
ALTER TABLE blood_requests ADD COLUMN reopened_at TIMESTAMPTZ;

-- emergency.js closeExpired: closedBy 'expired'
ALTER TABLE emergencies ADD COLUMN closed_by TEXT;

-- emergency.js admin_emergency_group: groups[g].adminClosed (NULL = an admin never closed/opened the group;
-- the panel reads it from admin_emergencies, and a closed-by-admin group does not reopen when a donor cancels)
ALTER TABLE emergency_groups ADD COLUMN admin_closed BOOLEAN;

-- emergency.js emergency_respond (cancel): cancelledAt
ALTER TABLE emergency_donors ADD COLUMN cancelled_at TIMESTAMPTZ;

-- bloodbank.js admin_bloodbank_edit: editedByAdmin
ALTER TABLE blood_banks ADD COLUMN edited_by_admin_at TIMESTAMPTZ;

-- emergencies_active askDonated: the appeals a donor said "coming" to
CREATE INDEX emergency_donors_coming_idx ON emergency_donors (uid, coming_at) WHERE status = 'coming';
