-- care_invite answers the same whether or not the email has a Lifolo account (no account enumeration, Oct 2026).
-- An invitation to an address without an account waits here; when someone signs in with that email it becomes a
-- normal care_links invitation (AccountService.ensure). Counted towards the 2 caregivers like a care_links row.
CREATE TABLE care_email_invites (
    patient_uid   TEXT NOT NULL REFERENCES accounts (uid) ON DELETE CASCADE,
    email         TEXT NOT NULL,
    patient_name  TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (patient_uid, email)
);
CREATE INDEX care_email_invites_email_idx ON care_email_invites (email);
