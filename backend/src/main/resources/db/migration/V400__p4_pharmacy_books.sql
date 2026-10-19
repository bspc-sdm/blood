-- =====================================================================================
-- V400 (P4): pharmacy listing, orders, stock and shop books — fixes found while porting pharmacy.js, rxnet.js,
-- medfind.js and shopbooks.js (Node behaviour kept).
-- =====================================================================================

-- pharmacy.js aspects: behaviour has its own sum/count (ratingSum/ratingCount is the combined average of the aspects)
ALTER TABLE pharmacies ADD COLUMN aspect_behaviour_sum   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pharmacies ADD COLUMN aspect_behaviour_count INTEGER NOT NULL DEFAULT 0;

-- pharmacyRatings/{uid}_{id}: rating = rounded average of the given aspects; an old (legacy) rating has no aspects
ALTER TABLE pharmacy_ratings ADD COLUMN rating SMALLINT CHECK (rating BETWEEN 1 AND 5);
ALTER TABLE pharmacy_ratings ALTER COLUMN behaviour DROP NOT NULL;

-- Node keeps these when a pharmacy listing is removed (pharmacy_delete): online orders stay in the patient's history
-- (contacts stripped), medicine-find answers, call records, stock list, missed list and the shop books. Only the
-- account deletion removes the shop's own data → owner FK on accounts instead of pharmacies.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT c.conname, c.conrelid::regclass::text AS tbl FROM pg_constraint c
           WHERE c.contype = 'f' AND c.confrelid = 'pharmacies'::regclass
             AND c.conrelid::regclass::text IN ('rx_orders', 'med_find_targets', 'pharmacy_calls', 'pharmacy_stock',
                                                'pharmacy_missed', 'shop_items', 'shop_purchases', 'shop_sales',
                                                'shop_adjustments', 'shop_days')
  LOOP
    EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.conname);
  END LOOP;
END $$;
ALTER TABLE pharmacy_stock   ADD CONSTRAINT pharmacy_stock_owner_fkey   FOREIGN KEY (pharmacy_uid) REFERENCES accounts (uid) ON DELETE CASCADE;
ALTER TABLE pharmacy_missed  ADD CONSTRAINT pharmacy_missed_owner_fkey  FOREIGN KEY (pharmacy_uid) REFERENCES accounts (uid) ON DELETE CASCADE;
ALTER TABLE shop_items       ADD CONSTRAINT shop_items_owner_fkey       FOREIGN KEY (shop_uid) REFERENCES accounts (uid) ON DELETE CASCADE;
ALTER TABLE shop_purchases   ADD CONSTRAINT shop_purchases_owner_fkey   FOREIGN KEY (shop_uid) REFERENCES accounts (uid) ON DELETE CASCADE;
ALTER TABLE shop_sales       ADD CONSTRAINT shop_sales_owner_fkey       FOREIGN KEY (shop_uid) REFERENCES accounts (uid) ON DELETE CASCADE;
ALTER TABLE shop_adjustments ADD CONSTRAINT shop_adjustments_owner_fkey FOREIGN KEY (shop_uid) REFERENCES accounts (uid) ON DELETE CASCADE;
ALTER TABLE shop_days        ADD CONSTRAINT shop_days_owner_fkey        FOREIGN KEY (shop_uid) REFERENCES accounts (uid) ON DELETE CASCADE;
CREATE INDEX pharmacy_calls_shop_idx ON pharmacy_calls (pharmacy_uid);

-- shopbooks.js: quantities are 2-decimal numbers (r2), not integers; unit '' = not given (newItem)
ALTER TABLE shop_items DROP CONSTRAINT IF EXISTS shop_items_unit_check;
ALTER TABLE shop_items ALTER COLUMN unit SET DEFAULT '';
ALTER TABLE shop_items ADD CONSTRAINT shop_items_unit_check
    CHECK (unit IN ('', 'piece', 'strip', 'box', 'bottle', 'tube', 'vial', 'sachet', 'other'));
ALTER TABLE shop_items ALTER COLUMN qty TYPE NUMERIC(12, 2);
ALTER TABLE shop_items ALTER COLUMN low_at TYPE NUMERIC(12, 2);
-- Firestore did not enforce one product per name key (a renamed product keeps its old id); the check is in the action
DROP INDEX IF EXISTS shop_items_key_idx;
CREATE INDEX shop_items_key_idx ON shop_items (shop_uid, key);

ALTER TABLE shop_purchase_lines ALTER COLUMN qty TYPE NUMERIC(12, 2);
-- undo.qty null = the product had no quantity before the purchase
ALTER TABLE shop_purchase_undo ALTER COLUMN qty DROP DEFAULT;
ALTER TABLE shop_purchase_undo ALTER COLUMN qty DROP NOT NULL;
ALTER TABLE shop_purchase_undo ALTER COLUMN qty TYPE NUMERIC(12, 2);

ALTER TABLE shop_sale_lines ALTER COLUMN qty TYPE NUMERIC(12, 2);
-- dec = how much the product quantity really went down (a sale never takes it below 0); null on old sales
ALTER TABLE shop_sale_lines DROP COLUMN dec;
ALTER TABLE shop_sale_lines ADD COLUMN dec NUMERIC(12, 2);

ALTER TABLE shop_adjustments ALTER COLUMN qty_before TYPE NUMERIC(12, 2);
ALTER TABLE shop_adjustments ALTER COLUMN qty_after DROP NOT NULL;
ALTER TABLE shop_adjustments ALTER COLUMN qty_after TYPE NUMERIC(12, 2);
ALTER TABLE shop_adjustments ALTER COLUMN delta TYPE NUMERIC(12, 2);

ALTER TABLE shop_days ALTER COLUMN woq TYPE NUMERIC(14, 2);
ALTER TABLE shop_day_items ALTER COLUMN q TYPE NUMERIC(14, 2);
ALTER TABLE shop_day_items ALTER COLUMN nq TYPE NUMERIC(14, 2);

-- medfind.js dailyCleanup: finds expired more than 2 days ago
CREATE INDEX med_finds_expires_idx ON med_finds (expires_at);
