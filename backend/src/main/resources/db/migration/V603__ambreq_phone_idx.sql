-- ambreq_create: at most 5 requests an hour with the same contact number, over all accounts (security audit, Oct 2026)
CREATE INDEX ambulance_requests_phone_idx ON ambulance_requests (phone, created_at) WHERE phone IS NOT NULL;
