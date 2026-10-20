-- correction_create (Oct 2026): one waiting correction request per person (two taps at once cannot make two)
CREATE UNIQUE INDEX correction_requests_one_pending ON correction_requests (uid) WHERE status = 'pending';
