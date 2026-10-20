-- Paper prescriptions are added to an app account one by one, each by its own code (owner decision 2026-10).
-- Before, claiming one by code linked the paper patient (rx_patients.linked_uid) to the account and every later
-- prescription the doctor wrote for that patient went to the account automatically. Stop that for existing links too.
-- Prescriptions already claimed keep their patient_uid (nothing is taken away from anyone's account).
-- The column stays (unused, always NULL) so an older server build reading it still works during a rollback.
UPDATE rx_patients SET linked_uid = NULL WHERE linked_uid IS NOT NULL;
