-- Invoices (রসিদ) — spring-p16, 23 Oct 2026 (V661: above V660 chat), docs/INVOICES.md.
--
-- One receipt per sale / lab bill / chamber fee / ambulance trip. Immutable after issue (trigger below): only payments
-- are added (invoice_payments, append-only) and the status may go issued → void (with a reason). No delete through the
-- API. Kept 6 years after either account is deleted, with the personal fields removed (anonymised_at, purge_after).
--
-- No foreign keys to accounts on purpose: the rows must outlive the issuer's and the patient's accounts.

CREATE TABLE invoices (
    id             TEXT PRIMARY KEY,
    kind           TEXT NOT NULL CHECK (kind IN ('pharmacy', 'lab', 'chamber', 'ambulance')),
    -- pharmacy/lab: the owner's uid; chamber: the doctor's uid; ambulance: ambulances.id
    biz_id         TEXT NOT NULL,
    -- the account that owns the business (access); NULL once that account is deleted
    owner_uid      TEXT,
    no             TEXT NOT NULL CHECK (no ~ '^(W|D[1-9][0-9]{0,2})-[0-9]{6,9}$'),
    series         TEXT NOT NULL,
    seq            INTEGER NOT NULL CHECK (seq > 0),
    biz_name       TEXT NOT NULL DEFAULT '',
    biz_phone      TEXT,
    biz_address    TEXT,
    patient_uid    TEXT,
    customer_name  TEXT CHECK (length(customer_name) <= 80),
    customer_phone TEXT CHECK (length(customer_phone) <= 20),
    ref_type       TEXT CHECK (ref_type IN ('book_sale', 'rx_order', 'lab_order', 'appointment', 'ambreq')),
    ref_id         TEXT,
    -- [{name, qty, unit?, price, disc, amount, code?}] — money as numbers with 2 decimals
    lines          JSONB NOT NULL,
    subtotal       NUMERIC(12, 2) NOT NULL CHECK (subtotal >= 0),
    disc           NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (disc >= 0),
    -- [{label, amount}]
    fees           JSONB NOT NULL DEFAULT '[]',
    total          NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
    paid           NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (paid >= 0),
    due            NUMERIC(12, 2) NOT NULL CHECK (due >= 0),
    status         TEXT NOT NULL DEFAULT 'issued' CHECK (status IN ('issued', 'void')),
    -- a fixed reason (security review I2) + an optional note ("other" needs one)
    void_reason    TEXT CHECK (void_reason IN ('wrong_item', 'returned', 'wrong_price', 'duplicate', 'other')),
    void_note      TEXT CHECK (length(void_note) <= 200),
    voided_at      TIMESTAMPTZ,
    voided_by      TEXT,
    issued_at      TIMESTAMPTZ NOT NULL,
    issued_by      TEXT,
    device_id      TEXT,
    -- the number the desktop printed when the sync gave the receipt another one (spring-p17 renumbering)
    device_no      TEXT CHECK (length(device_no) <= 30),
    offline        BOOLEAN NOT NULL DEFAULT FALSE,
    verify_code    TEXT NOT NULL UNIQUE CHECK (verify_code ~ '^[A-Za-z0-9]{10}$'),
    -- ambulance: the fare is above the owner's fare chart (null = no chart estimate)
    over_fare      BOOLEAN,
    chart_fare     INTEGER,
    is_demo        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    anonymised_at  TIMESTAMPTZ,
    purge_after    TIMESTAMPTZ,
    UNIQUE (kind, biz_id, no),
    CHECK (disc <= subtotal),
    CHECK (due = total - paid),
    CHECK (status = 'issued' OR void_reason IS NOT NULL)
);
CREATE INDEX invoices_biz_idx ON invoices (kind, biz_id, issued_at DESC, id DESC);
CREATE INDEX invoices_patient_idx ON invoices (patient_uid, issued_at DESC, id DESC) WHERE patient_uid IS NOT NULL;
CREATE INDEX invoices_owner_idx ON invoices (owner_uid) WHERE owner_uid IS NOT NULL;
CREATE INDEX invoices_device_no_idx ON invoices (kind, biz_id, device_no) WHERE device_no IS NOT NULL;
CREATE INDEX invoices_purge_idx ON invoices (purge_after) WHERE purge_after IS NOT NULL;
-- one live receipt per source (a lab order, an appointment, a sale …); a voided one may be issued again
CREATE UNIQUE INDEX invoices_ref_live_idx ON invoices (ref_type, ref_id) WHERE status = 'issued' AND ref_id IS NOT NULL;

-- payments: append-only (only `ref` may be cleared when the invoice is anonymised)
CREATE TABLE invoice_payments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id  TEXT NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    method      TEXT NOT NULL CHECK (method IN ('cash', 'bkash', 'nagad', 'card', 'other')),
    ref         TEXT CHECK (length(ref) <= 40),
    by_uid      TEXT,
    device_id   TEXT
);
CREATE INDEX invoice_payments_invoice_idx ON invoice_payments (invoice_id, at, id);

-- per-business number series: W (server/web/phone) and D1, D2 … (desktop devices). The online number is taken with
-- INSERT … ON CONFLICT DO UPDATE … RETURNING (row lock on the counter); a device's own numbers move last_seq up.
CREATE TABLE invoice_series (
    kind      TEXT NOT NULL,
    biz_id    TEXT NOT NULL,
    series    TEXT NOT NULL CHECK (series ~ '^(W|D[1-9][0-9]{0,2})$'),
    last_seq  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, biz_id, series)
);

-- audit: issue / pay / void / anonymise (who, when, from which device)
CREATE TABLE invoice_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id  TEXT NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    action      TEXT NOT NULL CHECK (action IN ('issue', 'pay', 'void', 'anonymise', 'report')),
    by_uid      TEXT,
    device_id   TEXT,
    detail      JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX invoice_events_invoice_idx ON invoice_events (invoice_id, id);

-- immutability: what was issued never changes. Allowed: paid/due (payments), issued → void (+ reason/time/by), and the
-- retention clean-up (personal fields → NULL, anonymised_at, purge_after).
CREATE FUNCTION invoices_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.kind IS DISTINCT FROM OLD.kind OR NEW.biz_id IS DISTINCT FROM OLD.biz_id
        OR NEW.no IS DISTINCT FROM OLD.no OR NEW.series IS DISTINCT FROM OLD.series OR NEW.seq IS DISTINCT FROM OLD.seq
        OR NEW.biz_name IS DISTINCT FROM OLD.biz_name OR NEW.biz_address IS DISTINCT FROM OLD.biz_address
        OR NEW.ref_type IS DISTINCT FROM OLD.ref_type OR NEW.ref_id IS DISTINCT FROM OLD.ref_id
        OR NEW.lines IS DISTINCT FROM OLD.lines OR NEW.subtotal IS DISTINCT FROM OLD.subtotal OR NEW.disc IS DISTINCT FROM OLD.disc
        OR NEW.fees IS DISTINCT FROM OLD.fees OR NEW.total IS DISTINCT FROM OLD.total
        OR NEW.issued_at IS DISTINCT FROM OLD.issued_at OR NEW.device_id IS DISTINCT FROM OLD.device_id
        OR NEW.device_no IS DISTINCT FROM OLD.device_no
        OR NEW.offline IS DISTINCT FROM OLD.offline OR NEW.verify_code IS DISTINCT FROM OLD.verify_code
        OR NEW.over_fare IS DISTINCT FROM OLD.over_fare OR NEW.chart_fare IS DISTINCT FROM OLD.chart_fare
        OR NEW.is_demo IS DISTINCT FROM OLD.is_demo OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'invoice % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    -- personal fields may only be removed
    IF (NEW.owner_uid IS DISTINCT FROM OLD.owner_uid AND NEW.owner_uid IS NOT NULL)
        OR (NEW.patient_uid IS DISTINCT FROM OLD.patient_uid AND NEW.patient_uid IS NOT NULL)
        OR (NEW.customer_name IS DISTINCT FROM OLD.customer_name AND NEW.customer_name IS NOT NULL)
        OR (NEW.customer_phone IS DISTINCT FROM OLD.customer_phone AND NEW.customer_phone IS NOT NULL)
        OR (NEW.issued_by IS DISTINCT FROM OLD.issued_by AND NEW.issued_by IS NOT NULL)
        OR (NEW.biz_phone IS DISTINCT FROM OLD.biz_phone AND NEW.biz_phone IS NOT NULL)
        OR (NEW.voided_by IS DISTINCT FROM OLD.voided_by AND NEW.voided_by IS NOT NULL AND OLD.voided_by IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    -- void is final; paid only grows
    IF NEW.void_note IS DISTINCT FROM OLD.void_note AND NEW.void_note IS NOT NULL AND OLD.void_note IS NOT NULL THEN
        RAISE EXCEPTION 'invoice % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'void' AND (NEW.status <> 'void' OR NEW.void_reason IS DISTINCT FROM OLD.void_reason
        OR NEW.voided_at IS DISTINCT FROM OLD.voided_at OR NEW.paid IS DISTINCT FROM OLD.paid) THEN
        RAISE EXCEPTION 'invoice % is void', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.paid < OLD.paid THEN
        RAISE EXCEPTION 'invoice % payments cannot be removed', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER invoices_immutable BEFORE UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION invoices_immutable();

CREATE FUNCTION invoice_payments_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.invoice_id IS DISTINCT FROM OLD.invoice_id OR NEW.at IS DISTINCT FROM OLD.at
        OR NEW.amount IS DISTINCT FROM OLD.amount OR NEW.method IS DISTINCT FROM OLD.method
        OR NEW.device_id IS DISTINCT FROM OLD.device_id
        OR (NEW.ref IS DISTINCT FROM OLD.ref AND NEW.ref IS NOT NULL)
        OR (NEW.by_uid IS DISTINCT FROM OLD.by_uid AND NEW.by_uid IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice payment % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER invoice_payments_append_only BEFORE UPDATE ON invoice_payments FOR EACH ROW EXECUTE FUNCTION invoice_payments_append_only();

-- the patient says the business took more than the receipt shows (security review I8: "রসিদের চেয়ে বেশি নিয়েছে")
CREATE TABLE invoice_reports (
    invoice_id  TEXT NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    uid         TEXT NOT NULL,
    paid        NUMERIC(12, 2),
    note        TEXT CHECK (length(note) <= 300),
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (invoice_id, uid)
);

-- a purged receipt (6 years after an account deletion) still answers the verify page (security review I1)
CREATE TABLE invoice_tombstones (
    verify_code  TEXT PRIMARY KEY,
    kind         TEXT NOT NULL,
    biz_name     TEXT NOT NULL DEFAULT '',
    no           TEXT NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL,
    total        NUMERIC(12, 2) NOT NULL,
    line_count   INTEGER NOT NULL,
    status       TEXT NOT NULL,
    purged_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "call an ambulance": the fare the patient agreed to, frozen when the trip is accepted (security review I8) — the
-- chart estimate and the chart's extras that apply (null = not in the chart); ambreq_finish compares with these, never
-- with the chart as it is at the end of the trip
ALTER TABLE ambulance_requests ADD COLUMN snap_at TIMESTAMPTZ;
ALTER TABLE ambulance_requests ADD COLUMN snap_fare INTEGER;
ALTER TABLE ambulance_requests ADD COLUMN snap_oxygen INTEGER;
ALTER TABLE ambulance_requests ADD COLUMN snap_icu INTEGER;
ALTER TABLE ambulance_requests ADD COLUMN snap_night INTEGER;

-- the chamber desk (chamber_checkin): the patient has arrived at the chamber — by the doctor or an assistant
ALTER TABLE appointments ADD COLUMN arrived_at TIMESTAMPTZ;
ALTER TABLE appointments ADD COLUMN arrived_by TEXT;

-- online prescription orders: the pharmacy's priced lines (rx_order_update accepted + lines) and the new `delivered`
ALTER TABLE rx_orders ADD COLUMN invoice_lines JSONB;
ALTER TABLE rx_orders DROP CONSTRAINT IF EXISTS rx_orders_status_check;
ALTER TABLE rx_orders ADD CONSTRAINT rx_orders_status_check
    CHECK (status IN ('sent', 'ready', 'accepted', 'out', 'delivered', 'done', 'cancelled', 'withdrawn'));
