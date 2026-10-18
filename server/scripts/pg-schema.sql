-- Lifolo: Firestore-এর মতো ডক স্টোর, PostgreSQL-এ (lib/pgstore.js এটা ব্যবহার করে)।
-- বারবার চালানো নিরাপদ (IF NOT EXISTS / CREATE OR REPLACE)। pgstore চালু হওয়ার সময় ডাটাবেসের fs_meta.schema_version
-- এই ফাইলের শেষের সংস্করণের সাথে না মিললে তবেই চালায় (মিললে কোনো DDL নয় — চালুর সময় লেখা আটকায় না)।
-- PG_AUTO_SCHEMA=0 দিলে চালায়ই না — তখন হাতে: psql "$DATABASE_URL" -f scripts/pg-schema.sql
-- এই ফাইল বদলালে শেষের schema_version বাড়ান, নইলে চালু থাকা ডাটাবেসে বদল পৌঁছাবে না।
--
-- এক টেবিল, প্রতিটা Firestore ডক একটা সারি:
--   path = পুরো ঠিকানা (requests/abc/responses/xyz), coll = কালেকশনের ঠিকানা (requests/abc/responses),
--   grp = শেষ কালেকশনের নাম (responses — collectionGroup-এর জন্য), id = ডকের id, data = ডকের তথ্য (jsonb)।
-- লেখা আর সাজানো বাইট ধরে (COLLATE "C") — Firestore-এর মতো, আর geohash-এর '~' শেষ-সীমা ঠিক কাজ করে।
--
-- jsonb-তে বিশেষ মানের রূপ (pgstore.js লেখে আর পড়ে):
--   Timestamp  {"$ts": {"s": সেকেন্ড, "n": ন্যানোসেকেন্ড}}
--   GeoPoint   {"$geo": {"lat": .., "lng": ..}}
--   Reference  {"$ref": "users/abc"}
--   Bytes      {"$bytes": "base64"}
--   NaN/±Infinity {"$num": "NaN"} / {"$num": "Infinity"} / {"$num": "-Infinity"}
--   লেখা (মান আর চাবি): U+0000 → U+0001 U+0001, U+0001 → U+0001 U+0002 (jsonb U+0000 রাখতে পারে না; ক্রম একই থাকে)
--   ডকের ভেতরের কোনো map-এর চাবি '$' দিয়ে শুরু হলে আরেকটা '$' জুড়ে রাখা হয় ("$x" → "$$x")।

-- schema-র সংস্করণ (pgstore.ensureSchema দেখে)
CREATE TABLE IF NOT EXISTS fs_meta (key text PRIMARY KEY, value text NOT NULL);

CREATE TABLE IF NOT EXISTS docs (
  path       text COLLATE "C" PRIMARY KEY,
  coll       text COLLATE "C" NOT NULL,
  grp        text COLLATE "C" NOT NULL,
  id         text COLLATE "C" NOT NULL,
  data       jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- কালেকশনের সব ডক (id-র ক্রমে — Firestore-এর orderBy ছাড়া ক্রম), collectionGroup, আর == / array-contains
CREATE INDEX IF NOT EXISTS docs_coll_id ON docs (coll, id);
CREATE INDEX IF NOT EXISTS docs_grp_path ON docs (grp, path);
CREATE INDEX IF NOT EXISTS docs_data_gin ON docs USING gin (data jsonb_path_ops);

-- Firestore-এর ধরনভিত্তিক ক্রম: null < bool < number < timestamp < string < bytes < reference < geopoint < array < map
-- (SQL ফাংশন, IMMUTABLE — প্ল্যানার ভেতরে বসিয়ে নেয়, আর expression index-এ ব্যবহার করা যায়)
CREATE OR REPLACE FUNCTION fs_rank(x jsonb) RETURNS int
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE
    WHEN x IS NULL THEN NULL
    WHEN jsonb_typeof(x) = 'null' THEN 0
    WHEN jsonb_typeof(x) = 'boolean' THEN 1
    WHEN jsonb_typeof(x) = 'number' THEN 2
    WHEN jsonb_typeof(x) = 'string' THEN 4
    WHEN jsonb_typeof(x) = 'array' THEN 9
    WHEN x ? '$ts' THEN 3
    WHEN x ? '$num' THEN 2
    WHEN x ? '$bytes' THEN 5
    WHEN x ? '$ref' THEN 6
    WHEN x ? '$geo' THEN 7
    ELSE 10
  END
$$;

-- সংখ্যার মতো তুলনা: number, ±Infinity, bool (0/1), timestamp (সেকেন্ড.ন্যানো)। অন্য ধরনে (আর NaN-এ) NULL।
-- (বদলালে এই ফাংশনের expression index-গুলো REINDEX করতে হয় — পুরোনো সারির মান index-এ পুরোনো থাকে)
CREATE OR REPLACE FUNCTION fs_num(x jsonb) RETURNS numeric
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE
    WHEN x IS NULL THEN NULL
    WHEN jsonb_typeof(x) = 'number' THEN x::numeric
    WHEN jsonb_typeof(x) = 'boolean' THEN CASE WHEN x::boolean THEN 1::numeric ELSE 0::numeric END
    WHEN jsonb_typeof(x) = 'object' AND x ? '$ts'
      THEN (x->'$ts'->>'s')::numeric + (x->'$ts'->>'n')::numeric / 1000000000
    WHEN jsonb_typeof(x) = 'object' AND x ? '$num' AND x->>'$num' IN ('Infinity', '-Infinity')
      THEN (x->>'$num')::numeric
  END
$$;

-- লেখার মতো তুলনা: string, reference (ঠিকানা)। অন্য ধরনে NULL। ব্যবহারের জায়গায় COLLATE "C" দেওয়া হয়।
CREATE OR REPLACE FUNCTION fs_str(x jsonb) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE
    WHEN x IS NULL THEN NULL
    WHEN jsonb_typeof(x) = 'string' THEN x #>> '{}'
    WHEN jsonb_typeof(x) = 'object' AND x ? '$ref' THEN x->>'$ref'
  END
$$;

-- ঘন ঘন সাজানো/range করা ঘরের জন্য expression index (সব কালেকশনে একটাই, coll প্রথমে):
--   SELECT fs_index('createdAt', 'num');  -- সংখ্যা/সময়: orderBy, <, >, cursor
--   SELECT fs_index('geohash', 'str');    -- লেখা: geohash-এর startAt/endAt
-- নেস্টেড ঘর ডট দিয়ে ('home.at')। ঘরের নাম '$' দিয়ে শুরু হলে '$$' লিখুন।
CREATE OR REPLACE FUNCTION fs_index(field text, kind text DEFAULT 'num') RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
  expr text := 'data';
  seg text;
  name text;
  key text;
BEGIN
  IF kind NOT IN ('num', 'str') THEN RAISE EXCEPTION 'kind must be num or str'; END IF;
  FOREACH seg IN ARRAY string_to_array(field, '.') LOOP
    -- pgstore কোয়েরিতে যেমন লেখে ঠিক তেমন: সাধারণ নাম সরাসরি ->'নাম'
    IF seg !~ '^[A-Za-z_][A-Za-z0-9_]*$' THEN
      RAISE EXCEPTION 'fs_index: field segment % must match [A-Za-z_][A-Za-z0-9_]*', seg;
    END IF;
    expr := expr || '->' || quote_literal(seg);
  END LOOP;
  key := CASE kind WHEN 'num' THEN format('fs_num(%s)', expr) ELSE format('fs_str(%s) COLLATE "C"', expr) END;
  name := left(format('docs_%s_%s', kind, regexp_replace(field, '[^A-Za-z0-9]', '_', 'g')), 50) || '_' || left(md5(field), 6);
  EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON docs (coll, fs_rank(%s), %s, id) WHERE (%s) IS NOT NULL',
    name, expr, key, expr);
  RETURN name;
END
$$;

-- এখনকার কোডের গরম ঘরগুলো (খালি টেবিলে বানানো সস্তা; পরে দরকার হলে আরও যোগ করুন)
SELECT fs_index('donorGeo', 'str');
SELECT fs_index('geoOpen', 'str');
SELECT fs_index('geohash', 'str');
SELECT fs_index('createdAt', 'num');
SELECT fs_index('at', 'num');
SELECT fs_index('remindAt', 'num');

-- শেষে: এই সংস্করণ বসানো (ফাইল বদলালে সংখ্যা বাড়ান)
INSERT INTO fs_meta (key, value) VALUES ('schema_version', '2')
  ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
