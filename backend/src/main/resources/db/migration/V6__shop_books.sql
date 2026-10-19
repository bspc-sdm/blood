-- =====================================================================================
-- V6: pharmacy shop books — products, purchases, sales, adjustments and daily aggregates
-- shopItems/{uid}/items/{id}, shopBooks/{uid}/{purchases|sales|adjust|days}/{id}
-- =====================================================================================

CREATE TABLE shop_items (
    shop_uid      TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    id            TEXT NOT NULL,
    -- normalized name key (shopbooks.js key()) used to match stock/prescriptions
    key           TEXT NOT NULL,
    name          TEXT NOT NULL CHECK (length(name) <= 100),
    generic       TEXT,
    form          TEXT,
    unit          TEXT NOT NULL DEFAULT 'piece' CHECK (unit IN ('piece', 'strip', 'box', 'bottle', 'tube', 'vial', 'sachet', 'other')),
    price         NUMERIC(12, 2),
    cost          NUMERIC(12, 4),
    disc_type     TEXT CHECK (disc_type IN ('pct', 'amt')),
    disc_value    NUMERIC(12, 2),
    qty           INTEGER,
    low_at        INTEGER,
    -- "YYYY-MM"
    expiry        TEXT CHECK (expiry ~ '^[0-9]{4}-[0-9]{2}$'),
    in_stock      BOOLEAN NOT NULL DEFAULT TRUE,
    last_sale_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_uid, id)
);
CREATE UNIQUE INDEX shop_items_key_idx ON shop_items (shop_uid, key);

CREATE TABLE shop_purchases (
    shop_uid    TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    id          TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    day         DATE NOT NULL,
    supplier    TEXT NOT NULL DEFAULT '',
    note        TEXT NOT NULL DEFAULT '',
    total       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_uid, id)
);
CREATE INDEX shop_purchases_at_idx ON shop_purchases (shop_uid, at DESC);
CREATE TABLE shop_purchase_lines (
    shop_uid     TEXT NOT NULL,
    purchase_id  TEXT NOT NULL,
    idx          INTEGER NOT NULL,
    item_id      TEXT,
    name         TEXT NOT NULL,
    qty          INTEGER NOT NULL,
    unit_cost    NUMERIC(12, 4),
    expiry       TEXT,
    total        NUMERIC(14, 2),
    PRIMARY KEY (shop_uid, purchase_id, idx),
    FOREIGN KEY (shop_uid, purchase_id) REFERENCES shop_purchases (shop_uid, id) ON DELETE CASCADE
);
-- purchases.undo{itemId: {qty, cost, price, expiry, inStock, after{cost, price, expiry}}}
CREATE TABLE shop_purchase_undo (
    shop_uid      TEXT NOT NULL,
    purchase_id   TEXT NOT NULL,
    item_id       TEXT NOT NULL,
    qty           INTEGER NOT NULL DEFAULT 0,
    cost          NUMERIC(12, 4),
    price         NUMERIC(12, 2),
    expiry        TEXT,
    in_stock      BOOLEAN,
    after_cost    NUMERIC(12, 4),
    after_price   NUMERIC(12, 2),
    after_expiry  TEXT,
    PRIMARY KEY (shop_uid, purchase_id, item_id),
    FOREIGN KEY (shop_uid, purchase_id) REFERENCES shop_purchases (shop_uid, id) ON DELETE CASCADE
);

CREATE TABLE shop_sales (
    shop_uid         TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    id               TEXT NOT NULL,
    at               TIMESTAMPTZ NOT NULL,
    day              DATE NOT NULL,
    source           TEXT NOT NULL CHECK (source IN ('counter', 'rx', 'online')),
    customer         TEXT NOT NULL DEFAULT '',
    bill_disc_type   TEXT CHECK (bill_disc_type IN ('pct', 'amt')),
    bill_disc_value  NUMERIC(12, 2),
    -- rx {code, at, rxId}: which dispensing this sale records (one sale per dispensing)
    rx_code          TEXT,
    rx_at            BIGINT,
    rx_id            TEXT,
    -- totals {gross, discount, billDiscount, net, cost, profit, netCosted, unpriced}
    gross            NUMERIC(14, 2) NOT NULL DEFAULT 0,
    discount         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    bill_discount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    net              NUMERIC(14, 2) NOT NULL DEFAULT 0,
    cost             NUMERIC(14, 4) NOT NULL DEFAULT 0,
    profit           NUMERIC(14, 4) NOT NULL DEFAULT 0,
    net_costed       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    unpriced         INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_uid, id)
);
CREATE INDEX shop_sales_at_idx ON shop_sales (shop_uid, at DESC);
CREATE UNIQUE INDEX shop_sales_rx_idx ON shop_sales (shop_uid, rx_code, rx_at) WHERE rx_code IS NOT NULL;
CREATE TABLE shop_sale_lines (
    shop_uid    TEXT NOT NULL,
    sale_id     TEXT NOT NULL,
    idx         INTEGER NOT NULL,
    item_id     TEXT,
    name        TEXT NOT NULL,
    qty         INTEGER NOT NULL,
    unit_price  NUMERIC(12, 2),
    unit_disc   NUMERIC(12, 2),
    discount    NUMERIC(14, 2),
    net         NUMERIC(14, 2),
    unit_cost   NUMERIC(12, 4),
    cost        NUMERIC(14, 4),
    profit      NUMERIC(14, 4),
    -- prescription medicine index ("i"), and flags for lines without a product / qty decrement
    med_idx     INTEGER,
    no_item     BOOLEAN NOT NULL DEFAULT FALSE,
    dec         BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (shop_uid, sale_id, idx),
    FOREIGN KEY (shop_uid, sale_id) REFERENCES shop_sales (shop_uid, id) ON DELETE CASCADE
);

CREATE TABLE shop_adjustments (
    shop_uid    TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    id          TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    day         DATE NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('writeoff', 'count')),
    item_id     TEXT NOT NULL,
    name        TEXT NOT NULL,
    qty_before  INTEGER,
    qty_after   INTEGER NOT NULL,
    delta       INTEGER NOT NULL,
    unit_cost   NUMERIC(12, 4),
    loss        NUMERIC(14, 4) NOT NULL DEFAULT 0,
    reason      TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_uid, id)
);
CREATE INDEX shop_adjustments_at_idx ON shop_adjustments (shop_uid, at DESC);

-- shopBooks/{uid}/days/{YYYYMMDD} — daily aggregates kept in step with every write (report/today read only these)
CREATE TABLE shop_days (
    shop_uid    TEXT NOT NULL REFERENCES pharmacies (uid) ON DELETE CASCADE,
    day         DATE NOT NULL,
    sales       INTEGER NOT NULL DEFAULT 0,
    purchases   INTEGER NOT NULL DEFAULT 0,
    adjusts     INTEGER NOT NULL DEFAULT 0,
    gross       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    discount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    net         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    -- net of costed lines (profit margin base)
    nc          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    cost        NUMERIC(14, 4) NOT NULL DEFAULT 0,
    profit      NUMERIC(14, 4) NOT NULL DEFAULT 0,
    bought      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    write_off   NUMERIC(14, 4) NOT NULL DEFAULT 0,
    -- write-off quantity
    woq         INTEGER NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_uid, day)
);
-- days.src{counter|rx|online: {n, net}}
CREATE TABLE shop_day_sources (
    shop_uid  TEXT NOT NULL,
    day       DATE NOT NULL,
    source    TEXT NOT NULL CHECK (source IN ('counter', 'rx', 'online')),
    n         INTEGER NOT NULL DEFAULT 0,
    net       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_uid, day, source),
    FOREIGN KEY (shop_uid, day) REFERENCES shop_days (shop_uid, day) ON DELETE CASCADE
);
-- days.items{k<itemKey>|x<itemKey>: {n, q, net, profit, neg, nq}} — per-product day totals (x = write-off)
CREATE TABLE shop_day_items (
    shop_uid  TEXT NOT NULL,
    day       DATE NOT NULL,
    item_key  TEXT NOT NULL,
    name      TEXT NOT NULL DEFAULT '',
    q         INTEGER NOT NULL DEFAULT 0,
    net       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    profit    NUMERIC(14, 4) NOT NULL DEFAULT 0,
    neg       NUMERIC(14, 4) NOT NULL DEFAULT 0,
    nq        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_uid, day, item_key),
    FOREIGN KEY (shop_uid, day) REFERENCES shop_days (shop_uid, day) ON DELETE CASCADE
);
