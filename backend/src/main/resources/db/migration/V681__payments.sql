-- Lifolo Pay — manual MFS payments, gateway-ready (spring-p20, 23 Oct 2026), backend/docs/PAYMENTS.md, with the fixes of
-- docs/test-plan/PAYMENTS-SECURITY-REVIEW.md (P1–P13).
--
-- Money goes directly from the patient to the business (its own bKash / Nagad / Rocket / Upay number). Lifolo never
-- holds or settles anyone's money: it keeps the request, the patient's TrxID and the last 4 digits of the sender
-- number, and the business says "received". No payments in the blood context (the server refuses them).
--
-- No foreign keys to accounts on purpose: payment records outlive account deletion with the personal fields removed.

-- a business's MFS numbers. kind/biz_id as in invoices (pharmacy/lab: owner uid, chamber: doctor uid, ambulance:
-- ambulances.id). P4: a number reaches patients only after it has been active for 24 h (`active_since`): added,
-- re-added, re-activated or edited. While a changed number matures, `prev_number` (the matured one it replaced) is
-- still used; a deactivated or removed method is gone at once.
CREATE TABLE pay_methods (
    kind          TEXT NOT NULL CHECK (kind IN ('pharmacy', 'lab', 'chamber', 'ambulance')),
    biz_id        TEXT NOT NULL,
    method        TEXT NOT NULL CHECK (method IN ('bkash', 'nagad', 'rocket', 'upay')),
    number        TEXT NOT NULL CHECK (number ~ '^01[0-9]{9}$'),
    type          TEXT NOT NULL CHECK (type IN ('personal', 'merchant')),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    active_since  TIMESTAMPTZ,
    prev_number   TEXT CHECK (prev_number ~ '^01[0-9]{9}$'),
    prev_type     TEXT CHECK (prev_type IN ('personal', 'merchant')),
    position      INTEGER NOT NULL DEFAULT 0,
    owner_uid     TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, biz_id, method),
    CHECK (active = (active_since IS NOT NULL))
);

-- every change of a business's methods (audit; last 4 digits only)
CREATE TABLE pay_method_log (
    id         BIGSERIAL PRIMARY KEY,
    kind       TEXT NOT NULL,
    biz_id     TEXT NOT NULL,
    method     TEXT NOT NULL,
    change     TEXT NOT NULL CHECK (change IN ('added', 'number', 'type', 'on', 'off', 'removed', 'frozen', 'unfrozen')),
    old_last4  TEXT,
    new_last4  TEXT,
    by_uid     TEXT,
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pay_method_log_biz_idx ON pay_method_log (kind, biz_id, at DESC);

-- P4 "আমি করিনি": a business whose payment numbers are frozen (no number reaches anyone; only an admin unfreezes)
CREATE TABLE pay_freezes (
    kind        TEXT NOT NULL,
    biz_id      TEXT NOT NULL,
    frozen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    frozen_by   TEXT,
    via         TEXT NOT NULL CHECK (via IN ('app', 'email', 'admin')),
    PRIMARY KEY (kind, biz_id)
);

-- a doctor's in-person chamber asks an advance booking fee (pay_chamber_advance)
CREATE TABLE pay_chamber_advance (
    doctor_uid         TEXT NOT NULL,
    chamber_key        TEXT NOT NULL,
    amount             INTEGER NOT NULL CHECK (amount BETWEEN 1 AND 100000),
    refundable_hours   INTEGER NOT NULL DEFAULT 0 CHECK (refundable_hours BETWEEN 0 AND 720),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (doctor_uid, chamber_key)
);

-- one pay request: who pays whom how much for what; `methods` = the business's matured numbers at creation (snapshot)
CREATE TABLE pay_requests (
    id               TEXT PRIMARY KEY,
    kind             TEXT NOT NULL CHECK (kind IN ('pharmacy', 'lab', 'chamber', 'ambulance')),
    biz_id           TEXT NOT NULL,
    owner_uid        TEXT,
    biz_name         TEXT NOT NULL DEFAULT '',
    payer_uid        TEXT,
    ref_type         TEXT NOT NULL CHECK (ref_type IN ('appointment', 'rx_order', 'lab_order', 'ambreq', 'invoice')),
    ref_id           TEXT NOT NULL,
    purpose          TEXT NOT NULL CHECK (purpose IN ('chamber_fee', 'booking_advance', 'delivery', 'lab', 'ambulance_fare', 'invoice_due')),
    amount           NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    methods          JSONB NOT NULL DEFAULT '[]',
    status           TEXT NOT NULL DEFAULT 'open'
                     CHECK (status IN ('open', 'claimed', 'confirmed', 'rejected', 'expired', 'refund_claimed', 'refunded', 'cancelled')),
    claim_method     TEXT CHECK (claim_method IN ('bkash', 'nagad', 'rocket', 'upay')),
    claim_trx        TEXT CHECK (claim_trx ~ '^[A-Z0-9]{6,20}$'),
    claim_last4      TEXT CHECK (claim_last4 ~ '^[0-9]{4}$'),
    claimed_at       TIMESTAMPTZ,
    claimed_by       TEXT,
    confirmed_at     TIMESTAMPTZ,
    confirmed_by     TEXT,
    reject_reason    TEXT CHECK (reject_reason IN ('not_received', 'wrong_amount', 'wrong_number', 'other')),
    reject_note      TEXT CHECK (length(reject_note) <= 200),
    rejected_at      TIMESTAMPTZ,
    refund_trx       TEXT CHECK (refund_trx ~ '^[A-Z0-9]{6,20}$'),
    refund_amount    NUMERIC(12, 2) CHECK (refund_amount > 0),
    refunded_at      TIMESTAMPTZ,
    refunded_by      TEXT,
    refund_ack_at    TIMESTAMPTZ,
    -- P11: the business cancelled / did not deliver after the money came: it owes a refund (set by the job)
    refund_due_at    TIMESTAMPTZ,
    refund_due_why   TEXT,
    invoice_id       TEXT,
    -- the receipt was made for this request (a full refund voids it); false = a payment added to an existing receipt
    invoice_issued   BOOLEAN NOT NULL DEFAULT FALSE,
    refundable_until TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    reminded_at      TIMESTAMPTZ,
    flagged_at       TIMESTAMPTZ,
    created_by       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_demo          BOOLEAN NOT NULL DEFAULT FALSE,
    anonymised_at    TIMESTAMPTZ,
    CHECK (status <> 'claimed' OR claimed_at IS NOT NULL),
    CHECK (status NOT IN ('refund_claimed', 'refunded') OR refunded_at IS NOT NULL)
);
-- a TrxID only once per business (any purpose)
CREATE UNIQUE INDEX pay_requests_trx_uniq ON pay_requests (kind, biz_id, claim_trx) WHERE claim_trx IS NOT NULL;
-- one live (not yet paid) request per source and purpose (creating again returns it)
CREATE UNIQUE INDEX pay_requests_live_uniq ON pay_requests (ref_type, ref_id, purpose) WHERE status IN ('open', 'claimed', 'rejected');
CREATE INDEX pay_requests_ref_idx ON pay_requests (ref_type, ref_id);
CREATE INDEX pay_requests_payer_idx ON pay_requests (payer_uid, created_at DESC, id DESC) WHERE payer_uid IS NOT NULL;
CREATE INDEX pay_requests_biz_idx ON pay_requests (kind, biz_id, created_at DESC, id DESC);
CREATE INDEX pay_requests_claimed_idx ON pay_requests (claimed_at) WHERE status = 'claimed';
CREATE INDEX pay_requests_expiry_idx ON pay_requests (expires_at) WHERE status IN ('open', 'rejected');
CREATE INDEX pay_requests_refund_due_idx ON pay_requests (refund_due_at) WHERE refund_due_at IS NOT NULL;

-- append-only history of every request (who, from → to, TrxID, note)
CREATE TABLE pay_events (
    id           BIGSERIAL PRIMARY KEY,
    request_id   TEXT NOT NULL REFERENCES pay_requests (id) ON DELETE CASCADE,
    action       TEXT NOT NULL,
    by_uid       TEXT,
    payer_uid    TEXT,
    from_status  TEXT,
    to_status    TEXT,
    trx          TEXT,
    note         TEXT CHECK (length(note) <= 300),
    at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pay_events_request_idx ON pay_events (request_id, id);
CREATE INDEX pay_events_payer_idx ON pay_events (payer_uid, action, at DESC) WHERE payer_uid IS NOT NULL;

-- every claim attempt (rate limits: 5 per request an hour, 20 per person a day)
CREATE TABLE pay_claim_tries (
    id          BIGSERIAL PRIMARY KEY,
    request_id  TEXT NOT NULL,
    payer_uid   TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX pay_claim_tries_req_idx ON pay_claim_tries (request_id, at);
CREATE INDEX pay_claim_tries_payer_idx ON pay_claim_tries (payer_uid, at);

-- pay_report → admins (a report on a rejected claim disputes the rejection: it no longer counts against the payer)
CREATE TABLE pay_reports (
    id            TEXT PRIMARY KEY,
    request_id    TEXT NOT NULL REFERENCES pay_requests (id) ON DELETE CASCADE,
    reporter_uid  TEXT,
    payer_uid     TEXT,
    reason        TEXT NOT NULL CHECK (reason IN ('not_confirmed', 'no_refund', 'wrong_number', 'asked_more', 'asked_other_number', 'scam', 'other')),
    note          TEXT CHECK (length(note) <= 300),
    disputes_reject BOOLEAN NOT NULL DEFAULT FALSE,
    status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    outcome       TEXT CHECK (outcome IN ('business_fault', 'payer_fault', 'no_fault')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    resolved_by   TEXT
);
CREATE INDEX pay_reports_status_idx ON pay_reports (status, created_at DESC);
CREATE INDEX pay_reports_request_idx ON pay_reports (request_id);

-- bKash Tokenized Checkout — Lifolo's own income only (purpose lifolo_subscription). P7: the amount is the server's
-- price, merchantInvoiceNumber = id, success only from execute/query matching this row; one grant per row.
CREATE TABLE pgw_payments (
    id              TEXT PRIMARY KEY,
    uid             TEXT,
    provider        TEXT NOT NULL DEFAULT 'bkash',
    purpose         TEXT NOT NULL CHECK (purpose IN ('lifolo_subscription')),
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    payment_id      TEXT UNIQUE,
    status          TEXT NOT NULL DEFAULT 'created' CHECK (status IN ('created', 'completed', 'failed', 'cancelled', 'refunded')),
    trx_id          TEXT UNIQUE,
    client          TEXT NOT NULL DEFAULT 'app' CHECK (client IN ('app', 'web')),
    provider_status TEXT,
    refund_trx      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    checked_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    is_demo         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX pgw_payments_uid_idx ON pgw_payments (uid, created_at DESC);
CREATE INDEX pgw_payments_pending_idx ON pgw_payments (created_at) WHERE status = 'created';

-- P5 receipts: a refund. In full → the receipt is voided with the new reason `refunded`; in part → a refund line
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_void_reason_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_void_reason_check
    CHECK (void_reason IN ('wrong_item', 'returned', 'wrong_price', 'duplicate', 'other', 'refunded'));
ALTER TABLE invoices ADD COLUMN refunded NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (refunded >= 0);
ALTER TABLE invoice_events DROP CONSTRAINT IF EXISTS invoice_events_action_check;
ALTER TABLE invoice_events ADD CONSTRAINT invoice_events_action_check CHECK (action IN ('issue', 'pay', 'void', 'anonymise', 'report', 'refund'));
CREATE TABLE invoice_refunds (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id  TEXT NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    ref         TEXT CHECK (length(ref) <= 40),
    by_uid      TEXT
);
CREATE INDEX invoice_refunds_invoice_idx ON invoice_refunds (invoice_id, at, id);

-- the online chamber's bKash number moves to the methods (one code path, P4.5): the doctor's data stays valid, a number
-- changed in the last 24 h keeps maturing (the earlier one stays in use meanwhile)
INSERT INTO pay_methods (kind, biz_id, method, number, type, active, active_since, prev_number, prev_type, owner_uid, updated_at)
SELECT 'chamber', c.provider_uid, 'bkash', c.bkash_number, 'personal', TRUE,
       CASE WHEN p.online_bkash_changed_at > now() - interval '24 hours' THEN p.online_bkash_changed_at ELSE now() - interval '30 days' END,
       CASE WHEN p.online_bkash_changed_at > now() - interval '24 hours' AND p.online_bkash_prev ~ '^01[0-9]{9}$' THEN p.online_bkash_prev END,
       CASE WHEN p.online_bkash_changed_at > now() - interval '24 hours' AND p.online_bkash_prev ~ '^01[0-9]{9}$' THEN 'personal' END,
       c.provider_uid, now()
FROM provider_chambers c JOIN providers p ON p.uid = c.provider_uid
WHERE c.mode = 'online' AND c.bkash_number ~ '^01[0-9]{9}$'
ON CONFLICT DO NOTHING;

-- the open online-chamber fees become chamber_fee requests (the number and fee as booked)
INSERT INTO pay_requests (id, kind, biz_id, owner_uid, biz_name, payer_uid, ref_type, ref_id, purpose, amount, methods, status,
                          claim_method, claim_trx, claimed_at, confirmed_at, confirmed_by, created_at, updated_at, is_demo)
SELECT 'prm' || substr(md5(a.id), 1, 20), 'chamber', a.provider_uid, a.provider_uid, a.provider_name, a.patient_uid, 'appointment', a.id,
       'chamber_fee', x.amount,
       jsonb_build_array(jsonb_build_object('method', 'bkash', 'number', a.bkash_number, 'type', 'personal')),
       CASE a.pay_state WHEN 'unpaid' THEN 'open' WHEN 'claimed' THEN 'claimed' ELSE 'confirmed' END,
       CASE WHEN a.pay_ref ~ '^[A-Z0-9]{6,20}$' THEN 'bkash' END,
       CASE WHEN a.pay_ref ~ '^[A-Z0-9]{6,20}$' THEN a.pay_ref END,
       CASE WHEN a.pay_state = 'claimed' THEN coalesce(a.pay_claimed_at, now()) ELSE a.pay_claimed_at END,
       CASE WHEN a.pay_state = 'paid' THEN coalesce(a.pay_paid_at, now()) END, a.pay_paid_by, a.created_at, now(), a.patient_uid LIKE 'demo-%'
FROM appointments a
CROSS JOIN LATERAL (
    SELECT NULLIF(substring(translate(coalesce(a.fee, ''), '০১২৩৪৫৬৭৮৯', '0123456789') FROM '([0-9]+)'), '')::NUMERIC AS amount
) x
WHERE a.online AND a.pay_state IS NOT NULL AND a.status IN ('pending', 'confirmed', 'completed')
  AND a.bkash_number ~ '^01[0-9]{9}$' AND x.amount > 0 AND x.amount < 10000000
ON CONFLICT DO NOTHING;

-- P2: "asked me to send money to another number" — a report reason for calls and messages too
ALTER TABLE call_reports DROP CONSTRAINT IF EXISTS call_reports_reason_check;
ALTER TABLE call_reports ADD CONSTRAINT call_reports_reason_check CHECK (reason IN ('harassment', 'spam', 'fake', 'asked_other_number', 'other'));
ALTER TABLE chat_reports DROP CONSTRAINT IF EXISTS chat_reports_reason_check;
ALTER TABLE chat_reports ADD CONSTRAINT chat_reports_reason_check CHECK (reason IN ('harassment', 'spam', 'fake', 'asked_other_number', 'other'));
